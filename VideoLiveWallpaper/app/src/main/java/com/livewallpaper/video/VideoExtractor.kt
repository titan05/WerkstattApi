package com.livewallpaper.video

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

sealed class ExtractionResult {
    data class Found(val videoUrl: String, val title: String) : ExtractionResult()
    data class Failed(val reason: Reason) : ExtractionResult()

    enum class Reason { NETWORK, PRIVATE, LIVE, NOT_FOUND, NO_VIDEO }
}

object VideoExtractor {

    private const val TAG = "VideoExtractor"
    private const val CONNECT_TIMEOUT = 15_000
    private const val READ_TIMEOUT = 20_000

    fun extract(service: String, pageUrl: String): ExtractionResult = when (service) {
        "YouTube" -> extractYouTube(pageUrl)
        "Instagram" -> extractInstagram(pageUrl)
        else -> ExtractionResult.Failed(ExtractionResult.Reason.NO_VIDEO)
    }

    // --- YouTube -------------------------------------------------------------

    private fun extractYouTube(pageUrl: String): ExtractionResult {
        val videoId = parseYouTubeId(pageUrl)
            ?: return ExtractionResult.Failed(ExtractionResult.Reason.NOT_FOUND)

        return try {
            youtubeInnertube(videoId)
        } catch (e: Exception) {
            Log.w(TAG, "Innertube fehlgeschlagen, versuche Seitenquelltext", e)
            try {
                youtubePageScrape(videoId)
            } catch (e2: Exception) {
                Log.w(TAG, "Seitenquelltext fehlgeschlagen", e2)
                ExtractionResult.Failed(ExtractionResult.Reason.NETWORK)
            }
        }
    }

    private fun youtubeInnertube(videoId: String): ExtractionResult {
        val conn = (URL("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
            .openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = CONNECT_TIMEOUT
            readTimeout = READ_TIMEOUT
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty(
                "User-Agent",
                "com.google.android.youtube/19.09.37 (Linux; U; Android 14) gzip"
            )
            setRequestProperty("X-YouTube-Client-Name", "3")
            setRequestProperty("X-YouTube-Client-Version", "19.09.37")
        }

        val body = JSONObject().apply {
            put("videoId", videoId)
            put("context", JSONObject().apply {
                put("client", JSONObject().apply {
                    put("clientName", "ANDROID")
                    put("clientVersion", "19.09.37")
                    put("androidSdkVersion", 30)
                    put("hl", "de")
                })
            })
        }

        try {
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            if (conn.responseCode != 200) {
                return ExtractionResult.Failed(ExtractionResult.Reason.NETWORK)
            }
            val response = conn.inputStream.bufferedReader().readText()
            return parsePlayerResponse(JSONObject(response))
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    private fun youtubePageScrape(videoId: String): ExtractionResult {
        val conn = (URL("https://www.youtube.com/watch?v=$videoId")
            .openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT
            readTimeout = READ_TIMEOUT
            setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
            )
            setRequestProperty("Accept-Language", "de-DE,de;q=0.9")
            setRequestProperty("Cookie", "CONSENT=YES+")
        }

        try {
            val html = conn.inputStream.bufferedReader().readText()
            val json = extractPlayerJson(html)
                ?: return ExtractionResult.Failed(ExtractionResult.Reason.NO_VIDEO)
            return parsePlayerResponse(json)
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    private fun extractPlayerJson(html: String): JSONObject? {
        for (marker in listOf("var ytInitialPlayerResponse = ", "ytInitialPlayerResponse = ")) {
            val start = html.indexOf(marker)
            if (start < 0) continue
            val jsonStart = start + marker.length
            val jsonEnd = findJsonEnd(html, jsonStart) ?: continue
            return runCatching { JSONObject(html.substring(jsonStart, jsonEnd)) }.getOrNull()
        }
        return null
    }

    private fun findJsonEnd(text: String, from: Int): Int? {
        var depth = 0
        var inString = false
        var escape = false
        for (i in from until text.length) {
            val c = text[i]
            if (escape) { escape = false; continue }
            if (c == '\\') { escape = true; continue }
            if (c == '"') { inString = !inString; continue }
            if (inString) continue
            when (c) {
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) return i + 1 }
            }
        }
        return null
    }

    private fun parsePlayerResponse(json: JSONObject): ExtractionResult {
        val playability = json.optJSONObject("playabilityStatus")
        when (playability?.optString("status", "")) {
            "LOGIN_REQUIRED" -> return ExtractionResult.Failed(ExtractionResult.Reason.PRIVATE)
            "LIVE_STREAM_OFFLINE",
            "LIVE_STREAM" -> return ExtractionResult.Failed(ExtractionResult.Reason.LIVE)
            "OK" -> { /* weiter */ }
            "UNPLAYABLE" -> return ExtractionResult.Failed(ExtractionResult.Reason.PRIVATE)
            else -> return ExtractionResult.Failed(ExtractionResult.Reason.NO_VIDEO)
        }

        val title = json.optJSONObject("videoDetails")
            ?.optString("title", "YouTube Video") ?: "YouTube Video"

        val isLive = json.optJSONObject("videoDetails")
            ?.optBoolean("isLiveContent", false) == true &&
            json.optJSONObject("videoDetails")
                ?.optBoolean("isLive", false) == true
        if (isLive) return ExtractionResult.Failed(ExtractionResult.Reason.LIVE)

        val streamingData = json.optJSONObject("streamingData")
            ?: return ExtractionResult.Failed(ExtractionResult.Reason.NO_VIDEO)

        val combined = pickBestMp4(streamingData.optJSONArray("formats"), 720)
        if (combined != null) return ExtractionResult.Found(combined, title)

        val adaptive = pickBestMp4(streamingData.optJSONArray("adaptiveFormats"), 720)
        if (adaptive != null) return ExtractionResult.Found(adaptive, title)

        val anyCombined = pickBestMp4(streamingData.optJSONArray("formats"), Int.MAX_VALUE)
        if (anyCombined != null) return ExtractionResult.Found(anyCombined, title)

        val anyAdaptive = pickBestMp4(streamingData.optJSONArray("adaptiveFormats"), Int.MAX_VALUE)
        if (anyAdaptive != null) return ExtractionResult.Found(anyAdaptive, title)

        return ExtractionResult.Failed(ExtractionResult.Reason.NO_VIDEO)
    }

    private fun pickBestMp4(formats: JSONArray?, maxHeight: Int): String? {
        if (formats == null) return null
        var bestUrl: String? = null
        var bestHeight = -1

        for (i in 0 until formats.length()) {
            val fmt = formats.optJSONObject(i) ?: continue
            val url = fmt.optString("url", "")
            if (url.isEmpty()) continue
            val mime = fmt.optString("mimeType", "")
            if (!mime.startsWith("video/mp4")) continue
            val h = fmt.optInt("height", 0)
            if (h in 1..maxHeight && h > bestHeight) {
                bestHeight = h
                bestUrl = url
            }
        }
        return bestUrl
    }

    // --- Instagram -----------------------------------------------------------

    private fun extractInstagram(pageUrl: String): ExtractionResult {
        val shortcode = parseInstagramShortcode(pageUrl)
            ?: return ExtractionResult.Failed(ExtractionResult.Reason.NOT_FOUND)

        return try {
            instagramEmbed(shortcode)
        } catch (e: Exception) {
            Log.w(TAG, "Instagram-Embed fehlgeschlagen, versuche Seite direkt", e)
            try {
                instagramDirect(pageUrl)
            } catch (e2: Exception) {
                Log.w(TAG, "Instagram direkt fehlgeschlagen", e2)
                ExtractionResult.Failed(ExtractionResult.Reason.NETWORK)
            }
        }
    }

    private fun instagramEmbed(shortcode: String): ExtractionResult {
        val html = fetchHtml("https://www.instagram.com/p/$shortcode/embed/captioned/")
        return parseInstagramHtml(html)
    }

    private fun instagramDirect(pageUrl: String): ExtractionResult {
        val html = fetchHtml(pageUrl)
        return parseInstagramHtml(html)
    }

    private fun parseInstagramHtml(html: String): ExtractionResult {
        val videoUrl = findInJson(html, "video_url")
            ?: findMetaContent(html, "og:video")
            ?: findMetaContent(html, "og:video:secure_url")
            ?: findVideoSrc(html)
            ?: findCdnVideoUrl(html)

        if (videoUrl != null) {
            val decoded = videoUrl
                .replace("\\u0026", "&")
                .replace("\\/", "/")
                .replace("\\\"", "\"")
                .replace("&amp;", "&")
            return ExtractionResult.Found(decoded, "Instagram Video")
        }

        if (html.contains("\"is_private\":true") ||
            html.contains("\"require_login\"") ||
            (html.contains("loginForm") && !html.contains("video"))) {
            return ExtractionResult.Failed(ExtractionResult.Reason.PRIVATE)
        }

        return ExtractionResult.Failed(ExtractionResult.Reason.NO_VIDEO)
    }

    private fun fetchHtml(pageUrl: String): String {
        val conn = (URL(pageUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT
            readTimeout = READ_TIMEOUT
            instanceFollowRedirects = true
            setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
            )
            setRequestProperty("Accept", "text/html,application/xhtml+xml")
            setRequestProperty("Accept-Language", "de-DE,de;q=0.9,en;q=0.5")
        }
        return try {
            conn.inputStream.bufferedReader().readText()
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    // --- URL-Parsing ---------------------------------------------------------

    fun parseYouTubeId(url: String): String? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val host = uri.host?.lowercase() ?: return null
        val path = uri.path.orEmpty()

        if (host == "youtu.be" || host == "www.youtu.be") {
            return path.trimStart('/').split('/').firstOrNull()
                ?.split('?')?.firstOrNull()
                ?.takeIf { it.isNotEmpty() && it.length <= 20 }
        }

        if (!host.contains("youtube")) return null

        uri.query?.split('&')
            ?.firstOrNull { it.startsWith("v=") }
            ?.substringAfter("v=")
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it.substringBefore('&') }

        val segments = path.split('/')
        for (i in segments.indices) {
            if (segments[i] in listOf("shorts", "embed", "v", "e", "live") &&
                i + 1 < segments.size) {
                return segments[i + 1].takeIf { it.isNotEmpty() && it.length <= 20 }
            }
        }
        return null
    }

    fun parseInstagramShortcode(url: String): String? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val segments = uri.path?.trimEnd('/')?.split('/') ?: return null
        for (i in segments.indices) {
            if (segments[i] in listOf("p", "reel", "reels", "tv") && i + 1 < segments.size) {
                return segments[i + 1].takeIf { it.isNotEmpty() }
            }
        }
        return null
    }

    // --- HTML-Hilfsmethoden --------------------------------------------------

    private val jsonValuePattern = Regex(""""video_url"\s*:\s*"([^"]+)"""")

    private fun findInJson(html: String, key: String): String? {
        val pattern = Regex(""""$key"\s*:\s*"([^"]+)"""")
        return pattern.find(html)?.groupValues?.get(1)
    }

    private fun findMetaContent(html: String, property: String): String? {
        val p1 = Regex(
            """<meta[^>]+property\s*=\s*["']$property["'][^>]+content\s*=\s*["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        )
        p1.find(html)?.let { return it.groupValues[1] }

        val p2 = Regex(
            """<meta[^>]+content\s*=\s*["']([^"']+)["'][^>]+property\s*=\s*["']$property["']""",
            RegexOption.IGNORE_CASE
        )
        return p2.find(html)?.groupValues?.get(1)
    }

    private fun findVideoSrc(html: String): String? {
        val pattern = Regex(
            """<video[^>]+src\s*=\s*["']([^"']+\.mp4[^"']*)["']""",
            RegexOption.IGNORE_CASE
        )
        return pattern.find(html)?.groupValues?.get(1)
    }

    private fun findCdnVideoUrl(html: String): String? {
        val pattern = Regex("""(https?://scontent[^"'\s\\]+\.mp4[^"'\s\\]*)""")
        return pattern.find(html)?.groupValues?.get(1)
    }
}
