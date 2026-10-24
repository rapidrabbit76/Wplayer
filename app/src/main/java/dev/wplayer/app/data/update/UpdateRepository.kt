package dev.wplayer.app.data.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.provider.Settings
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.wplayer.app.BuildConfig
import dev.wplayer.app.data.SettingsRepository
import dev.wplayer.app.data.forEachChunk
import dev.wplayer.app.data.reason
import dev.wplayer.app.data.toHex
import dev.wplayer.app.di.ApplicationScope
import dev.wplayer.app.di.IoDispatcher
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** `latest.json`, attached to each GitHub release by scripts/release.sh. */
@Serializable
data class ReleaseManifest(
    val versionName: String,
    val versionCode: Long,
    /** File name of the APK, attached to the same release. */
    val apk: String,
    val sha256: String,
    val notes: String = "",
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: ReleaseManifest) : UpdateState
    data class Downloading(val release: ReleaseManifest, val fraction: Float) : UpdateState

    /** Handed to the system installer, which asks the user to confirm. */
    data class Installing(val release: ReleaseManifest) : UpdateState

    /** Installing needs "install unknown apps" allowed for this app first. */
    data object NeedsInstallPermission : UpdateState
    data class Failed(val reason: String) : UpdateState
}

/**
 * Checks the repository's latest GitHub release for a newer version,
 * downloads it, verifies its checksum and hands it to the system installer. The installer only accepts
 * an APK signed with the installed app's key, so a tampered file cannot replace it.
 */
@Singleton
class UpdateRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    @ApplicationScope private val scope: CoroutineScope,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    val installedVersionName: String = BuildConfig.VERSION_NAME

    private val releases = GitHubReleases(
        repository = BuildConfig.UPDATE_REPOSITORY,
        token = BuildConfig.UPDATE_TOKEN,
        userAgent = "Wplayer/${BuildConfig.VERSION_NAME}",
    )

    /** Checks unless one is running; [onlyIfDue] skips it when the last check was recent. */
    fun check(onlyIfDue: Boolean = false) {
        if (_state.value is UpdateState.Checking || _state.value is UpdateState.Downloading) return
        scope.launch {
            val config = settings.settings.first().update
            if (onlyIfDue && System.currentTimeMillis() - config.lastCheckedAtMillis < AUTO_CHECK_INTERVAL_MILLIS) return@launch
            _state.value = UpdateState.Checking
            _state.value = try {
                val manifest = withContext(io) {
                    val bytes = releases.download(releases.latest().asset(MANIFEST_NAME)).inputStream.use { it.readBytes() }
                    json.decodeFromString<ReleaseManifest>(String(bytes))
                }
                settings.update { it.copy(update = it.update.copy(lastCheckedAtMillis = System.currentTimeMillis())) }
                if (manifest.versionCode > BuildConfig.VERSION_CODE) UpdateState.Available(manifest) else UpdateState.UpToDate
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                UpdateState.Failed(e.reason())
            }
        }
    }

    /** Downloads [release], checks its SHA-256 and opens the system installer, unless an install is under way. */
    fun install(release: ReleaseManifest) {
        if (!context.packageManager.canRequestPackageInstalls()) {
            _state.value = UpdateState.NeedsInstallPermission
            return
        }
        // Claimed before the download starts, so a second tap cannot write the same file at once.
        val previous = _state.getAndUpdate { if (it.underWay) it else UpdateState.Downloading(release, 0f) }
        if (previous.underWay) return
        scope.launch {
            try {
                val apk = File(context.cacheDir, APK_PATH)
                download(release, apk)
                _state.value = UpdateState.Installing(release)
                withContext(io) { commitToInstaller(apk) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = UpdateState.Failed(e.reason())
            }
        }
    }

    fun installDeclined() {
        _state.update { if (it is UpdateState.Installing) UpdateState.Available(it.release) else it }
    }

    fun installFailed(reason: String) {
        _state.update { if (it is UpdateState.Installing) UpdateState.Failed(reason) else it }
    }

    /** The system screen where "install unknown apps" is allowed for this app. */
    fun installPermissionIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private suspend fun download(release: ReleaseManifest, target: File) = withContext(io) {
        target.parentFile?.mkdirs()
        val digest = MessageDigest.getInstance("SHA-256")
        releases.download(releases.latest().asset(release.apk)).let { connection ->
            val total = connection.contentLengthLong.takeIf { it > 0 }
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    var copied = 0L
                    input.forEachChunk(DOWNLOAD_BUFFER_BYTES) { buffer, n ->
                        output.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                        copied += n
                        _state.value = UpdateState.Downloading(release, total?.let { copied.toFloat() / it } ?: 0f)
                    }
                }
            }
        }
        val actual = digest.digest().toHex()
        if (!actual.equals(release.sha256, ignoreCase = true)) {
            target.delete()
            throw IOException("downloaded file does not match its checksum")
        }
    }

    private fun commitToInstaller(apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            .apply { setAppPackageName(context.packageName) }
        val sessionId = installer.createSession(params)
        try {
            installer.openSession(sessionId).use { session ->
                session.openWrite("base.apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                // The installer fills in the result extras, so the intent must be mutable.
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
                val callback = PendingIntent.getBroadcast(context, sessionId, Intent(context, UpdateInstallReceiver::class.java), flags)
                session.commit(callback.intentSender)
            }
        } catch (e: Exception) {
            // Otherwise the half-written session and its copy of the APK stay on the device.
            installer.abandonSession(sessionId)
            throw e
        }
    }

    private val UpdateState.underWay get() = this is UpdateState.Downloading || this is UpdateState.Installing

    private companion object {
        const val MANIFEST_NAME = "latest.json"
        const val APK_PATH = "updates/wplayer.apk"
        const val AUTO_CHECK_INTERVAL_MILLIS = 12 * 60 * 60 * 1000L
        const val DOWNLOAD_BUFFER_BYTES = 64 * 1024
        val json = Json { ignoreUnknownKeys = true }
    }
}
