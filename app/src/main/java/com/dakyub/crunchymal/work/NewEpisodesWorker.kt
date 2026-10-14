package com.dakyub.crunchymal.work

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.dakyub.crunchymal.CrunchyMalApp
import com.dakyub.crunchymal.R
import com.dakyub.crunchymal.data.CardItem
import com.dakyub.crunchymal.data.Provider
import com.dakyub.crunchymal.episodeIntent
import com.dakyub.crunchymal.seriesIntent
import android.content.Intent
import com.dakyub.crunchymal.ui.screens.resumeCards
import com.dakyub.crunchymal.ui.screens.newEpisodeCards
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.TimeUnit

/**
 * Tâche en arrière-plan (toutes les 4 h) : cherche les nouveaux épisodes des séries suivies, envoie une
 * notification pour chacun (une seule fois) et met à jour la chaîne de l'écran d'accueil.
 */
class NewEpisodesWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = (applicationContext as CrunchyMalApp).graph
        val settings = graph.settings
        if (!settings.newEpisodeNotifications && !settings.homeChannel && !settings.arrivalAlerts) return Result.success()
        val selected = graph.providers.selected.value
        val providers = selected.filter { provider ->
            when (provider) {
                Provider.CRUNCHYROLL -> graph.auth.loggedIn.value
                Provider.ADN -> graph.adn.loggedIn.value
            }
        }.toSet()
        if (providers.isEmpty()) return Result.success()

        // Alertes de disponibilité (liste MAL « À voir »), au plus toutes les 12 h.
        val prefs = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (settings.arrivalAlerts && System.currentTimeMillis() - prefs.getLong("arrivals_checked", 0) > 12 * 3600_000L) {
            runCatching { graph.arrivals.check(providers) }.onSuccess { arrivals ->
                prefs.edit().putLong("arrivals_checked", System.currentTimeMillis()).apply()
                arrivals.forEach { arrival ->
                    notify(
                        applicationContext,
                        channel = ARRIVALS,
                        id = "arrival:${arrival.provider}:${arrival.seriesId}".hashCode(),
                        title = "Disponible sur ${arrival.provider.label} : ${arrival.title}",
                        text = "Dans ta liste MAL « À voir »",
                        intent = seriesIntent(applicationContext, arrival.provider, arrival.seriesId),
                    )
                }
            }
            // Suites de séries terminées sur MAL arrivées sur Crunchyroll / ADN (trouvées au passage précédent).
            val crEnabled = Provider.CRUNCHYROLL in providers
            runCatching { graph.sequels.newlyAvailable() }.getOrDefault(emptyList()).forEach { sequel ->
                val found = graph.availability.found.value[sequel.malId] ?: return@forEach
                val (provider, id) = found.crunchyroll?.takeIf { crEnabled }?.let { Provider.CRUNCHYROLL to it.id }
                    ?: found.adn?.let { Provider.ADN to it.id } ?: return@forEach
                notify(
                    applicationContext,
                    channel = ARRIVALS,
                    id = "sequel:${sequel.malId}".hashCode(),
                    title = "Suite disponible sur ${provider.label} : ${sequel.titleEnglish ?: sequel.title}",
                    text = "Suite de « ${sequel.prequelTitle} », que tu as terminée",
                    intent = seriesIntent(applicationContext, provider, id),
                )
            }
            runCatching { graph.sequels.refresh(crEnabled) }
        }

        if (!settings.newEpisodeNotifications && !settings.homeChannel) return Result.success()
        val newEpisodes = runCatching { graph.newEpisodeCards(providers) }.getOrElse { return Result.retry() }
        if (settings.newEpisodeNotifications) notifyNew(applicationContext, newEpisodes)
        if (settings.homeChannel) {
            // « Reprendre » unifié (Crunchyroll, ADN et Plex), comme sur l'accueil de l'app.
            val resume = coroutineScope { runCatching { graph.resumeCards(this, providers) }.getOrDefault(emptyList()) }
            graph.homeChannel.publish(newEpisodes + resume)
        }
        return Result.success()
    }

    companion object {
        private const val WORK = "new_episodes"
        private const val CHANNEL = "new_episodes"
        private const val ARRIVALS = "arrivals"
        private const val PREFS = "notified_episodes"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<NewEpisodesWorker>(4, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        /**
         * Une notification par nouvel épisode jamais signalé. Au tout premier passage, les épisodes
         * déjà sortis sont seulement mémorisés (pas de rafale de notifications à l'installation).
         */
        fun notifyNew(context: Context, cards: List<CardItem>) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val known = prefs.getStringSet("ids", emptySet()).orEmpty()
            val firstRun = !prefs.getBoolean("initialized", false)
            val fresh = cards.filter { it.episodeId != null && it.episodeId !in known }
            prefs.edit()
                .putBoolean("initialized", true)
                .putStringSet("ids", (known + fresh.mapNotNull { it.episodeId }).toList().takeLast(500).toSet())
                .apply()
            if (firstRun || fresh.isEmpty()) return
            fresh.take(5).forEach { card ->
                val episodeId = card.episodeId ?: return@forEach
                notify(
                    context,
                    channel = CHANNEL,
                    id = episodeId.hashCode(),
                    title = "Nouvel épisode : ${card.series.title}",
                    text = card.subtitle?.removePrefix("Nouveau : ").orEmpty(),
                    intent = episodeIntent(context, card.series, episodeId),
                )
            }
        }

        /** Envoie une notification qui ouvre [intent] (si la permission de notifier est accordée). */
        @SuppressLint("MissingPermission")
        private fun notify(context: Context, channel: String, id: Int, title: String, text: String, intent: Intent?) {
            if (intent == null) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val name = if (channel == ARRIVALS) "Arrivées de ta liste MAL" else "Nouveaux épisodes"
                context.getSystemService(NotificationManager::class.java)
                    .createNotificationChannel(NotificationChannel(channel, name, NotificationManager.IMPORTANCE_DEFAULT))
            }
            val pending = PendingIntent.getActivity(context, id, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val notification = NotificationCompat.Builder(context, channel)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(pending)
                .setAutoCancel(true)
                .build()
            runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
        }
    }
}
