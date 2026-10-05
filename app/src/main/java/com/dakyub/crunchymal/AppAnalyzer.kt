package com.dakyub.crunchymal

import android.content.Context
import android.content.res.XmlResourceParser
import org.xmlpull.v1.XmlPullParser
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile

/**
 * Diagnostic : cherche dans l'APK de l'app Crunchyroll officielle comment elle gère les liens
 * (filtres d'intent du manifeste + chaînes du code évoquant des liens profonds).
 */
object AppAnalyzer {
    private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"

    private val interesting = Regex(
        """crunchyroll://|deep.?link|^/?(watch|series|episode|play|media|show)(/|\?|$)|\{(id|guid|mediaId|episodeId|seriesId)}""",
        RegexOption.IGNORE_CASE,
    )

    fun analyze(context: Context): List<String> {
        val out = mutableListOf<String>()
        val appInfo = runCatching { context.packageManager.getApplicationInfo(OfficialApp.PACKAGE, 0) }.getOrNull()
            ?: return listOf("App Crunchyroll introuvable.")

        out += "— Filtres d'intent (manifeste) —"
        out += runCatching { intentFilters(context) }.getOrElse { listOf("Lecture du manifeste impossible : ${it.message}") }

        out += "— Chaînes de liens trouvées dans le code —"
        val apks = listOf(appInfo.sourceDir) + appInfo.splitSourceDirs.orEmpty()
        val found = sortedSetOf<String>()
        for (apk in apks) {
            runCatching {
                ZipFile(apk).use { zip ->
                    zip.entries().asSequence()
                        .filter { it.name.matches(Regex("""classes\d*\.dex""")) }
                        .forEach { entry ->
                            runCatching {
                                val bytes = zip.getInputStream(entry).use { it.readBytes() }
                                dexStrings(bytes).filterTo(found) { it.length in 3..200 && interesting.containsMatchIn(it) }
                            }.onFailure { out += "${entry.name} illisible : ${it.message}" }
                        }
                }
            }.onFailure { out += "Lecture de $apk impossible : ${it.message}" }
        }
        out += if (found.isEmpty()) listOf("(aucune)") else found.take(300)
        return out
    }

    /** Lit le manifeste binaire de l'app officielle via ses ressources. */
    private fun intentFilters(context: Context): List<String> {
        val res = context.packageManager.getResourcesForApplication(OfficialApp.PACKAGE)
        val parser: XmlResourceParser = res.assets.openXmlResourceParser("AndroidManifest.xml")
        val lines = mutableListOf<String>()
        var activity = ""
        var filter = StringBuilder()
        val p = parser
        try {
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                when (p.eventType) {
                    XmlPullParser.START_TAG -> when (p.name) {
                        "activity", "activity-alias" -> activity = p.getAttributeValue(ANDROID_NS, "name").orEmpty()
                            .removePrefix("com.crunchyroll.")
                        "intent-filter" -> filter = StringBuilder()
                        "action" -> filter.append(" action=").append(p.getAttributeValue(ANDROID_NS, "name")?.substringAfterLast('.'))
                        "category" -> filter.append(" cat=").append(p.getAttributeValue(ANDROID_NS, "name")?.substringAfterLast('.'))
                        "data" -> listOf("scheme", "host", "port", "path", "pathPrefix", "pathPattern", "mimeType").forEach { a ->
                            p.getAttributeValue(ANDROID_NS, a)?.let { filter.append(" $a=").append(it) }
                        }
                    }
                    XmlPullParser.END_TAG -> if (p.name == "intent-filter") lines += "$activity :$filter"
                }
            }
        } finally {
            p.close()
        }
        if (lines.isEmpty()) lines += "(aucun filtre lu — le manifeste lu n'est peut-être pas celui de Crunchyroll)"
        return lines
    }

    /** Extrait la table des chaînes d'un fichier .dex. */
    private fun dexStrings(dex: ByteArray): Sequence<String> = sequence {
        if (dex.size < 0x70) return@sequence
        val buf = ByteBuffer.wrap(dex).order(ByteOrder.LITTLE_ENDIAN)
        val count = buf.getInt(0x38)
        val idsOff = buf.getInt(0x3C)
        for (i in 0 until count) {
            var pos = buf.getInt(idsOff + i * 4)
            // Longueur ULEB128 (en caractères UTF-16), ignorée : la chaîne se termine par un octet nul.
            while (dex[pos].toInt() and 0x80 != 0) pos++
            pos++
            var end = pos
            while (end < dex.size && dex[end] != 0.toByte()) end++
            yield(String(dex, pos, end - pos, Charsets.UTF_8))
        }
    }
}
