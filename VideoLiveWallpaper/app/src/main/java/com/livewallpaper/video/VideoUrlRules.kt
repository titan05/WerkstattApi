package com.livewallpaper.video

import java.net.URI

/** Ergebnis der Pruefung einer eingegebenen Adresse. */
sealed class UrlCheck {
    /** Adresse sieht nach einer direkt ladbaren Videodatei aus. */
    data class Ok(val url: String) : UrlCheck()

    /** Portal, dessen Video automatisch heruntergeladen werden kann. */
    data class Extractable(val service: String, val url: String) : UrlCheck()

    /** Videoportal - dort gibt es keine direkte Videodatei. */
    data class Streaming(val service: String, val url: String) : UrlCheck()

    /** Unverschluesselt (http) - wird aus Sicherheitsgruenden nicht geladen. */
    object Cleartext : UrlCheck()

    object Empty : UrlCheck()

    object Invalid : UrlCheck()
}

/**
 * Prueft eingegebene Adressen, bevor etwas geladen wird - ohne
 * Android-Abhaengigkeiten, damit es sich mit Unit-Tests pruefen laesst.
 */
object VideoUrlRules {

    /** Portale, deren Video die App automatisch herunterladen kann. */
    private val extractableHosts = mapOf(
        "youtube.com" to "YouTube",
        "youtu.be" to "YouTube",
        "youtube-nocookie.com" to "YouTube",
        "instagram.com" to "Instagram"
    )

    /** Portale ohne automatischen Download - nur Info-Dialog. */
    private val streamingHosts = mapOf(
        "vimeo.com" to "Vimeo",
        "tiktok.com" to "TikTok",
        "facebook.com" to "Facebook",
        "fb.watch" to "Facebook",
        "dailymotion.com" to "Dailymotion",
        "twitch.tv" to "Twitch",
        "netflix.com" to "Netflix",
        "twitter.com" to "X",
        "x.com" to "X"
    )

    private val videoExtensions =
        setOf("mp4", "m4v", "webm", "mkv", "mov", "3gp", "avi", "ts", "mpeg", "mpg")

    fun check(input: String): UrlCheck {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return UrlCheck.Empty

        // Ohne Schema ist "beispiel.de/clip.mp4" gemeint - https ergaenzen.
        val candidate = if (trimmed.contains("://")) trimmed else "https://$trimmed"

        val uri = try {
            URI(candidate)
        } catch (e: Exception) {
            return UrlCheck.Invalid
        }

        val host = uri.host?.lowercase() ?: return UrlCheck.Invalid
        extractableServiceFor(host)?.let { return UrlCheck.Extractable(it, candidate) }
        serviceFor(host)?.let { return UrlCheck.Streaming(it, candidate) }

        return when (uri.scheme?.lowercase()) {
            "https" -> UrlCheck.Ok(candidate)
            "http" -> UrlCheck.Cleartext
            else -> UrlCheck.Invalid
        }
    }

    private fun extractableServiceFor(host: String): String? = extractableHosts.entries
        .firstOrNull { (domain, _) -> host == domain || host.endsWith(".$domain") }
        ?.value

    private fun serviceFor(host: String): String? = streamingHosts.entries
        .firstOrNull { (domain, _) -> host == domain || host.endsWith(".$domain") }
        ?.value

    /** Dateiname aus der Adresse, z.B. "sonnenuntergang.mp4". */
    fun fileNameFrom(url: String): String? {
        val path = try {
            URI(url).path
        } catch (e: Exception) {
            null
        } ?: return null
        val name = path.substringAfterLast('/')
        return name.takeIf { it.isNotBlank() && it.length <= 120 }
    }

    /** Ob die Adresse auf eine Videodatei zeigt - ergaenzt die Typangabe des Servers. */
    fun looksLikeVideoFile(url: String): Boolean {
        val name = fileNameFrom(url) ?: return false
        val extension = name.substringAfterLast('.', "").lowercase()
        return extension in videoExtensions
    }

    /** Passende Dateiendung, notfalls aus dem vom Server gemeldeten Typ. */
    fun extensionFor(url: String, contentType: String?): String {
        val fromUrl = fileNameFrom(url)?.substringAfterLast('.', "")?.lowercase()
        if (!fromUrl.isNullOrEmpty() && fromUrl in videoExtensions) return fromUrl

        val subtype = contentType?.substringAfter('/', "")?.substringBefore(';')?.trim()?.lowercase()
        return when (subtype) {
            "quicktime" -> "mov"
            "x-matroska" -> "mkv"
            "mp2t" -> "ts"
            null, "" -> "mp4"
            else -> if (subtype in videoExtensions) subtype else "mp4"
        }
    }
}
