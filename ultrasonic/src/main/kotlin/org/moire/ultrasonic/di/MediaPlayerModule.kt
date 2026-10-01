package org.moire.ultrasonic.di

import android.content.Context
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import org.moire.ultrasonic.model.NowPlayingViewModel
import org.moire.ultrasonic.model.PlayerViewModel
import org.moire.ultrasonic.service.DefaultMediaControllerProvider
import org.moire.ultrasonic.service.ExternalStorageMonitor
import org.moire.ultrasonic.service.MediaControllerProvider
import org.moire.ultrasonic.service.MediaPlayerManager
import org.moire.ultrasonic.service.PlaybackQueueActions
import org.moire.ultrasonic.service.PlaybackRepository
import org.moire.ultrasonic.service.PlaybackStateSerializer
import org.moire.ultrasonic.service.RatingManager
import org.moire.ultrasonic.subsonic.NetworkAndStorageChecker
import org.moire.ultrasonic.subsonic.ShareHandler

/**
 * This Koin module contains the registration of classes related to the media player
 */
val mediaPlayerModule = module {

    // These are dependency-free
    single { PlaybackStateSerializer(androidContext()) }
    single { ExternalStorageMonitor() }
    single { NetworkAndStorageChecker(get()) }
    single { ShareHandler(get()) }

    single<MediaControllerProvider> { DefaultMediaControllerProvider(get()) }
    single<PlaybackRepository> { MediaPlayerManager(get()) }
    single { PlaybackQueueActions(get()) }
    // createdAtStart: the RatingManager has to listen for rating events from the beginning.
    single(createdAtStart = true) { RatingManager(get()) }
    viewModel { NowPlayingViewModel(get()) }
    viewModel { PlayerViewModel(get()) }
}
