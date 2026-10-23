package com.dakyub.crunchymal

import android.content.Context
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.zip.ZipFile

/**
 * Diagnostic : cherche dans le code de l'app Crunchyroll officielle les chaînes évoquant des liens
 * profonds. Chaque .dex est copié dans le cache puis lu en mémoire mappée (pas de gros tableau sur le tas).
 */
object AppAnalyzer {
    // Accolades toujours échappées : le moteur regex d'Android (ICU) refuse un "}" isolé.
    // Retient les schémas de liens, les chemins d'API (avec paramètres {…}) et les mots-clés utiles.
    private val interesting by lazy {
        Regex(
            """[a-z]+://|deep.?link|uriPattern|bookmark|watchlist|history|favorite|progress|playlist|^/?[a-z0-9_-]+/[a-z0-9_\{\}/-]*\{[a-zA-Z_]+\}|\{[a-zA-Z_]*(id|Id|guid)\}""",
            RegexOption.IGNORE_CASE,
        )
    }
    private val dexName by lazy { Regex("""classes\d*\.dex""") }

    /**
     * [extra] : autres chaînes à retenir (en plus des liens et chemins d'API) ; [keep] : filtre final des
     * chaînes affichées.
     */
    fun analyze(
        context: Context,
        packageName: String,
        limit: Int = 150,
        extra: Regex? = null,
        keep: (String) -> Boolean = { true },
    ): List<String> {
        val out = mutableListOf<String>()
        val appInfo = try {
            context.packageManager.getApplicationInfo(packageName, 0)
        } catch (t: Throwable) {
            return listOf("Application $packageName introuvable.")
        }

        val found = sortedSetOf<String>()
        val apks = listOf(appInfo.sourceDir) + appInfo.splitSourceDirs.orEmpty()
        val tmp = File(context.cacheDir, "analyze.dex")
        for (apk in apks) {
            try {
                ZipFile(apk).use { zip ->
                    val dexEntries = zip.entries().asSequence()
                        .filter { it.name.matches(dexName) }
                        .toList()
                    out += "${File(apk).name} : ${dexEntries.size} fichier(s) dex"
                    for (entry in dexEntries) {
                        try {
                            zip.getInputStream(entry).use { input -> tmp.outputStream().use { input.copyTo(it) } }
                            scanDex(tmp, found) { interesting.containsMatchIn(it) || extra?.containsMatchIn(it) == true }
                        } catch (t: Throwable) {
                            out += "${entry.name} illisible : ${t.javaClass.simpleName} ${t.message}"
                        } finally {
                            tmp.delete()
                        }
                    }
                }
            } catch (t: Throwable) {
                out += "Lecture de $apk impossible : ${t.javaClass.simpleName} ${t.message}"
            }
        }
        out += "— ${found.size} chaîne(s) brutes, les plus pertinentes ci-dessous —"
        out += found.asSequence()
            .filter { it.length <= 120 && '\n' !in it && "deeplink_url_format" !in it && !isForeignClass(it, packageName) && keep(it) }
            .map { shorten(it) }
            .distinct()
            .sortedWith(compareBy({ priority(it) }, { it }))
            .take(limit)
            .toList()
        return out
    }

    /** Descripteur de classe (Lcom/x/Y;) n'appartenant pas à l'app analysée : bruit des bibliothèques. */
    private fun isForeignClass(s: String, packageName: String): Boolean =
        s.startsWith("L") && s.endsWith(";") && !s.startsWith("L" + packageName.substringBeforeLast('.').replace('.', '/'))

    /** 0 = schéma crunchyroll://, 1 = chemins/modèles d'URL, 2 = noms de classes "deeplink", 3 = reste. */
    private fun priority(s: String): Int = when {
        "://" in s && !s.startsWith("http") -> 0
        s.startsWith("/") || '{' in s || "://" in s -> 1
        s.startsWith("L") && s.endsWith(";") || '.' in s && ' ' !in s -> 2
        else -> 3
    }

    /** Raccourcit les descripteurs de classes (Lcom/crunchyroll/x/Y;) en x.Y. */
    private fun shorten(s: String): String =
        if (s.startsWith("L") && s.endsWith(";")) {
            s.removePrefix("L").removeSuffix(";").replace('/', '.').removePrefix("com.crunchyroll.")
        } else s

    /** Toutes les chaînes du code de [packageName] qui passent [filter] (vide si l'app est absente). */
    fun strings(context: Context, packageName: String, filter: (String) -> Boolean): Set<String> {
        val appInfo = runCatching { context.packageManager.getApplicationInfo(packageName, 0) }.getOrNull() ?: return emptySet()
        val found = mutableSetOf<String>()
        val tmp = File(context.cacheDir, "strings.dex")
        for (apk in listOf(appInfo.sourceDir) + appInfo.splitSourceDirs.orEmpty()) {
            runCatching {
                ZipFile(apk).use { zip ->
                    zip.entries().asSequence().filter { it.name.matches(dexName) }.toList().forEach { entry ->
                        try {
                            zip.getInputStream(entry).use { input -> tmp.outputStream().use { input.copyTo(it) } }
                            scanDex(tmp, found, filter)
                        } finally {
                            tmp.delete()
                        }
                    }
                }
            }
        }
        return found
    }

    /** Parcourt la table des chaînes du .dex (format : https://source.android.com/docs/core/runtime/dex-format). */
    private fun scanDex(file: File, found: MutableSet<String>, filter: (String) -> Boolean) {
        RandomAccessFile(file, "r").use { raf ->
            val buf: MappedByteBuffer = raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length())
            buf.order(ByteOrder.LITTLE_ENDIAN)
            val size = buf.limit()
            if (size < 0x70) return
            val count = buf.getInt(0x38)
            val idsOff = buf.getInt(0x3C)
            val bytes = ByteArray(256)
            for (i in 0 until count) {
                var pos = buf.getInt(idsOff + i * 4)
                // Longueur ULEB128 ignorée : la chaîne MUTF-8 se termine par un octet nul.
                while (pos < size && buf.get(pos).toInt() and 0x80 != 0) pos++
                pos++
                var len = 0
                while (pos + len < size && buf.get(pos + len) != 0.toByte() && len < bytes.size) {
                    bytes[len] = buf.get(pos + len)
                    len++
                }
                if (len < 3 || len >= bytes.size) continue
                val s = String(bytes, 0, len, Charsets.UTF_8)
                if (filter(s)) found += s
            }
        }
    }
}
