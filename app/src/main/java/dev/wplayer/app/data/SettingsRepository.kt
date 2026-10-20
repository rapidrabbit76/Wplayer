package dev.wplayer.app.data

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.Serializer
import dev.wplayer.app.data.model.AppSettings
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton
import dev.wplayer.app.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.catch
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

@Singleton
class SettingsRepository @Inject constructor(
    private val dataStore: DataStore<AppSettings>,
    @ApplicationScope scope: CoroutineScope,
) {
    /** An unreadable settings file falls back to defaults instead of failing. */
    val settings: Flow<AppSettings> = dataStore.data
        .catch { e -> if (e is IOException) emit(AppSettings()) else throw e }

    /**
     * The latest settings, read once and kept for the app's lifetime. Screens
     * start from it so their first frame (before their own flows emit) already
     * has the saved view mode instead of a default that then flips.
     */
    val current: StateFlow<AppSettings> = settings.stateIn(scope, SharingStarted.Eagerly, AppSettings())

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        dataStore.updateData(transform)
    }
}

/**
 * Stores [AppSettings] as JSON. Fields added in later versions take their
 * defaults; unknown fields and enum values from newer versions are ignored.
 */
object AppSettingsSerializer : Serializer<AppSettings> {
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        encodeDefaults = true
    }

    override val defaultValue: AppSettings = AppSettings()

    override suspend fun readFrom(input: InputStream): AppSettings = try {
        json.decodeFromString(AppSettings.serializer(), input.readBytes().decodeToString())
    } catch (e: SerializationException) {
        throw CorruptionException("unreadable settings", e)
    } catch (e: IllegalArgumentException) {
        throw CorruptionException("unreadable settings", e)
    }

    override suspend fun writeTo(t: AppSettings, output: OutputStream) {
        output.write(json.encodeToString(AppSettings.serializer(), t).encodeToByteArray())
    }
}
