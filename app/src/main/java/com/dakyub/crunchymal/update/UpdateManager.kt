package com.dakyub.crunchymal.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.dakyub.crunchymal.BuildConfig
import com.dakyub.crunchymal.data.Http
import com.dakyub.crunchymal.data.fetch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import okhttp3.Request
import java.io.File

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val build: Int, val url: String) : UpdateState
    data class Downloading(val build: Int, val progress: Float) : UpdateState
    data object Installing : UpdateState
    data class Error(val message: String) : UpdateState
}

@Serializable
private data class GhAsset(val name: String = "", @SerialName("browser_download_url") val url: String = "")

@Serializable
private data class GhRelease(@SerialName("tag_name") val tag: String = "", val assets: List<GhAsset> = emptyList())

/**
 * Mises à jour intégrées : compare le numéro de build GitHub Actions (= versionCode) à celui de la
 * dernière Release du dépôt, télécharge l'APK et l'installe via PackageInstaller.
 */
class UpdateManager(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state

    val currentBuild: Int get() = BuildConfig.VERSION_CODE

    suspend fun check() {
        if (_state.value is UpdateState.Downloading || _state.value is UpdateState.Installing) return
        _state.value = UpdateState.Checking
        _state.value = try {
            val request = Request.Builder()
                .url("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest")
                .header("Accept", "application/vnd.github+json")
                .build()
            val release = Http.json.decodeFromString<GhRelease>(Http.client.fetch(request))
            val build = release.tag.removePrefix("build-").toIntOrNull()
            val apk = release.assets.firstOrNull { it.name.endsWith(".apk") }
            if (build != null && apk != null && build > currentBuild) UpdateState.Available(build, apk.url)
            else UpdateState.UpToDate
        } catch (e: Exception) {
            UpdateState.Error("Vérification impossible : ${e.message}")
        }
    }

    /** Vrai si Android autorise CrunchyMAL à installer des applications. */
    fun canInstall(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    /** Ouvre l'écran système d'autorisation "sources inconnues" pour CrunchyMAL. */
    fun openInstallPermissionSettings(): Boolean = runCatching {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }.isSuccess

    /**
     * Lance le téléchargement dans la portée de l'app : le bouton qui le déclenche disparaît dès que
     * l'état passe à "Téléchargement", il ne doit donc pas porter la coroutine.
     */
    fun startDownloadAndInstall(update: UpdateState.Available) {
        if (_state.value is UpdateState.Downloading || _state.value is UpdateState.Installing) return
        _state.value = UpdateState.Downloading(update.build, 0f)
        scope.launch { downloadAndInstall(update) }
    }

    suspend fun downloadAndInstall(update: UpdateState.Available) {
        try {
            val file = withContext(Dispatchers.IO) { download(update) }
            _state.value = UpdateState.Installing
            withContext(Dispatchers.IO) { install(file) }
        } catch (e: Exception) {
            _state.value = UpdateState.Error("Échec de la mise à jour : ${e.message}")
        }
    }

    private fun download(update: UpdateState.Available): File {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val file = File(dir, "CrunchyMAL.apk")
        Http.client.newCall(Request.Builder().url(update.url).build()).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            val body = response.body ?: error("réponse vide")
            val total = body.contentLength().takeIf { it > 0 } ?: -1L
            body.byteStream().use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var read: Int
                    var done = 0L
                    while (input.read(buffer).also { read = it } >= 0) {
                        output.write(buffer, 0, read)
                        done += read
                        if (total > 0) _state.value = UpdateState.Downloading(update.build, done.toFloat() / total)
                    }
                }
            }
        }
        return file
    }

    private fun install(file: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(context.packageName)
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("CrunchyMAL.apk", 0, file.length()).use { out ->
                file.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
            val pending = PendingIntent.getBroadcast(
                context, sessionId, Intent(context, InstallReceiver::class.java), flags,
            )
            session.commit(pending.intentSender)
        }
    }

    fun reportInstallResult(message: String?) {
        _state.value = if (message == null) UpdateState.Idle else UpdateState.Error(message)
    }
}
