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
import java.util.ArrayDeque

class FileService() : IFileService.Stub() {

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

    override fun countFiles(): Int = collectCntFiles().size

    override fun listItems(offset: Int, limit: Int): Array<String> {
        val safeOffset = offset.coerceAtLeast(0)
        val safeLimit = limit.coerceIn(1, MAX_PAGE_SIZE)
        val files = collectCntFiles()
        if (safeOffset >= files.size) return emptyArray()

        return files
            .subList(safeOffset, (safeOffset + safeLimit).coerceAtMost(files.size))
            .map { file ->
                val detection = FileFormatDetector.detect(file)
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
                    var remaining = offset
                    while (remaining > 0L) {
                        val skipped = input.skip(remaining)
                        if (skipped <= 0L) {
                            if (input.read() == -1) break
                            remaining--
                        } else {
                            remaining -= skipped
                        }
                    }

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

    private fun validatedFile(path: String): File {
        val root = File(CACHE_ROOT).canonicalFile
        val target = File(path).canonicalFile
        val insideRoot = target.path == root.path || target.path.startsWith(root.path + File.separator)
        require(insideRoot) { "Caminho fora do cache permitido" }
        require(target.isFile) { "Arquivo não encontrado" }
        return target
    }

    companion object {
        private const val TAG = "StickerFileService"
        private const val MAX_PAGE_SIZE = 200
        const val CACHE_ROOT = "/storage/emulated/0/Android/data/com.zhiliaoapp.musically/cache/picture/fresco_custom_cache"
    }
}
