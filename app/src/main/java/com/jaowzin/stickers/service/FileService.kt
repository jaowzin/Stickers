package com.jaowzin.stickers.service

import android.content.Context
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.annotation.Keep
import com.jaowzin.stickers.IFileService
import com.jaowzin.stickers.util.FileFormatDetector
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.LinkedHashMap

class FileService() : IFileService.Stub() {

    @Volatile
    private var cachedSignature: String? = null

    @Volatile
    private var cachedIndex: List<IndexedSticker> = emptyList()

    private val indexLock = Any()

    init {
        Log.i(TAG, "FileService iniciado")
    }

    @Keep
    constructor(context: Context) : this() {
        Log.i(TAG, "FileService iniciado com contexto: $context")
    }

    override fun destroy() {
        Log.i(TAG, "FileService encerrado")
        System.exit(0)
    }

    override fun countFiles(): Int = getUniqueStickerIndex().size

    override fun listItems(offset: Int, limit: Int): Array<String> {
        val safeOffset = offset.coerceAtLeast(0)
        val safeLimit = limit.coerceIn(1, MAX_PAGE_SIZE)
        val stickers = getUniqueStickerIndex()
        if (safeOffset >= stickers.size) return emptyArray()

        return stickers
            .subList(safeOffset, (safeOffset + safeLimit).coerceAtMost(stickers.size))
            .map { sticker ->
                val file = sticker.file
                val detection = sticker.detection
                JSONObject()
                    .put("path", file.absolutePath)
                    .put("name", file.name)
                    .put("size", file.length())
                    .put("lastModified", file.lastModified())
                    .put("format", detection.format)
                    .put("mimeType", detection.mimeType)
                    .put("extension", detection.extension)
                    .put("dataOffset", detection.offset)
                    .put("category", detection.category.name)
                    .put("animated", detection.animated)
                    .toString()
            }
            .toTypedArray()
    }

    override fun openContent(path: String, dataOffset: Long): ParcelFileDescriptor {
        val source = validatedFile(path)
        val offset = dataOffset.coerceIn(0L, source.length())
        val pipe = ParcelFileDescriptor.createPipe()

        Thread({
            runCatching {
                FileInputStream(source).use { input ->
                    skipFully(input, offset)

                    ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { output ->
                        input.copyTo(output, DEFAULT_BUFFER_SIZE)
                    }
                }
            }.onFailure { error ->
                Log.e(TAG, "Falha transmitindo ${source.absolutePath}", error)
                runCatching { pipe[1].closeWithError(error.message ?: "Erro de leitura") }
            }
        }, "sticker-file-stream").start()

        return pipe[0]
    }

    override fun canReadCache(): Boolean {
        val root = File(CACHE_ROOT)
        return root.isDirectory && root.canRead() && root.listFiles() != null
    }

    override fun getCacheRoot(): String = CACHE_ROOT

    /**
     * Retorna somente uma cópia de cada sticker.
     *
     * Os arquivos são ordenados do mais recente para o mais antigo. O SHA-256 é
     * calculado a partir do início real da mídia detectada, ignorando possíveis
     * cabeçalhos adicionados pelo cache Fresco. Como o primeiro hash vence, a
     * cópia mais recente é mantida.
     */
    private fun getUniqueStickerIndex(): List<IndexedSticker> {
        val files = collectCntFiles()
        val signature = buildMetadataSignature(files)

        if (signature == cachedSignature) return cachedIndex

        return synchronized(indexLock) {
            if (signature == cachedSignature) {
                cachedIndex
            } else {
                val uniqueByContent = LinkedHashMap<String, IndexedSticker>()

                for (file in files) {
                    val detection = FileFormatDetector.detect(file)
                    val contentHash = runCatching {
                        sha256OfContent(file, detection.offset)
                    }.getOrElse { error ->
                        Log.w(TAG, "Falha calculando hash de ${file.absolutePath}", error)
                        // Não elimina um arquivo ilegível por engano.
                        "unreadable:${file.absolutePath}:${file.length()}:${file.lastModified()}"
                    }

                    uniqueByContent.putIfAbsent(
                        contentHash,
                        IndexedSticker(file = file, detection = detection)
                    )
                }

                uniqueByContent.values.toList().also { result ->
                    val duplicatesRemoved = files.size - result.size
                    Log.i(
                        TAG,
                        "Índice atualizado: ${result.size} stickers únicos; " +
                            "$duplicatesRemoved duplicados removidos"
                    )
                    cachedIndex = result
                    cachedSignature = signature
                }
            }
        }
    }

    private fun collectCntFiles(): List<File> {
        val root = File(CACHE_ROOT)
        if (!root.isDirectory) return emptyList()

        val result = ArrayList<File>()
        val pending = ArrayDeque<File>()
        pending.add(root)

        while (pending.isNotEmpty()) {
            val directory = pending.removeFirst()
            val children = runCatching { directory.listFiles() }.getOrNull() ?: continue
            for (child in children) {
                when {
                    child.isDirectory -> pending.add(child)
                    child.isFile && child.extension.equals("cnt", ignoreCase = true) -> result.add(child)
                }
            }
        }

        result.sortWith(compareByDescending<File> { it.lastModified() }.thenBy { it.name })
        return result
    }

    private fun buildMetadataSignature(files: List<File>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        for (file in files) {
            digest.update(file.absolutePath.toByteArray(Charsets.UTF_8))
            digest.update(0.toByte())
            digest.update(file.length().toString().toByteArray(Charsets.UTF_8))
            digest.update(0.toByte())
            digest.update(file.lastModified().toString().toByteArray(Charsets.UTF_8))
            digest.update(0.toByte())
        }
        return digest.digest().toHex()
    }

    private fun sha256OfContent(file: File, dataOffset: Long): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val offset = dataOffset.coerceIn(0L, file.length())

        FileInputStream(file).use { input ->
            skipFully(input, offset)
            val buffer = ByteArray(HASH_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }

        return digest.digest().toHex()
    }

    private fun skipFully(input: FileInputStream, bytes: Long) {
        var remaining = bytes
        while (remaining > 0L) {
            val skipped = input.skip(remaining)
            if (skipped > 0L) {
                remaining -= skipped
            } else {
                if (input.read() == -1) break
                remaining--
            }
        }
    }

    private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
        (byte.toInt() and 0xFF).toString(16).padStart(2, '0')
    }

    private fun validatedFile(path: String): File {
        val root = File(CACHE_ROOT).canonicalFile
        val target = File(path).canonicalFile
        val insideRoot = target.path == root.path || target.path.startsWith(root.path + File.separator)
        require(insideRoot) { "Caminho fora do cache permitido" }
        require(target.isFile) { "Arquivo não encontrado" }
        return target
    }

    private data class IndexedSticker(
        val file: File,
        val detection: FileFormatDetector.Detection
    )

    companion object {
        private const val TAG = "StickerFileService"
        private const val MAX_PAGE_SIZE = 200
        private const val HASH_BUFFER_SIZE = 64 * 1024
        const val CACHE_ROOT = "/storage/emulated/0/Android/data/com.zhiliaoapp.musically/cache/picture/fresco_custom_cache/stable_sticker"
    }
}
