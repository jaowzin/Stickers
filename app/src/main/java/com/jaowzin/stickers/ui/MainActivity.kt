package com.jaowzin.stickers.ui

import android.Manifest
import android.app.AlertDialog
import android.content.ComponentName
import android.content.ContentValues
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.drawable.Animatable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import android.provider.Settings
import android.widget.MediaController
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jaowzin.stickers.BuildConfig
import com.jaowzin.stickers.IFileService
import com.jaowzin.stickers.R
import com.jaowzin.stickers.databinding.ActivityMainBinding
import com.jaowzin.stickers.databinding.DialogPreviewBinding
import com.jaowzin.stickers.databinding.ItemStickerBinding
import com.jaowzin.stickers.model.StickerItem
import com.jaowzin.stickers.service.FileService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: StickerAdapter
    private var fileService: IFileService? = null
    private var serviceBound = false
    private val loadingPaths = ConcurrentHashMap.newKeySet<String>()

    private val userServiceArgs by lazy {
        Shizuku.UserServiceArgs(ComponentName(BuildConfig.APPLICATION_ID, FileService::class.java.name))
            .daemon(false)
            .processNameSuffix("sticker-reader")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
            .tag("tiktok-sticker-cache-reader")
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        runOnUiThread {
            updateShizukuStatus()
            if (hasShizukuPermission()) bindFileService()
        }
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        runOnUiThread {
            fileService = null
            serviceBound = false
            updateShizukuStatus("Shizuku foi desconectado.")
        }
    }

    private val permissionResultListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == SHIZUKU_PERMISSION_REQUEST) {
            runOnUiThread {
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    bindFileService()
                } else {
                    updateShizukuStatus("Permissão do Shizuku negada.")
                }
            }
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder?) {
            fileService = IFileService.Stub.asInterface(binder)
            serviceBound = true
            runOnUiThread {
                updateShizukuStatus("Shizuku conectado. Lendo o cache como shell.")
                refreshItems()
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            fileService = null
            serviceBound = false
            runOnUiThread { updateShizukuStatus("Serviço de leitura desconectado.") }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarInsets()

        adapter = StickerAdapter(
            onClick = ::showPreview,
            onLoadThumbnail = ::loadThumbnail
        )
        binding.stickerGrid.layoutManager = GridLayoutManager(this, calculateSpanCount())
        binding.stickerGrid.adapter = adapter
        binding.stickerGrid.setHasFixedSize(true)

        binding.permissionButton.setOnClickListener { requestShizukuAccess() }
        binding.refreshButton.setOnClickListener { refreshItems() }
        binding.openShizukuButton.setOnClickListener { openShizuku() }
        binding.saveAllButton.setOnClickListener { saveAllStickers() }

        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionResultListener)

        updateShizukuStatus()
    }

    private fun applySystemBarInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.rootContainer) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(binding.rootContainer)
    }

    override fun onDestroy() {
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        Shizuku.removeRequestPermissionResultListener(permissionResultListener)
        if (serviceBound) {
            runCatching { Shizuku.unbindUserService(userServiceArgs, serviceConnection, false) }
        }
        super.onDestroy()
    }

    private fun calculateSpanCount(): Int {
        val widthDp = resources.displayMetrics.widthPixels / resources.displayMetrics.density
        return max(2, (widthDp / 136f).toInt())
    }

    private fun requestShizukuAccess() {
        if (!Shizuku.pingBinder()) {
            updateShizukuStatus("Inicie o Shizuku antes de continuar.")
            return
        }
        if (Shizuku.isPreV11()) {
            updateShizukuStatus("Esta versão do Shizuku é antiga demais. Atualize para a API 11 ou superior.")
            return
        }
        when {
            hasShizukuPermission() -> bindFileService()
            Shizuku.shouldShowRequestPermissionRationale() -> {
                updateShizukuStatus("A permissão foi bloqueada. Libere este app nas configurações do Shizuku.")
            }
            else -> Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST)
        }
    }

    private fun hasShizukuPermission(): Boolean = runCatching {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    private fun bindFileService() {
        if (serviceBound || !Shizuku.pingBinder()) return
        runCatching {
            Shizuku.bindUserService(userServiceArgs, serviceConnection)
            updateShizukuStatus("Conectando ao serviço privilegiado…")
        }.onFailure { showError("Não foi possível iniciar o serviço Shizuku", it) }
    }

    private fun updateShizukuStatus(customMessage: String? = null) {
        val alive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        val granted = alive && hasShizukuPermission()

        binding.statusText.text = customMessage ?: when {
            !alive -> "Shizuku não está ativo. Abra o Shizuku e inicie pela Depuração sem fio."
            !granted -> "Shizuku ativo. Conceda permissão para ler o cache do TikTok."
            fileService == null -> "Permissão concedida. Conectando ao leitor…"
            else -> "Pronto para ler os stickers do cache."
        }
        binding.permissionButton.isVisible = !granted
        binding.openShizukuButton.isVisible = !alive
        binding.refreshButton.isEnabled = fileService != null
        binding.saveAllButton.isEnabled = fileService != null && adapter.currentList.isNotEmpty()
    }

    private fun refreshItems() {
        val service = fileService ?: run {
            requestShizukuAccess()
            return
        }

        binding.loading.isVisible = true
        binding.emptyState.isVisible = false
        binding.refreshButton.isEnabled = false
        binding.saveAllButton.isEnabled = false
        binding.resultCount.text = "Lendo e removendo duplicados…"

        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    if (!service.canReadCache()) {
                        error("A pasta não existe ou o processo shell não conseguiu acessá-la: ${service.cacheRoot}")
                    }
                    val total = service.countFiles()
                    val items = ArrayList<StickerItem>(total)
                    var offset = 0
                    while (offset < total) {
                        val page = service.listItems(offset, PAGE_SIZE)
                        if (page.isEmpty()) break
                        page.mapTo(items) { StickerItem.fromJson(it) }
                        offset += page.size
                    }
                    deduplicateVisually(items)
                }
            }.onSuccess { items ->
                adapter.submitList(items)
                binding.resultCount.text = resources.getQuantityString(R.plurals.sticker_count, items.size, items.size)
                binding.emptyState.isVisible = items.isEmpty()
                binding.saveAllButton.isEnabled = items.isNotEmpty()
            }.onFailure { error ->
                adapter.submitList(emptyList())
                binding.resultCount.text = getString(R.string.no_results)
                binding.emptyState.isVisible = true
                showError("Falha ao ler os arquivos .cnt", error)
            }
            binding.loading.isVisible = false
            binding.refreshButton.isEnabled = fileService != null
        }
    }

    private fun deduplicateVisually(items: List<StickerItem>): List<StickerItem> {
        val seen = HashSet<String>()
        val unique = ArrayList<StickerItem>(items.size)

        for (item in items) {
            val key = runCatching {
                val file = materializePreview(item)
                visualFingerprint(item, file)
            }.getOrElse {
                "fallback:${item.path}:${item.size}:${item.lastModified}"
            }
            if (seen.add(key)) unique.add(item)
        }
        return unique
    }

    private fun loadThumbnail(item: StickerItem, itemBinding: ItemStickerBinding) {
        if (item.category == StickerItem.Category.UNKNOWN) {
            itemBinding.thumbnail.setImageResource(R.drawable.ic_file_unknown)
            return
        }
        if (!loadingPaths.add(item.path)) return

        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val file = materializePreview(item)
                    when (item.category) {
                        StickerItem.Category.IMAGE -> PreviewResult.DrawableResult(decodeDrawable(file, 420))
                        StickerItem.Category.VIDEO -> PreviewResult.BitmapResult(createVideoThumbnail(file))
                        StickerItem.Category.UNKNOWN -> PreviewResult.None
                    }
                }
            }
            loadingPaths.remove(item.path)

            if (itemBinding.thumbnail.tag != item.path) return@launch
            result.onSuccess { preview ->
                when (preview) {
                    is PreviewResult.DrawableResult -> {
                        itemBinding.thumbnail.setImageDrawable(preview.drawable)
                        (preview.drawable as? Animatable)?.start()
                    }
                    is PreviewResult.BitmapResult -> itemBinding.thumbnail.setImageBitmap(preview.bitmap)
                    PreviewResult.None -> itemBinding.thumbnail.setImageResource(R.drawable.ic_file_unknown)
                }
            }.onFailure {
                itemBinding.thumbnail.setImageResource(R.drawable.ic_broken_image)
            }
        }
    }

    private fun showPreview(item: StickerItem) {
        val dialogBinding = DialogPreviewBinding.inflate(layoutInflater)
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(item.format)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.save_sticker, null)
            .setNegativeButton(R.string.close, null)
            .create()

        dialogBinding.previewInfo.text = buildString {
            append(formatBytes(item.size))
            append(" • ")
            append(DateFormat.getDateTimeInstance().format(Date(item.lastModified)))
            if (item.dataOffset > 0) append(" • cabeçalho removido: ${item.dataOffset} bytes")
        }
        dialogBinding.previewPath.text = item.path
        dialog.show()

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            saveSingleSticker(item)
        }

        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { materializePreview(item) }
            }
            if (!dialog.isShowing) return@launch
            dialogBinding.previewLoading.isVisible = false

            result.onSuccess { file ->
                when (item.category) {
                    StickerItem.Category.IMAGE -> {
                        val drawable = withContext(Dispatchers.IO) { decodeDrawable(file, 1400) }
                        dialogBinding.previewImage.isVisible = true
                        dialogBinding.previewImage.setImageDrawable(drawable)
                        (drawable as? Animatable)?.start()
                    }
                    StickerItem.Category.VIDEO -> {
                        dialogBinding.previewVideo.isVisible = true
                        val controller = MediaController(this@MainActivity)
                        controller.setAnchorView(dialogBinding.previewVideo)
                        dialogBinding.previewVideo.setMediaController(controller)
                        dialogBinding.previewVideo.setVideoURI(Uri.fromFile(file))
                        dialogBinding.previewVideo.setOnPreparedListener { player ->
                            player.isLooping = true
                            dialogBinding.previewVideo.start()
                        }
                    }
                    StickerItem.Category.UNKNOWN -> {
                        dialogBinding.previewMessage.isVisible = true
                        dialogBinding.previewMessage.text = getString(R.string.preview_not_supported, item.mimeType)
                    }
                }
            }.onFailure { error ->
                dialogBinding.previewMessage.isVisible = true
                dialogBinding.previewMessage.text = error.message ?: getString(R.string.preview_error)
            }
        }

        dialog.setOnDismissListener {
            dialogBinding.previewVideo.stopPlayback()
            (dialogBinding.previewImage.drawable as? Animatable)?.stop()
        }
    }

    private fun saveSingleSticker(item: StickerItem) {
        if (!ensureLegacyWritePermission()) return
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val file = materializePreview(item)
                    saveToDownloads(item, file)
                }
            }
            result.onSuccess {
                Toast.makeText(this@MainActivity, R.string.saved_one, Toast.LENGTH_LONG).show()
            }.onFailure { error ->
                showError(getString(R.string.save_failed), error)
            }
        }
    }

    private fun saveAllStickers() {
        if (!ensureLegacyWritePermission()) return
        val items = adapter.currentList.toList()
        if (items.isEmpty()) return

        binding.loading.isVisible = true
        binding.saveAllButton.isEnabled = false
        binding.refreshButton.isEnabled = false
        binding.resultCount.text = getString(R.string.saving)

        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    var saved = 0
                    var skipped = 0
                    for (item in items) {
                        val file = materializePreview(item)
                        if (saveToDownloads(item, file)) saved++ else skipped++
                    }
                    saved to skipped
                }
            }

            result.onSuccess { (saved, skipped) ->
                val message = if (skipped > 0) {
                    "$saved stickers salvos; $skipped já existiam em Downloads/StickersDump"
                } else {
                    getString(R.string.saved_many, saved)
                }
                Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
            }.onFailure { error ->
                showError(getString(R.string.save_failed), error)
            }

            binding.loading.isVisible = false
            binding.refreshButton.isEnabled = fileService != null
            binding.saveAllButton.isEnabled = adapter.currentList.isNotEmpty()
            binding.resultCount.text = resources.getQuantityString(
                R.plurals.sticker_count,
                adapter.currentList.size,
                adapter.currentList.size
            )
        }
    }

    private fun ensureLegacyWritePermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return true
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
            return true
        }
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
            STORAGE_PERMISSION_REQUEST
        )
        Toast.makeText(this, "Conceda a permissão e toque em salvar novamente.", Toast.LENGTH_LONG).show()
        return false
    }

    /**
     * Salva com nome derivado do fingerprint visual. Isso impede que uma nova
     * codificação do mesmo sticker crie outro arquivo no dump.
     */
    private fun saveToDownloads(item: StickerItem, source: File): Boolean {
        val fingerprint = visualFingerprint(item, source).substringAfter(':').take(24)
        val extension = item.extension.ifBlank { "bin" }.lowercase(Locale.US)
        val displayName = "sticker_${fingerprint}.$extension"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = contentResolver
            val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/$DUMP_FOLDER"
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI

            resolver.query(
                collection,
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?",
                arrayOf(displayName, "$relativePath/"),
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) return false
            }

            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, item.mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(collection, values) ?: error("Falha criando arquivo em Downloads")
            try {
                resolver.openOutputStream(uri, "w")?.use { output ->
                    source.inputStream().use { input -> input.copyTo(output) }
                } ?: error("Falha abrindo o arquivo de destino")
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } catch (error: Throwable) {
                resolver.delete(uri, null, null)
                throw error
            }
            return true
        }

        @Suppress("DEPRECATION")
        val directory = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), DUMP_FOLDER)
        if (!directory.exists() && !directory.mkdirs()) error("Não foi possível criar Downloads/$DUMP_FOLDER")
        val destination = File(directory, displayName)
        if (destination.exists()) return false
        source.inputStream().use { input ->
            destination.outputStream().use { output -> input.copyTo(output) }
        }
        return true
    }

    private fun materializePreview(item: StickerItem): File {
        val service = fileService ?: error("Serviço Shizuku desconectado")
        val previewDir = File(cacheDir, "sticker_previews").apply { mkdirs() }
        val fileName = sha256(item.path + ":" + item.lastModified + ":" + item.dataOffset) + "." + item.extension
        val destination = File(previewDir, fileName)
        val expectedLength = (item.size - item.dataOffset).coerceAtLeast(0L)
        if (destination.isFile && destination.length() == expectedLength && expectedLength > 0L) return destination

        val descriptor = service.openContent(item.path, item.dataOffset)
        android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
            FileOutputStream(destination).use { output -> input.copyTo(output) }
        }
        return destination
    }

    private fun visualFingerprint(item: StickerItem, file: File): String {
        return when (item.category) {
            StickerItem.Category.IMAGE -> {
                val bitmap = decodeBitmapForFingerprint(file)
                "image:${hashBitmap(bitmap)}"
            }
            StickerItem.Category.VIDEO -> {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(file.absolutePath)
                    val frame = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        ?: error("Vídeo sem quadro")
                    val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION).orEmpty()
                    "video:${hashBitmap(frame)}:$duration"
                } finally {
                    retriever.release()
                }
            }
            StickerItem.Category.UNKNOWN -> "binary:${sha256File(file)}"
        }
    }

    private fun decodeBitmapForFingerprint(file: File): Bitmap {
        val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val width = info.size.width.coerceAtLeast(1)
                val height = info.size.height.coerceAtLeast(1)
                val scale = minOf(64f / width, 64f / height, 1f)
                decoder.setTargetSize(
                    (width * scale).toInt().coerceAtLeast(1),
                    (height * scale).toInt().coerceAtLeast(1)
                )
            }
        } else {
            BitmapFactory.decodeFile(file.absolutePath) ?: error("Imagem inválida")
        }
        return if (bitmap.width == 64 && bitmap.height == 64) bitmap else {
            Bitmap.createScaledBitmap(bitmap, 64, 64, true)
        }
    }

    private fun hashBitmap(bitmap: Bitmap): String {
        val normalized = if (bitmap.width == 64 && bitmap.height == 64) bitmap else {
            Bitmap.createScaledBitmap(bitmap, 64, 64, true)
        }
        val pixels = IntArray(64 * 64)
        normalized.getPixels(pixels, 0, 64, 0, 0, 64, 64)
        val buffer = ByteBuffer.allocate(pixels.size * Int.SIZE_BYTES)
        pixels.forEach(buffer::putInt)
        return MessageDigest.getInstance("SHA-256").digest(buffer.array()).toHex()
    }

    private fun sha256File(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toHex()
    }

    private fun ByteArray.toHex(): String = joinToString("") { byte ->
        (byte.toInt() and 0xFF).toString(16).padStart(2, '0')
    }

    private fun decodeDrawable(file: File, maxDimension: Int): android.graphics.drawable.Drawable {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(file)
            ImageDecoder.decodeDrawable(source) { decoder, info, _ ->
                val width = info.size.width
                val height = info.size.height
                val largest = max(width, height)
                if (largest > maxDimension) {
                    val ratio = maxDimension.toFloat() / largest
                    decoder.setTargetSize((width * ratio).toInt().coerceAtLeast(1), (height * ratio).toInt().coerceAtLeast(1))
                }
            }
        } else {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, options)
            options.inSampleSize = calculateInSampleSize(options.outWidth, options.outHeight, maxDimension)
            options.inJustDecodeBounds = false
            val bitmap = BitmapFactory.decodeFile(file.absolutePath, options) ?: error("Imagem inválida")
            android.graphics.drawable.BitmapDrawable(resources, bitmap)
        }
    }

    private fun createVideoThumbnail(file: File): Bitmap {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: error("Não foi possível gerar a miniatura do vídeo")
        } finally {
            retriever.release()
        }
    }

    private fun calculateInSampleSize(width: Int, height: Int, maxDimension: Int): Int {
        var sample = 1
        var currentWidth = width
        var currentHeight = height
        while (max(currentWidth, currentHeight) > maxDimension * 2) {
            sample *= 2
            currentWidth /= 2
            currentHeight /= 2
        }
        return sample
    }

    private fun openShizuku() {
        val launchIntent = packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
        if (launchIntent != null) {
            startActivity(launchIntent)
            return
        }
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$SHIZUKU_PACKAGE")))
        }.onFailure {
            startActivity(Intent(Settings.ACTION_APPLICATION_SETTINGS))
        }
    }

    private fun showError(title: String, error: Throwable) {
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(error.message ?: error.javaClass.simpleName)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .toHex()

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB")
        var value = bytes / 1024.0
        for (unit in units) {
            if (value < 1024 || unit == units.last()) return String.format(Locale.getDefault(), "%.1f %s", value, unit)
            value /= 1024.0
        }
        return "$bytes B"
    }

    private sealed interface PreviewResult {
        data class DrawableResult(val drawable: android.graphics.drawable.Drawable) : PreviewResult
        data class BitmapResult(val bitmap: Bitmap) : PreviewResult
        data object None : PreviewResult
    }

    companion object {
        private const val SHIZUKU_PERMISSION_REQUEST = 1001
        private const val STORAGE_PERMISSION_REQUEST = 1002
        private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
        private const val PAGE_SIZE = 100
        private const val DUMP_FOLDER = "StickersDump"
    }
}
