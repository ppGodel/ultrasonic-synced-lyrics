package org.moire.ultrasonic.di

import androidx.room.Room
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.moire.ultrasonic.data.AppDatabase
import org.moire.ultrasonic.data.MIGRATION_1_2
import org.moire.ultrasonic.data.MIGRATION_2_1
import org.moire.ultrasonic.data.MIGRATION_2_3
import org.moire.ultrasonic.data.MIGRATION_3_2
import org.moire.ultrasonic.data.MIGRATION_3_4
import org.moire.ultrasonic.data.MIGRATION_4_3
import org.moire.ultrasonic.data.MIGRATION_4_5
import org.moire.ultrasonic.data.MIGRATION_5_4
import org.moire.ultrasonic.data.MIGRATION_5_6
import org.moire.ultrasonic.data.MIGRATION_6_5
import org.moire.ultrasonic.fragment.DownloadListModel
import org.moire.ultrasonic.model.AlbumListModel
import org.moire.ultrasonic.model.ArtistListModel
import org.moire.ultrasonic.model.ChatViewModel
import org.moire.ultrasonic.model.EditServerModel
import org.moire.ultrasonic.model.GenreListModel
import org.moire.ultrasonic.model.PlaylistListModel
import org.moire.ultrasonic.model.PodcastListModel
import org.moire.ultrasonic.model.SearchListModel
import org.moire.ultrasonic.model.ServerSettingsModel
import org.moire.ultrasonic.model.ShareListModel
import org.moire.ultrasonic.model.TrackCollectionModel
import org.moire.ultrasonic.util.CommunicationError
import org.moire.ultrasonic.util.Settings

const val SP_NAME = "Default_SP"
const val DB_FILENAME = "ultrasonic-database"

/**
 * This Koin module contains registration of classes related to permanent storage
 */
val appPermanentStorage = module {
    single(named(SP_NAME)) { Settings.preferences }

    single {
        Room.databaseBuilder(
            androidContext(),
            AppDatabase::class.java,
            DB_FILENAME
        )
            .addMigrations(MIGRATION_1_2)
            .addMigrations(MIGRATION_2_1)
            .addMigrations(MIGRATION_2_3)
            .addMigrations(MIGRATION_3_2)
            .addMigrations(MIGRATION_3_4)
            .addMigrations(MIGRATION_4_3)
            .addMigrations(MIGRATION_4_5)
            .addMigrations(MIGRATION_5_4)
            .addMigrations(MIGRATION_5_6)
            .addMigrations(MIGRATION_6_5)
            .build()
    }

    single { get<AppDatabase>().serverSettingDao() }

    viewModel { ServerSettingsModel(get(), get(), get()) }

    viewModel { EditServerModel(get()) }
    viewModel { ChatViewModel() }
    viewModel { AlbumListModel(get(), get(), get(), get()) }
    viewModel { ArtistListModel(get(), get(), get(), get()) }
    viewModel { GenreListModel(get(), get(), get(), get()) }
    viewModel { PlaylistListModel(get(), get(), get(), get(), get()) }
    viewModel { PodcastListModel(get(), get(), get(), get()) }
    viewModel { SearchListModel(get(), get(), get(), get()) }
    viewModel { ShareListModel(get(), get(), get(), get()) }
    viewModel { TrackCollectionModel(get(), get(), get(), get()) }
    viewModel { DownloadListModel(get(), get(), get(), get()) }
}
