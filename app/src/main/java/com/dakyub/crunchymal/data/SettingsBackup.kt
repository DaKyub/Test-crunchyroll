package com.dakyub.crunchymal.data

import android.content.Context
import com.dakyub.crunchymal.BuildConfig
import com.dakyub.crunchymal.data.mal.MalRecord
import com.dakyub.crunchymal.data.mal.MalRepository
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/** Valeur d'un réglage : type (s, b, i, l, f, ss) et contenu. */
@Serializable
data class BackupPref(val t: String, val v: JsonElement)

@Serializable
data class SettingsBackupFile(
    val app: String = "",
    val version: Int = 0,
    val createdAt: Long = 0,
    /** Fichier de réglages → clé → valeur. */
    val prefs: Map<String, Map<String, BackupPref>> = emptyMap(),
    /** Fiches MAL corrigées à la main. */
    val malCorrections: Map<String, MalRecord> = emptyMap(),
) {
    companion object {
        const val APP = "CrunchyMAL"
    }
}

/**
 * Sauvegarde des réglages dans un fichier JSON (clés API, comptes connectés, prénoms, options, corrections
 * MAL), à garder sur le téléphone et à remettre après une réinstallation ou sur une autre Shield.
 */
class SettingsBackup(private val context: Context, private val mal: MalRepository) {

    fun export(): String {
        val prefs = PREF_FILES.associateWith { name ->
            context.getSharedPreferences(name, Context.MODE_PRIVATE).all.mapNotNull { (key, value) ->
                val pref = when (value) {
                    is String -> BackupPref("s", JsonPrimitive(value))
                    is Boolean -> BackupPref("b", JsonPrimitive(value))
                    is Int -> BackupPref("i", JsonPrimitive(value))
                    is Long -> BackupPref("l", JsonPrimitive(value))
                    is Float -> BackupPref("f", JsonPrimitive(value))
                    is Set<*> -> BackupPref("ss", JsonArray(value.filterIsInstance<String>().map { JsonPrimitive(it) }))
                    else -> null
                }
                pref?.let { key to it }
            }.toMap()
        }
        val backup = SettingsBackupFile(
            app = SettingsBackupFile.APP,
            version = BuildConfig.VERSION_CODE,
            createdAt = System.currentTimeMillis(),
            prefs = prefs,
            malCorrections = mal.manualRecords(),
        )
        return Http.json.encodeToString(backup)
    }

    /** Remplace les réglages par ceux de la sauvegarde [text] ; renvoie le nombre d'éléments restaurés. */
    fun restore(text: String): Int {
        val backup = runCatching { Http.json.decodeFromString<SettingsBackupFile>(text) }
            .getOrElse { throw IllegalArgumentException("Fichier illisible : ce n'est pas une sauvegarde CrunchyMAL.") }
        require(backup.app == SettingsBackupFile.APP && backup.prefs.isNotEmpty()) { "Ce fichier n'est pas une sauvegarde CrunchyMAL." }
        var count = 0
        backup.prefs.filterKeys { it in PREF_FILES }.forEach { (name, values) ->
            val editor = context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
            values.forEach { (key, pref) ->
                val v = pref.v
                val known = runCatching {
                    when (pref.t) {
                        "s" -> editor.putString(key, v.jsonPrimitive.content)
                        "b" -> editor.putBoolean(key, v.jsonPrimitive.boolean)
                        "i" -> editor.putInt(key, v.jsonPrimitive.int)
                        "l" -> editor.putLong(key, v.jsonPrimitive.long)
                        "f" -> editor.putFloat(key, v.jsonPrimitive.float)
                        "ss" -> editor.putStringSet(key, v.jsonArray.map { it.jsonPrimitive.content }.toSet())
                        else -> null
                    }
                }.getOrNull() != null
                if (known) count++
            }
            // Écrit tout de suite : l'app redémarre juste après.
            editor.commit()
        }
        mal.restoreManual(backup.malCorrections)
        return count + backup.malCorrections.size
    }

    private companion object {
        /** Réglages, comptes Crunchyroll / ADN / MAL / Plex et alertes « À voir » déjà signalées. */
        val PREF_FILES = listOf("settings", "auth", "adn", "mal_auth", "plex", "arrivals")
    }
}
