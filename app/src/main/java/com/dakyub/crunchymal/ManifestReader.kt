package com.dakyub.crunchymal

import android.content.Context
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile

/**
 * Diagnostic : lit le manifeste (XML binaire) d'une app installée pour lister les liens qu'elle déclare
 * accepter (intent-filter : actions, catégories, schémas, hôtes, chemins), activité par activité.
 * Format : https://android.googlesource.com/platform/frameworks/base/+/main/libs/androidfw/include/androidfw/ResourceTypes.h
 */
object ManifestReader {
    private const val STRING_POOL = 0x0001
    private const val XML = 0x0003
    private const val START_ELEMENT = 0x0102
    private const val END_ELEMENT = 0x0103
    private const val RESOURCE_MAP = 0x0180

    /** Identifiants des attributs android: utiles (au cas où leur nom aurait été retiré du manifeste). */
    private val attrIds = mapOf(
        0x01010003 to "name", 0x01010026 to "mimeType", 0x01010027 to "scheme", 0x01010028 to "host",
        0x01010029 to "port", 0x0101002a to "path", 0x0101002b to "pathPrefix", 0x0101002c to "pathPattern",
    )

    private class Filter(val actions: MutableList<String> = mutableListOf(), val categories: MutableList<String> = mutableListOf(),
                         val data: MutableList<Map<String, String>> = mutableListOf())

    fun intentFilters(context: Context, packageName: String): List<String> {
        val apk = runCatching { context.packageManager.getApplicationInfo(packageName, 0).sourceDir }.getOrNull()
            ?: return listOf("Application $packageName introuvable.")
        val bytes = runCatching {
            ZipFile(apk).use { zip -> zip.getInputStream(zip.getEntry("AndroidManifest.xml")).use { it.readBytes() } }
        }.getOrElse { return listOf("Manifeste illisible : ${it.javaClass.simpleName} ${it.message}") }
        return runCatching { parse(bytes) }.getOrElse { listOf("Analyse du manifeste impossible : ${it.javaClass.simpleName} ${it.message}") }
    }

    private fun parse(bytes: ByteArray): List<String> {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(buf.getShort(0).toInt() and 0xFFFF == XML) { "pas un XML binaire" }
        var strings = emptyList<String>()
        var resIds = IntArray(0)
        val out = mutableListOf<String>()
        var component: String? = null
        var filter: Filter? = null
        var pos = buf.getShort(2).toInt() and 0xFFFF
        while (pos + 8 <= bytes.size) {
            val type = buf.getShort(pos).toInt() and 0xFFFF
            val headerSize = buf.getShort(pos + 2).toInt() and 0xFFFF
            val size = buf.getInt(pos + 4)
            if (size <= 0) break
            when (type) {
                STRING_POOL -> strings = readStrings(buf, pos)
                RESOURCE_MAP -> resIds = IntArray((size - headerSize) / 4) { buf.getInt(pos + headerSize + it * 4) }
                START_ELEMENT -> {
                    val name = strings.getOrNull(buf.getInt(pos + 20)).orEmpty()
                    val attrStart = buf.getShort(pos + 24).toInt() and 0xFFFF
                    val attrSize = buf.getShort(pos + 26).toInt() and 0xFFFF
                    val attrCount = buf.getShort(pos + 28).toInt() and 0xFFFF
                    val attrs = (0 until attrCount).associate { i ->
                        val a = pos + 16 + attrStart + i * attrSize
                        val nameIndex = buf.getInt(a + 4)
                        val attrName = strings.getOrNull(nameIndex)?.takeIf { it.isNotEmpty() }
                            ?: resIds.getOrNull(nameIndex)?.let { attrIds[it] } ?: "#$nameIndex"
                        attrName to value(buf, a, strings)
                    }
                    when (name) {
                        "activity", "activity-alias" -> component = attrs["name"]
                        "intent-filter" -> filter = Filter()
                        "action" -> filter?.actions?.add(attrs["name"].orEmpty().removePrefix("android.intent.action."))
                        "category" -> filter?.categories?.add(attrs["name"].orEmpty().removePrefix("android.intent.category."))
                        "data" -> filter?.data?.add(attrs)
                    }
                }
                END_ELEMENT -> {
                    when (strings.getOrNull(buf.getInt(pos + 20))) {
                        "intent-filter" -> {
                            val f = filter
                            // Seuls les filtres qui acceptent des liens (données ou action VIEW) sont utiles ici.
                            if (f != null && (f.data.isNotEmpty() || "VIEW" in f.actions)) {
                                out += "${component ?: "?"} · actions=${f.actions.joinToString(",")} · catégories=${f.categories.joinToString(",")}"
                                f.data.forEach { d -> out += "    data " + d.entries.joinToString(" ") { "${it.key}=${it.value}" } }
                            }
                            filter = null
                        }
                        "activity", "activity-alias" -> component = null
                    }
                }
            }
            pos += size
        }
        return out.ifEmpty { listOf("Aucun intent-filter acceptant des liens.") }
    }

    private fun value(buf: ByteBuffer, attr: Int, strings: List<String>): String {
        val raw = buf.getInt(attr + 8)
        if (raw >= 0) strings.getOrNull(raw)?.let { return it }
        val dataType = buf.get(attr + 15).toInt() and 0xFF
        val data = buf.getInt(attr + 16)
        return when (dataType) {
            0x03 -> strings.getOrNull(data).orEmpty()
            0x01 -> "@0x%08x".format(data)
            0x12 -> (data != 0).toString()
            0x10 -> data.toString()
            else -> "0x%x".format(data)
        }
    }

    private fun readStrings(buf: ByteBuffer, chunk: Int): List<String> {
        val count = buf.getInt(chunk + 8)
        val utf8 = buf.getInt(chunk + 16) and 0x100 != 0
        val stringsStart = chunk + buf.getInt(chunk + 20)
        return (0 until count).map { i ->
            var p = stringsStart + buf.getInt(chunk + 28 + i * 4)
            if (utf8) {
                // Longueur en caractères puis en octets, chacune sur 1 ou 2 octets.
                if (buf.get(p).toInt() and 0x80 != 0) p += 2 else p += 1
                var len = buf.get(p).toInt() and 0xFF
                if (len and 0x80 != 0) { len = ((len and 0x7F) shl 8) or (buf.get(p + 1).toInt() and 0xFF); p += 2 } else p += 1
                ByteArray(len) { buf.get(p + it) }.toString(Charsets.UTF_8)
            } else {
                var len = buf.getShort(p).toInt() and 0xFFFF
                if (len and 0x8000 != 0) { len = ((len and 0x7FFF) shl 16) or (buf.getShort(p + 2).toInt() and 0xFFFF); p += 4 } else p += 2
                CharArray(len) { buf.getChar(p + it * 2) }.concatToString()
            }
        }
    }
}
