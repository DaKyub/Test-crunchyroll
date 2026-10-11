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
import com.dakyub.crunchymal.ui.screens.adnContinueCards
import com.dakyub.crunchymal.ui.screens.crContinueCards
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
        if (!settings.newEpisodeNotifications && !settings.homeChannel) return Result.success()
        val selected = graph.providers.selected.value
        val providers = selected.filter { provider ->
            when (provider) {
                Provider.CRUNCHYROLL -> graph.auth.loggedIn.value
                Provider.ADN -> graph.adn.loggedIn.value
            }
        }.toSet()
        if (providers.isEmpty()) return Result.success()

        val newEpisodes = runCatching { graph.newEpisodeCards(providers) }.getOrElse { return Result.retry() }
        if (settings.newEpisodeNotifications) notifyNew(applicationContext, newEpisodes)
        if (settings.homeChannel) {
            val resume = coroutineScope {
                (if (Provider.CRUNCHYROLL in providers) runCatching { graph.crContinueCards() }.getOrDefault(emptyList()) else emptyList()) +
                    (if (Provider.ADN in providers) runCatching { graph.adnContinueCards(this) }.getOrDefault(emptyList()) else emptyList())
            }
            graph.homeChannel.publish(newEpisodes + resume)
        }
        return Result.success()
    }

    companion object {
        private const val WORK = "new_episodes"
        private const val CHANNEL = "new_episodes"
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
        // La permission (Android 13+) est vérifiée juste avant l'envoi.
        @SuppressLint("MissingPermission")
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(CHANNEL, "Nouveaux épisodes", NotificationManager.IMPORTANCE_DEFAULT)
                context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
            }
            val manager = NotificationManagerCompat.from(context)
            fresh.take(5).forEach { card ->
                val episodeId = card.episodeId ?: return@forEach
                val intent = episodeIntent(context, card.series, episodeId) ?: return@forEach
                val pending = PendingIntent.getActivity(
                    context,
                    episodeId.hashCode(),
                    intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                val notification = NotificationCompat.Builder(context, CHANNEL)
                    .setSmallIcon(R.drawable.ic_launcher)
                    .setContentTitle("Nouvel épisode : ${card.series.title}")
                    .setContentText(card.subtitle?.removePrefix("Nouveau : ").orEmpty())
                    .setContentIntent(pending)
                    .setAutoCancel(true)
                    .build()
                runCatching { manager.notify(episodeId.hashCode(), notification) }
            }
        }
    }
}
