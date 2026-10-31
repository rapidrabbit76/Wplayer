package dev.wplayer.app.data.update

import android.app.Application
import android.content.Context
import androidx.datastore.core.DataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.wplayer.app.data.AppSettingsSerializer
import dev.wplayer.app.data.SettingsRepository
import java.io.File
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
class UpdateInstallTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun aSecondTapBeforeTheFirstBytesStartsNothing() {
        shadowOf(context.packageManager).setCanRequestPackageInstalls(true)
        // Nothing launched here runs, as if the download had not received its first bytes yet.
        val scope = TestScope()
        val settings = SettingsRepository(
            DataStoreFactory.create(AppSettingsSerializer, scope = scope) { File(context.filesDir, "settings.json") },
            scope,
        )
        val repository = UpdateRepository(context, settings, scope, StandardTestDispatcher(scope.testScheduler))
        val release = ReleaseManifest(versionName = "2.0.0", versionCode = 200, apk = "wplayer-2.0.0.apk", sha256 = "00")

        repository.install(release)
        repository.install(release.copy(versionName = "2.0.1"))

        assertEquals(UpdateState.Downloading(release, 0f), repository.state.value)
    }
}
