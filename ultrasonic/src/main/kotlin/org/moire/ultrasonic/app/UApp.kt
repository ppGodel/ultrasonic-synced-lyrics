package org.moire.ultrasonic.app

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.StrictMode
import android.os.StrictMode.ThreadPolicy
import android.os.StrictMode.VmPolicy
import androidx.core.content.ContextCompat
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.memory.MemoryCache
import coil3.request.crossfade
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.android.ext.koin.androidContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.moire.ultrasonic.BuildConfig
import org.moire.ultrasonic.di.appPermanentStorage
import org.moire.ultrasonic.di.applicationModule
import org.moire.ultrasonic.di.baseNetworkModule
import org.moire.ultrasonic.di.mediaPlayerModule
import org.moire.ultrasonic.imageloader.AvatarFetcher
import org.moire.ultrasonic.imageloader.AvatarKeyer
import org.moire.ultrasonic.imageloader.CoverArtFetcher
import org.moire.ultrasonic.imageloader.CoverArtKeyer
import org.moire.ultrasonic.log.FileLoggerTree
import org.moire.ultrasonic.log.TimberKoinLogger
import org.moire.ultrasonic.service.MusicServiceFactory
import org.moire.ultrasonic.util.CoroutinePatterns
import org.moire.ultrasonic.util.FileUtil
import org.moire.ultrasonic.util.Settings
import org.moire.ultrasonic.util.Storage
import org.moire.ultrasonic.util.Util
import timber.log.Timber
import timber.log.Timber.DebugTree

/**
 * The Main class of the Application
 */

class UApp :
    Application(),
    SingletonImageLoader.Factory,
    KoinComponent {

    private var ioScope = CoroutinePatterns.loggingScope(Dispatchers.IO)

    init {
        instance = this
        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(ThreadPolicy.Builder().detectAll().penaltyLog().build())
            StrictMode.setVmPolicy(VmPolicy.Builder().detectAllExceptSocket().penaltyLog().build())
        }
    }

    var initiated = false
    var isFirstRun = false
    var setupDialogDisplayed = false

    override fun onCreate() {
        super.onCreate()

        if (BuildConfig.DEBUG) {
            Timber.plant(DebugTree())
        }

        Timber.d("onCreate called")

        // This flag flips once per install and NavigationActivity reads it while creating
        // its UI, which can happen before the background initialization below completes.
        // Reading it only on the main thread avoids serving a stale "false" on first run.
        isFirstRun = Util.isFirstRun()

        // In general we should not access the settings from the main thread to avoid blocking...
        ioScope.launch {
            if (Settings.debugLogToFile) {
                FileLoggerTree.plantToTimberForest()
                Util.dumpSettingsToLog()
            }
            // Populate externalFilesDir early
            FileUtil.cachedUltrasonicDirectory = FileUtil.ultrasonicDirectory
            Storage.mediaRoot.value
        }

        startKoin()
    }

    override fun newImageLoader(context: Context): ImageLoader = ImageLoader.Builder(context)
        .components {
            // The client is resolved lazily when a fetcher is created: the factory
            // rebuilds the SubsonicAPIClient whenever the active server changes.
            add(CoverArtFetcher.Factory { get<MusicServiceFactory>().apiClient() })
            add(CoverArtKeyer())
            add(AvatarFetcher.Factory { get<MusicServiceFactory>().apiClient() })
            add(AvatarKeyer())
        }
        .memoryCache(
            MemoryCache.Builder().maxSizeBytes(
                calculateMemoryCacheSize(context)
            ).build()
        )
        .crossfade(true)
        .build()

    private fun calculateMemoryCacheSize(context: Context): Long {
        val am = ContextCompat.getSystemService(
            context,
            ActivityManager::class.java
        )
        val largeHeap = context.applicationInfo.flags and ApplicationInfo.FLAG_LARGE_HEAP != 0
        val memoryClass = if (largeHeap) am!!.largeMemoryClass else am!!.memoryClass
        // Target 25% of the available heap.
        @Suppress("MagicNumber")
        return 1024L * 1024L * memoryClass / 4
    }

    internal fun startKoin() {
        // "Koin is ready" is owned by startKoin alone; NavigationActivity relies on this
        // flag to re-start Koin if the Application was recreated without a completed onCreate.
        initiated = true
        startKoin {
            // Sometimes Koin breaks when Kotlin version is upgraded,
            // you can normally fix it by changing to logger(TimberKoinLogger(Level.ERROR))
            // See https://github.com/InsertKoinIO/koin/issues/1188
            logger(TimberKoinLogger())

            // declare Android context
            androidContext(this@UApp)

            // declare modules to use
            modules(
                applicationModule,
                appPermanentStorage,
                baseNetworkModule,
                mediaPlayerModule
            )
        }
    }

    internal fun shutdownKoin() {
        stopKoin()
        initiated = false
    }

    companion object {
        var instance: UApp? = null

        fun applicationContext(): Context = requireNotNull(instance) {
            "UApp.instance accessed before Application.onCreate"
        }.applicationContext
    }
}

private fun VmPolicy.Builder.detectAllExceptSocket(): VmPolicy.Builder {
    detectLeakedSqlLiteObjects()
    detectActivityLeaks()
    detectLeakedClosableObjects()
    detectLeakedRegistrationObjects()
    detectFileUriExposure()
    detectContentUriWithoutPermission()

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        detectCredentialProtectedWhileLocked()
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        detectUnsafeIntentLaunch()
        detectIncorrectContextUse()
    }
    return this
}
