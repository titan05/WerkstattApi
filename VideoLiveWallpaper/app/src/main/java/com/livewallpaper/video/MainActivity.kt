package com.livewallpaper.video

import android.app.WallpaperManager
import android.content.ActivityNotFoundException
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.IntentCompat
import androidx.core.content.edit
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.livewallpaper.video.databinding.ActivityMainBinding
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Einstellungen des Live-Hintergrunds: Videos verwalten, Darstellung und
 * Wiedergabe konfigurieren, Hintergrund aktivieren.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: VideoAdapter

    private val importExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val speedFormat = DecimalFormat("0.##", DecimalFormatSymbols(Locale.GERMANY))

    private var videos = mutableListOf<VideoItem>()
    private var settings = Prefs.defaults()

    /** Verhindert, dass das Befuellen der Bedienelemente sofort wieder speichert. */
    private var updatingUi = false

    /** Vom Hauptthread gesetzt, vom Ladethread gelesen. */
    @Volatile
    private var downloadCancelled = false

    /** Geteiltes Video wurde schon uebernommen - nicht nach Drehen erneut. */
    private var shareHandled = false

    private val pickVideos = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> if (uris.isNotEmpty()) importVideos(uris) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        settings = Prefs.readSettings(this)
        videos = Prefs.readVideos(this).toMutableList()

        applyWindowInsets()
        setUpList()
        bindSettings()
        setUpListeners()
        updateVideoViews()

        // Reste frueherer Importe aufraeumen (z.B. nach einem Abbruch).
        val known = videos.toList()
        importExecutor.execute { Storage.cleanUp(this, known) }

        shareHandled = savedInstanceState?.getBoolean(STATE_SHARE_HANDLED) == true
        if (!shareHandled) handleShareIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_SHARE_HANDLED, shareHandled)
    }

    /** Nimmt Videos entgegen, die aus einer anderen App geteilt wurden. */
    private fun handleShareIntent(intent: Intent?) {
        if (intent == null) return
        val uris = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            )

            Intent.ACTION_SEND_MULTIPLE -> IntentCompat
                .getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                .orEmpty()

            else -> return
        }

        shareHandled = true
        if (uris.isEmpty()) {
            snack(getString(R.string.shared_no_video))
        } else {
            importVideos(uris)
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    override fun onDestroy() {
        importExecutor.shutdownNow()
        mainHandler.removeCallbacksAndMessages(null)
        adapter.shutdown()
        super.onDestroy()
    }

    // --- Aufbau ---------------------------------------------------------------

    /** Sorgt dafuer, dass der Inhalt nicht unter der Navigationsleiste endet. */
    private fun applyWindowInsets() {
        val basePadding = binding.scrollView.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.scrollView) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(bottom = basePadding + bars.bottom)
            insets
        }
    }

    private fun setUpList() {
        adapter = VideoAdapter(this) { item -> removeVideo(item) }
        binding.videoList.layoutManager = LinearLayoutManager(this)
        binding.videoList.adapter = adapter
        adapter.submit(videos)

        val touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                adapter.move(viewHolder.bindingAdapterPosition, target.bindingAdapterPosition)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

            override fun clearView(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder
            ) {
                super.clearView(recyclerView, viewHolder)
                videos = adapter.currentItems().toMutableList()
                Prefs.writeVideos(this@MainActivity, videos)
            }
        })
        touchHelper.attachToRecyclerView(binding.videoList)
    }

    private fun bindSettings() {
        updatingUi = true
        binding.scaleGroup.check(
            when (settings.scaleMode) {
                ScaleMode.CROP -> R.id.scaleCrop
                ScaleMode.FIT -> R.id.scaleFit
                ScaleMode.STRETCH -> R.id.scaleStretch
            }
        )
        binding.dimSlider.value = settings.dim.toFloat()
        binding.volumeSlider.value = settings.volume.toFloat()
        binding.speedSlider.value = settings.speed
        binding.parallaxSwitch.isChecked = settings.parallax
        binding.soundSwitch.isChecked = settings.soundEnabled
        binding.shuffleSwitch.isChecked = settings.shuffle
        binding.batterySwitch.isChecked = settings.batterySaver
        binding.freezeSlider.value = settings.freezeAfterSeconds.toFloat()
        binding.stillBelowSlider.value = settings.stillBelowPercent.toFloat()
        binding.fpsGroup.check(
            when (settings.maxFps) {
                24 -> R.id.fps24
                30 -> R.id.fps30
                else -> R.id.fpsFull
            }
        )
        updatingUi = false

        updateValueLabels()
    }

    private fun setUpListeners() {
        binding.addButton.setOnClickListener {
            pickVideos.launch(arrayOf("video/*"))
        }

        binding.linkButton.setOnClickListener { showUrlDialog() }

        binding.progressCancel.setOnClickListener {
            downloadCancelled = true
            binding.progressCancel.isEnabled = false
        }

        binding.setWallpaperButton.setOnClickListener {
            if (videos.isEmpty()) {
                snack(getString(R.string.need_video_first))
            } else {
                openWallpaperChooser()
            }
        }

        binding.scaleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked || updatingUi) return@addOnButtonCheckedListener
            val mode = when (checkedId) {
                R.id.scaleFit -> ScaleMode.FIT
                R.id.scaleStretch -> ScaleMode.STRETCH
                else -> ScaleMode.CROP
            }
            settings = settings.copy(scaleMode = mode)
            Prefs.get(this).edit { putString(Prefs.KEY_SCALE, mode.name) }
        }

        binding.dimSlider.addOnChangeListener { _, value, _ ->
            settings = settings.copy(dim = value.toInt())
            updateValueLabels()
            if (!updatingUi) Prefs.get(this).edit { putInt(Prefs.KEY_DIM, value.toInt()) }
        }

        binding.volumeSlider.addOnChangeListener { _, value, _ ->
            settings = settings.copy(volume = value.toInt())
            updateValueLabels()
            if (!updatingUi) Prefs.get(this).edit { putInt(Prefs.KEY_VOLUME, value.toInt()) }
        }

        binding.speedSlider.addOnChangeListener { _, value, _ ->
            settings = settings.copy(speed = value)
            updateValueLabels()
            if (!updatingUi) Prefs.get(this).edit { putFloat(Prefs.KEY_SPEED, value) }
        }

        binding.parallaxSwitch.setOnCheckedChangeListener { _, checked ->
            settings = settings.copy(parallax = checked)
            if (!updatingUi) Prefs.get(this).edit { putBoolean(Prefs.KEY_PARALLAX, checked) }
        }

        binding.soundSwitch.setOnCheckedChangeListener { _, checked ->
            settings = settings.copy(soundEnabled = checked)
            updateValueLabels()
            if (!updatingUi) Prefs.get(this).edit { putBoolean(Prefs.KEY_SOUND, checked) }
        }

        binding.shuffleSwitch.setOnCheckedChangeListener { _, checked ->
            settings = settings.copy(shuffle = checked)
            if (!updatingUi) Prefs.get(this).edit { putBoolean(Prefs.KEY_SHUFFLE, checked) }
        }

        binding.batterySwitch.setOnCheckedChangeListener { _, checked ->
            settings = settings.copy(batterySaver = checked)
            if (!updatingUi) Prefs.get(this).edit { putBoolean(Prefs.KEY_BATTERY, checked) }
        }

        binding.freezeSlider.addOnChangeListener { _, value, _ ->
            settings = settings.copy(freezeAfterSeconds = value.toInt())
            updateValueLabels()
            if (!updatingUi) Prefs.get(this).edit { putInt(Prefs.KEY_FREEZE, value.toInt()) }
        }

        binding.stillBelowSlider.addOnChangeListener { _, value, _ ->
            settings = settings.copy(stillBelowPercent = value.toInt())
            updateValueLabels()
            if (!updatingUi) Prefs.get(this).edit { putInt(Prefs.KEY_STILL_BELOW, value.toInt()) }
        }

        binding.fpsGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked || updatingUi) return@addOnButtonCheckedListener
            val fps = when (checkedId) {
                R.id.fps24 -> 24
                R.id.fps30 -> 30
                else -> 0
            }
            settings = settings.copy(maxFps = fps)
            Prefs.get(this).edit { putInt(Prefs.KEY_MAX_FPS, fps) }
        }
    }

    // --- Videos ---------------------------------------------------------------

    private fun importVideos(uris: List<Uri>) {
        showProgress(R.string.importing, cancellable = false)
        importExecutor.execute {
            val imported = mutableListOf<VideoItem>()
            var failed = 0
            uris.forEach { uri ->
                val item = VideoImporter.import(this, uri)
                if (item != null) imported.add(item) else failed++
            }
            val failures = failed
            mainHandler.post {
                hideProgress()
                if (imported.isNotEmpty()) {
                    videos.addAll(imported)
                    Prefs.writeVideos(this, videos)
                    adapter.submit(videos)
                    updateVideoViews()
                    snack(resources.getQuantityString(R.plurals.import_done, imported.size, imported.size))
                }
                if (failures > 0) {
                    snack(resources.getQuantityString(R.plurals.import_failed, failures, failures))
                }
            }
        }
    }

    // --- Von einer Adresse laden ---------------------------------------------

    private fun showUrlDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_url, null)
        val input = view.findViewById<TextInputEditText>(R.id.urlInput)
        clipboardUrl()?.let { input.setText(it) }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.link_dialog_title)
            .setView(view)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.load) { _, _ ->
                handleUrl(input.text?.toString().orEmpty())
            }
            .show()
    }

    private fun handleUrl(raw: String) {
        when (val check = VideoUrlRules.check(raw)) {
            is UrlCheck.Ok -> downloadFromUrl(check.url)
            is UrlCheck.Streaming -> showStreamingInfo(check.service)
            UrlCheck.Cleartext -> snack(getString(R.string.url_cleartext))
            UrlCheck.Empty, UrlCheck.Invalid -> snack(getString(R.string.url_invalid))
        }
    }

    /** Erklaert, warum ein Portal-Link nicht funktioniert, und was stattdessen geht. */
    private fun showStreamingInfo(service: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.streaming_title, service))
            .setMessage(getString(R.string.streaming_message, service, ownContentHint(service)))
            .setPositiveButton(R.string.understood, null)
            .show()
    }

    /** Wie man an die eigenen Videos des jeweiligen Portals kommt. */
    private fun ownContentHint(service: String): String = getString(
        when (service) {
            "YouTube" -> R.string.own_hint_youtube
            "Instagram" -> R.string.own_hint_instagram
            "TikTok" -> R.string.own_hint_tiktok
            else -> R.string.own_hint_generic
        }
    )

    private fun downloadFromUrl(url: String) {
        downloadCancelled = false
        showProgress(R.string.downloading, cancellable = true)
        importExecutor.execute {
            val result = VideoImporter.importFromUrl(
                context = this,
                url = url,
                onProgress = { loaded, total -> mainHandler.post { updateProgress(loaded, total) } },
                isCancelled = { downloadCancelled }
            )
            mainHandler.post {
                hideProgress()
                when (result) {
                    is UrlImportResult.Success -> {
                        videos.add(result.item)
                        Prefs.writeVideos(this, videos)
                        adapter.submit(videos)
                        updateVideoViews()
                        snack(resources.getQuantityString(R.plurals.import_done, 1, 1))
                    }

                    is UrlImportResult.Failure -> snack(getString(messageFor(result.error)))
                }
            }
        }
    }

    @StringRes
    private fun messageFor(error: UrlImportResult.Error): Int = when (error) {
        UrlImportResult.Error.NETWORK -> R.string.download_failed_network
        UrlImportResult.Error.FORBIDDEN -> R.string.download_failed_forbidden
        UrlImportResult.Error.NOT_FOUND -> R.string.download_failed_not_found
        UrlImportResult.Error.NOT_A_VIDEO -> R.string.download_failed_not_video
        UrlImportResult.Error.TOO_LARGE -> R.string.download_failed_too_large
        UrlImportResult.Error.CANCELLED -> R.string.download_cancelled
    }

    private fun clipboardUrl(): String? {
        val manager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
        val clip = manager.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        val text = clip.getItemAt(0)?.coerceToText(this)?.toString()?.trim() ?: return null
        return text.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }
    }

    private fun showProgress(@StringRes labelRes: Int, cancellable: Boolean) {
        binding.progressLabel.setText(labelRes)
        binding.progressDetail.text = ""
        binding.progressDetail.visibility = View.GONE
        binding.progressCancel.isEnabled = true
        binding.progressCancel.visibility = if (cancellable) View.VISIBLE else View.GONE
        binding.progressOverlay.visibility = View.VISIBLE
    }

    private fun hideProgress() {
        binding.progressOverlay.visibility = View.GONE
    }

    private fun updateProgress(loadedBytes: Long, totalBytes: Long) {
        binding.progressDetail.visibility = View.VISIBLE
        binding.progressDetail.text = if (totalBytes > 0) {
            getString(R.string.download_progress, megabytes(loadedBytes), megabytes(totalBytes))
        } else {
            getString(R.string.download_progress_unknown, megabytes(loadedBytes))
        }
    }

    private fun megabytes(bytes: Long): String =
        String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024.0))

    private fun removeVideo(item: VideoItem) {
        videos.remove(item)
        Prefs.writeVideos(this, videos)
        adapter.submit(videos)
        updateVideoViews()
        importExecutor.execute { Storage.delete(this, item) }
        snack(getString(R.string.removed_video, item.name))
    }

    private fun updateVideoViews() {
        binding.emptyText.visibility = if (videos.isEmpty()) View.VISIBLE else View.GONE
        binding.videoHint.visibility = if (videos.size > 1) View.VISIBLE else View.GONE
        binding.shuffleRow.visibility = if (videos.size > 1) View.VISIBLE else View.GONE
        updateStatus()
    }

    private fun updateValueLabels() {
        binding.dimValue.text = getString(R.string.percent_value, settings.dim)
        binding.volumeValue.text = getString(R.string.percent_value, settings.volume)
        binding.speedValue.text =
            getString(R.string.speed_value, speedFormat.format(settings.speed.toDouble()))
        binding.freezeValue.text = if (settings.freezeAfterSeconds <= 0) {
            getString(R.string.value_off)
        } else {
            getString(R.string.seconds_value, settings.freezeAfterSeconds)
        }
        binding.stillBelowValue.text = if (settings.stillBelowPercent <= 0) {
            getString(R.string.value_off)
        } else {
            getString(R.string.percent_value, settings.stillBelowPercent)
        }
        val soundOn = binding.soundSwitch.isChecked
        binding.volumeRow.visibility = if (soundOn) View.VISIBLE else View.GONE
        binding.volumeSlider.visibility = if (soundOn) View.VISIBLE else View.GONE
    }

    private fun updateStatus() {
        val info = WallpaperManager.getInstance(this).wallpaperInfo
        val active = info != null &&
            info.packageName == packageName &&
            info.serviceName == VideoWallpaperService::class.java.name
        binding.statusText.text = when {
            videos.isEmpty() -> getString(R.string.status_no_video)
            active -> getString(R.string.status_active)
            else -> getString(R.string.status_inactive)
        }
    }

    private fun openWallpaperChooser() {
        val component = ComponentName(this, VideoWallpaperService::class.java)
        val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
            .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, component)
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            try {
                startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))
            } catch (e2: ActivityNotFoundException) {
                snack(getString(R.string.set_wallpaper_failed))
            }
        }
    }

    private fun snack(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
    }

    private companion object {
        const val STATE_SHARE_HANDLED = "share_handled"
    }
}
