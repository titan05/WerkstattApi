package com.livewallpaper.video

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder

/**
 * Der eigentliche Live-Hintergrund: spielt die in der App ausgewaehlten Videos
 * ueber einen [VideoRenderer] auf der Wallpaper-Surface ab.
 *
 * Wiedergabe laeuft nur, solange der Hintergrund sichtbar ist - sobald eine App
 * im Vordergrund ist oder der Bildschirm aus geht, wird pausiert.
 */
class VideoWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = VideoEngine()

    inner class VideoEngine : Engine(),
        VideoRenderer.Listener,
        SharedPreferences.OnSharedPreferenceChangeListener {

        private val mainHandler = Handler(Looper.getMainLooper())
        private val powerManager by lazy { getSystemService(Context.POWER_SERVICE) as PowerManager }

        private var renderer: VideoRenderer? = null
        private var player: MediaPlayer? = null
        private var targetSurface: Surface? = null

        private var settings = Prefs.readSettings(this@VideoWallpaperService)
        private var playlist: List<VideoItem> = emptyList()
        private var order: List<Int> = emptyList()
        private var orderIndex = 0

        private var isVisible = false
        private var isPrepared = false
        /** Standbild statt Wiedergabe (Energiesparmodus). */
        private var stillOnly = false
        /** Im Standbild-Modus: erstes Bild steht bereits. */
        private var stillFrameShown = false
        private var failures = 0

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            setTouchEventsEnabled(false)
            playlist = Prefs.readVideos(this@VideoWallpaperService)
            buildOrder()
            Prefs.get(this@VideoWallpaperService).registerOnSharedPreferenceChangeListener(this)
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            renderer = VideoRenderer(this).also {
                it.applySettings(settings)
                it.start(holder.surface)
            }
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            renderer?.setSurfaceSize(width, height)
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            super.onSurfaceDestroyed(holder)
            releasePlayer()
            targetSurface = null
            renderer?.release()
            renderer = null
        }

        override fun onVideoSurfaceReady(source: VideoRenderer, surface: Surface) {
            // Kommt vom GL-Thread - der MediaPlayer wird auf dem Hauptthread bedient.
            mainHandler.post {
                // Nach einem schnellen Wechsel der Surface (z.B. Drehen) kann die
                // Meldung von einem bereits verworfenen Renderer stammen.
                if (source !== renderer) return@post
                targetSurface = surface
                openCurrent()
            }
        }

        override fun onVisibilityChanged(visible: Boolean) {
            isVisible = visible
            if (visible) {
                stillOnly = settings.batterySaver && powerManager.isPowerSaveMode
                resumePlayback()
            } else {
                pausePlayback()
            }
        }

        override fun onOffsetsChanged(
            xOffset: Float,
            yOffset: Float,
            xOffsetStep: Float,
            yOffsetStep: Float,
            xPixelOffset: Int,
            yPixelOffset: Int
        ) {
            renderer?.setXOffset(xOffset)
        }

        override fun onDestroy() {
            Prefs.get(this@VideoWallpaperService)
                .unregisterOnSharedPreferenceChangeListener(this)
            mainHandler.removeCallbacksAndMessages(null)
            releasePlayer()
            renderer?.release()
            renderer = null
            super.onDestroy()
        }

        override fun onSharedPreferenceChanged(prefs: SharedPreferences?, key: String?) {
            val newSettings = Prefs.readSettings(this@VideoWallpaperService)
            val newPlaylist = Prefs.readVideos(this@VideoWallpaperService)
            val playlistChanged = newPlaylist.map { it.id } != playlist.map { it.id }
            val orderChanged = newSettings.shuffle != settings.shuffle

            settings = newSettings
            playlist = newPlaylist
            renderer?.applySettings(newSettings)

            when {
                playlistChanged -> {
                    buildOrder()
                    failures = 0
                    if (playlist.isEmpty()) renderer?.clearFrame()
                    openCurrent()
                }

                orderChanged -> buildOrder()

                else -> {
                    applyVolume()
                    applySpeed()
                }
            }
        }

        // --- Wiedergabe ------------------------------------------------------

        private fun buildOrder() {
            val indices = playlist.indices.toList()
            order = if (settings.shuffle) indices.shuffled() else indices
            orderIndex = 0
        }

        private fun currentItem(): VideoItem? {
            if (order.isEmpty()) return null
            val index = order.getOrNull(orderIndex % order.size) ?: return null
            return playlist.getOrNull(index)
        }

        private fun openCurrent() {
            releasePlayer()
            val surface = targetSurface ?: return
            val item = currentItem() ?: return
            val file = Storage.videoFile(this@VideoWallpaperService, item)
            if (!file.exists()) {
                onPlaybackFailed()
                return
            }

            if (item.aspect > 0f) renderer?.setVideoAspect(item.aspect)

            val mediaPlayer = MediaPlayer()
            player = mediaPlayer
            isPrepared = false
            stillFrameShown = false
            try {
                mediaPlayer.setDataSource(file.absolutePath)
                mediaPlayer.setSurface(surface)
                mediaPlayer.isLooping = order.size <= 1
                mediaPlayer.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build()
                )
                mediaPlayer.setOnVideoSizeChangedListener { _, width, height ->
                    if (item.aspect <= 0f && width > 0 && height > 0) {
                        renderer?.setVideoAspect(width.toFloat() / height.toFloat())
                    }
                }
                mediaPlayer.setOnInfoListener { mp, what, _ ->
                    if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START && stillOnly) {
                        // Erstes Bild steht - im Energiesparmodus reicht das Standbild.
                        stillFrameShown = true
                        runCatching { mp.pause() }
                    }
                    false
                }
                mediaPlayer.setOnPreparedListener { mp ->
                    isPrepared = true
                    failures = 0
                    applyVolume()
                    if (isVisible) {
                        startPlayer(mp)
                    } else {
                        // Erstes Bild zeichnen, damit beim Einblenden nichts schwarz bleibt.
                        runCatching { mp.seekTo(0) }
                    }
                }
                mediaPlayer.setOnCompletionListener {
                    if (order.size > 1) {
                        orderIndex = (orderIndex + 1) % order.size
                        // Kein clearFrame: das letzte Bild bleibt stehen, bis das
                        // naechste Video sein erstes Bild liefert - so flackert nichts.
                        openCurrent()
                    }
                }
                mediaPlayer.setOnErrorListener { _, what, extra ->
                    Log.w(TAG, "MediaPlayer-Fehler $what/$extra bei ${item.name}")
                    mainHandler.post { onPlaybackFailed() }
                    true
                }
                mediaPlayer.prepareAsync()
            } catch (e: Exception) {
                Log.w(TAG, "Video konnte nicht geoeffnet werden: ${item.name}", e)
                onPlaybackFailed()
            }
        }

        private fun onPlaybackFailed() {
            releasePlayer()
            failures++
            // Nur so oft weiterschalten, wie es Videos gibt - sonst laeuft es endlos.
            if (order.isNotEmpty() && failures <= order.size) {
                orderIndex = (orderIndex + 1) % order.size
                openCurrent()
            }
        }

        private fun startPlayer(mediaPlayer: MediaPlayer) {
            applySpeedTo(mediaPlayer)
            runCatching { mediaPlayer.start() }
                .onFailure { Log.w(TAG, "start() fehlgeschlagen", it) }
        }

        private fun resumePlayback() {
            val mediaPlayer = player
            if (mediaPlayer == null) {
                openCurrent()
                return
            }
            if (!isPrepared) return
            if (stillOnly) {
                // Standbild: nur so lange anlaufen lassen, bis das erste Bild steht.
                if (!stillFrameShown && !mediaPlayer.isPlaying) startPlayer(mediaPlayer)
                return
            }
            if (!mediaPlayer.isPlaying) startPlayer(mediaPlayer)
        }

        private fun pausePlayback() {
            val mediaPlayer = player ?: return
            if (!isPrepared) return
            runCatching { if (mediaPlayer.isPlaying) mediaPlayer.pause() }
        }

        private fun applyVolume() {
            val mediaPlayer = player ?: return
            if (!isPrepared) return
            val volume = if (settings.soundEnabled) settings.volume / 100f else 0f
            runCatching { mediaPlayer.setVolume(volume, volume) }
        }

        private fun applySpeed() {
            val mediaPlayer = player ?: return
            if (!isPrepared || !mediaPlayer.isPlaying) return
            applySpeedTo(mediaPlayer)
        }

        private fun applySpeedTo(mediaPlayer: MediaPlayer) {
            runCatching {
                val params = PlaybackParams().setSpeed(settings.speed)
                mediaPlayer.playbackParams = params
            }.onFailure { Log.w(TAG, "Geschwindigkeit nicht unterstuetzt", it) }
        }

        private fun releasePlayer() {
            val mediaPlayer = player ?: return
            player = null
            isPrepared = false
            runCatching {
                mediaPlayer.setOnPreparedListener(null)
                mediaPlayer.setOnCompletionListener(null)
                mediaPlayer.setOnErrorListener(null)
                mediaPlayer.setOnInfoListener(null)
                mediaPlayer.setOnVideoSizeChangedListener(null)
                mediaPlayer.reset()
            }
            runCatching { mediaPlayer.release() }
        }
    }

    private companion object {
        const val TAG = "VideoWallpaper"
    }
}
