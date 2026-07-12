package com.jaowzin.stickers.ui

import android.content.ComponentName
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
import android.os.IBinder
import android.provider.Settings
import android.widget.MediaController
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
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
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

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

        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionResultListener)

        updateShizukuStatus()
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
            !alive -> "Shizuku não está ativo. Abra o Shizuku e inicie pelo Depuração sem fio."
            !granted -> "Shizuku ativo. Conceda permissão para ler o cache do TikTok."
            fileService == null -> "Permissão concedida. Conectando ao leitor…"
            else -> "Pronto para ler os stickers do cache."
        }
        binding.permissionButton.isVisible = !granted
        binding.openShizukuButton.isVisible = !alive
        binding.refreshButton.isEnabled = fileService != null
    }

    private fun refreshItems() {
        val service = fileService ?: run {
            requestShizukuAccess()
            return
        }

        binding.loading.isVisible = true
        binding.emptyState.isVisible = false
        binding.refreshButton.isEnabled = false

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
                    items
                }
            }.onSuccess { items ->
                adapter.submitList(items)
                binding.resultCount.text = resources.getQuantityString(R.plurals.sticker_count, items.size, items.size)
                binding.emptyState.isVisible = items.isEmpty()
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
        .joinToString("") { "%02x".format(Locale.US, it) }

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
        private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
        private const val PAGE_SIZE = 100
    }
}
