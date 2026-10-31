package dev.wplayer.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import dev.wplayer.app.data.LockedFolders
import dev.wplayer.app.data.SearchIndexScheduler
import dev.wplayer.core.NativeCore
import javax.inject.Inject

@HiltAndroidApp
class WplayerApplication : Application(), Configuration.Provider {
    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var searchIndexScheduler: SearchIndexScheduler

    @Inject
    lateinit var lockedFolders: LockedFolders

    /** Workers get their dependencies from Hilt; the default initializer is removed in the manifest. */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        NativeCore.ensureLoaded()
        searchIndexScheduler.start()
        // Locked folders close again whenever no screen of the app is visible.
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStop(owner: LifecycleOwner) = lockedFolders.lockSession()
            },
        )
    }
}
