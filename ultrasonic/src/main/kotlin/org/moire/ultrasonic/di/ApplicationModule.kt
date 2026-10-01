package org.moire.ultrasonic.di

import okhttp3.logging.HttpLoggingInterceptor
import org.koin.dsl.module
import org.moire.ultrasonic.adapters.PopupMenuFactory
import org.moire.ultrasonic.data.ActiveServerProvider
import org.moire.ultrasonic.log.TimberOkHttpLogger
import org.moire.ultrasonic.service.DownloadService
import org.moire.ultrasonic.service.MusicServiceFactory
import org.moire.ultrasonic.subsonic.ImageLoaderProvider
import org.moire.ultrasonic.subsonic.VideoPlayer
import org.moire.ultrasonic.util.CacheCleaner
import org.moire.ultrasonic.util.CommunicationError
import org.moire.ultrasonic.util.ContextMenuUtil
import org.moire.ultrasonic.util.DownloadUtil

/**
 * This Koin module contains the registration of general classes needed for Ultrasonic
 */
val applicationModule = module {
    single { ActiveServerProvider(get()) }

    single<HttpLoggingInterceptor.Logger> { TimberOkHttpLogger() }
    single { MusicServiceFactory(get(), get()) }

    // The ImageLoaderProvider resolves the SubsonicAPIClient per request: the factory
    // rebuilds it whenever the active server or its properties change, and the provider
    // rebuilds the loader when the client instance changes.
    single { ImageLoaderProvider { get<MusicServiceFactory>().apiClient() } }

    single { PopupMenuFactory(get()) }
    single { CacheCleaner(get(), get()) { DownloadService.inFlightDownloads() } }
    single { CommunicationError(get()) }
    single { DownloadUtil(get(), get(), get()) }
    single { ContextMenuUtil(get(), get(), get()) }
    single { VideoPlayer(get()) }
}
