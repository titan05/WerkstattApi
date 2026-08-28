package com.livewallpaper.video

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Ein importiertes Video aus dem App-Speicher. */
data class VideoItem(
    val id: String,
    val name: String,
    val fileName: String,
    val durationMs: Long,
    /** Seitenverhaeltnis (Breite/Hoehe) inklusive Rotation, 0 wenn unbekannt. */
    val aspect: Float
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("file", fileName)
        put("duration", durationMs)
        put("aspect", aspect.toDouble())
    }

    companion object {
        fun fromJson(o: JSONObject): VideoItem = VideoItem(
            id = o.optString("id"),
            name = o.optString("name"),
            fileName = o.optString("file"),
            durationMs = o.optLong("duration"),
            aspect = o.optDouble("aspect", 0.0).toFloat()
        )
    }
}

enum class ScaleMode {
    /** Bild fuellt den Screen komplett, Ueberstand wird abgeschnitten. */
    CROP,

    /** Ganzes Video sichtbar, schwarze Balken moeglich. */
    FIT,

    /** Auf Screen-Format verzerrt. */
    STRETCH;

    companion object {
        fun fromName(value: String?): ScaleMode =
            entries.firstOrNull { it.name == value } ?: CROP
    }
}

/** Alle Einstellungen des Live-Hintergrunds. */
data class Settings(
    val scaleMode: ScaleMode,
    val soundEnabled: Boolean,
    val volume: Int,
    val dim: Int,
    val parallax: Boolean,
    val speed: Float,
    val shuffle: Boolean,
    val batterySaver: Boolean,
    /** Nach so vielen Sekunden Sichtbarkeit einfrieren, 0 = nie. */
    val freezeAfterSeconds: Int,
    /** Obergrenze fuer gezeichnete Bilder pro Sekunde, 0 = unbegrenzt. */
    val maxFps: Int,
    /** Unter diesem Akkustand nur ein Standbild zeigen, 0 = aus. */
    val stillBelowPercent: Int
)

object Prefs {
    private const val FILE = "wallpaper_settings"

    const val KEY_VIDEOS = "videos"
    const val KEY_SCALE = "scale_mode"
    const val KEY_SOUND = "sound_enabled"
    const val KEY_VOLUME = "volume"
    const val KEY_DIM = "dim"
    const val KEY_PARALLAX = "parallax"
    const val KEY_SPEED = "speed"
    const val KEY_SHUFFLE = "shuffle"
    const val KEY_BATTERY = "battery_saver"
    const val KEY_FREEZE = "freeze_after_seconds"
    const val KEY_MAX_FPS = "max_fps"
    const val KEY_STILL_BELOW = "still_below_percent"

    fun get(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Werkseinstellungen - bewusst schon akkuschonend. */
    fun defaults(): Settings = Settings(
        scaleMode = ScaleMode.CROP,
        soundEnabled = false,
        volume = 60,
        dim = 0,
        parallax = true,
        speed = 1f,
        shuffle = false,
        batterySaver = true,
        freezeAfterSeconds = 15,
        maxFps = 30,
        stillBelowPercent = 15
    )

    fun readSettings(context: Context): Settings {
        val p = get(context)
        val d = defaults()
        return Settings(
            scaleMode = ScaleMode.fromName(p.getString(KEY_SCALE, d.scaleMode.name)),
            soundEnabled = p.getBoolean(KEY_SOUND, d.soundEnabled),
            volume = p.getInt(KEY_VOLUME, d.volume).coerceIn(0, 100),
            dim = p.getInt(KEY_DIM, d.dim).coerceIn(0, 80),
            parallax = p.getBoolean(KEY_PARALLAX, d.parallax),
            speed = p.getFloat(KEY_SPEED, d.speed).coerceIn(0.25f, 2f),
            shuffle = p.getBoolean(KEY_SHUFFLE, d.shuffle),
            batterySaver = p.getBoolean(KEY_BATTERY, d.batterySaver),
            freezeAfterSeconds = p.getInt(KEY_FREEZE, d.freezeAfterSeconds).coerceIn(0, 60),
            maxFps = p.getInt(KEY_MAX_FPS, d.maxFps).coerceIn(0, 120),
            stillBelowPercent = p.getInt(KEY_STILL_BELOW, d.stillBelowPercent).coerceIn(0, 50)
        )
    }

    fun readVideos(context: Context): List<VideoItem> {
        val raw = get(context).getString(KEY_VIDEOS, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                array.optJSONObject(i)?.let { VideoItem.fromJson(it) }
            }.filter { it.id.isNotEmpty() && it.fileName.isNotEmpty() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun writeVideos(context: Context, videos: List<VideoItem>) {
        val array = JSONArray()
        videos.forEach { array.put(it.toJson()) }
        get(context).edit().putString(KEY_VIDEOS, array.toString()).apply()
    }
}

/** Ablage der importierten Videos im privaten App-Speicher. */
object Storage {
    fun videoDir(context: Context): File =
        File(context.filesDir, "videos").apply { if (!exists()) mkdirs() }

    fun videoFile(context: Context, item: VideoItem): File =
        File(videoDir(context), item.fileName)

    fun thumbFile(context: Context, item: VideoItem): File =
        File(videoDir(context), item.id + ".jpg")

    fun delete(context: Context, item: VideoItem) {
        videoFile(context, item).delete()
        thumbFile(context, item).delete()
    }

    /** Entfernt Dateien, die zu keinem Eintrag mehr gehoeren. */
    fun cleanUp(context: Context, videos: List<VideoItem>) {
        val keep = HashSet<String>()
        videos.forEach {
            keep.add(it.fileName)
            keep.add(it.id + ".jpg")
        }
        videoDir(context).listFiles()?.forEach { file ->
            if (file.name !in keep) file.delete()
        }
    }
}
