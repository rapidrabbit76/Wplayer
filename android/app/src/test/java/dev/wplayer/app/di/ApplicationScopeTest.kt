package dev.wplayer.app.di

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Work launched on the app scope and forgotten (saving a position, a setting) must not crash the app. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
class ApplicationScopeTest {
    @Test
    fun `a failing launch neither crashes the process nor stops later work`() = runBlocking {
        val crash = AtomicReference<Throwable?>()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> crash.set(e) }
        val scope = AppModule.applicationScope()
        try {
            scope.launch { throw IOException("disk full") }.join()
            val later = CompletableDeferred<String>()
            scope.launch { later.complete("saved") }
            assertEquals("saved", withTimeout(5_000) { later.await() })
            assertNull(crash.get())
        } finally {
            scope.cancel()
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
    }
}
