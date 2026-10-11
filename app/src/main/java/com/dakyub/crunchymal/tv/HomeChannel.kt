package com.dakyub.crunchymal.tv

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.tvprovider.media.tv.PreviewChannel
import androidx.tvprovider.media.tv.PreviewChannelHelper
import androidx.tvprovider.media.tv.PreviewProgram
import androidx.tvprovider.media.tv.TvContractCompat
import com.dakyub.crunchymal.MainActivity
import com.dakyub.crunchymal.R
import com.dakyub.crunchymal.data.CardItem
import com.dakyub.crunchymal.data.Settings
import com.dakyub.crunchymal.episodeIntent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Chaîne « CrunchyMAL » de l'écran d'accueil Android TV : nouveaux épisodes puis reprises. Un clic
 * lance directement l'épisode dans l'app Crunchyroll ou ADN. Mise à jour à chaque chargement de
 * l'accueil et par la tâche en arrière-plan.
 */
class HomeChannel(private val context: Context, private val settings: Settings) {
    private val prefs = context.getSharedPreferences("home_channel", Context.MODE_PRIVATE)
    private val mutex = Mutex()

    /** Remplace les programmes de la chaîne (ou la supprime si l'option est désactivée). */
    suspend fun publish(items: List<CardItem>) = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return@withContext
        mutex.withLock {
            runCatching {
                val helper = PreviewChannelHelper(context)
                if (!settings.homeChannel) {
                    remove(helper)
                    return@runCatching
                }
                val channelId = ensureChannel(helper)
                prefs.getStringSet(PROGRAMS, emptySet()).orEmpty().forEach { id ->
                    id.toLongOrNull()?.let { runCatching { helper.deletePreviewProgram(it) } }
                }
                val ids = items
                    .filter { it.episodeId != null && it.imageUrl != null }
                    .distinctBy { it.episodeId }
                    .take(MAX_PROGRAMS)
                    .mapNotNull { item ->
                        val intent = episodeIntent(context, item.series, item.episodeId!!) ?: return@mapNotNull null
                        val program = PreviewProgram.Builder()
                            .setChannelId(channelId)
                            .setType(TvContractCompat.PreviewPrograms.TYPE_TV_EPISODE)
                            .setTitle(item.series.title)
                            .setDescription(item.subtitle.orEmpty())
                            .setPosterArtUri(Uri.parse(item.imageUrl))
                            .setPosterArtAspectRatio(
                                if (item.wide) TvContractCompat.PreviewPrograms.ASPECT_RATIO_16_9
                                else TvContractCompat.PreviewPrograms.ASPECT_RATIO_2_3
                            )
                            .setIntentUri(Uri.parse(intent.toUri(Intent.URI_INTENT_SCHEME)))
                            .setInternalProviderId(item.episodeId)
                            .build()
                        runCatching { helper.publishPreviewProgram(program).toString() }.getOrNull()
                    }
                prefs.edit().putStringSet(PROGRAMS, ids.toSet()).apply()
            }
        }
    }

    private fun ensureChannel(helper: PreviewChannelHelper): Long {
        val existing = prefs.getLong(CHANNEL, -1L)
        if (existing > 0 && runCatching { helper.getPreviewChannel(existing) }.getOrNull() != null) return existing
        val appIntent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val channel = PreviewChannel.Builder()
            .setDisplayName("CrunchyMAL")
            .setDescription("Nouveaux épisodes et reprises")
            .setAppLinkIntentUri(Uri.parse(appIntent.toUri(Intent.URI_INTENT_SCHEME)))
            .setLogo(logo())
            .build()
        val id = helper.publishChannel(channel)
        // Première chaîne de l'app : Android TV l'affiche sans passer par « Personnaliser les chaînes ».
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching { TvContractCompat.requestChannelBrowsable(context, id) }
        }
        prefs.edit().putLong(CHANNEL, id).putStringSet(PROGRAMS, emptySet()).apply()
        return id
    }

    private fun remove(helper: PreviewChannelHelper) {
        val existing = prefs.getLong(CHANNEL, -1L)
        if (existing > 0) runCatching { helper.deletePreviewChannel(existing) }
        prefs.edit().clear().apply()
    }

    private fun logo(): Bitmap {
        val bitmap = Bitmap.createBitmap(LOGO_SIZE, LOGO_SIZE, Bitmap.Config.ARGB_8888)
        ContextCompat.getDrawable(context, R.drawable.ic_launcher)?.apply {
            setBounds(0, 0, LOGO_SIZE, LOGO_SIZE)
            draw(Canvas(bitmap))
        }
        return bitmap
    }

    private companion object {
        const val CHANNEL = "channel_id"
        const val PROGRAMS = "program_ids"
        const val MAX_PROGRAMS = 20
        const val LOGO_SIZE = 160
    }
}
