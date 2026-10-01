/*
 * Util.kt
 * Copyright (C) 2009-2023 Ultrasonic developers
 *
 * Distributed under terms of the GNU GPLv3 license.
 */

package org.moire.ultrasonic.util

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.net.wifi.WifiManager.WifiLock
import android.os.Build
import android.os.Environment
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import java.io.Closeable
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import org.moire.ultrasonic.app.UApp.Companion.applicationContext
import org.moire.ultrasonic.domain.Bookmark
import org.moire.ultrasonic.domain.MusicDirectory
import org.moire.ultrasonic.domain.SearchResult
import org.moire.ultrasonic.domain.Track
import timber.log.Timber

/**
 * Contains various utility functions
 */
@Suppress("TooManyFunctions")
object Util {

    /**
     * Check if a usable network for downloading media is available
     *
     * @return Boolean
     */
    @JvmStatic
    fun hasUsableNetwork(): Boolean {
        val isUnmetered = !isNetworkRestricted()
        val wifiRequired = Settings.isWifiRequiredForDownload
        return (!wifiRequired || isUnmetered)
    }

    fun isNetworkRestricted(): Boolean = isNetworkMetered() || isNetworkCellular()

    private fun isNetworkMetered(): Boolean {
        val connManager = connectivityManager
        val capabilities = connManager.getNetworkCapabilities(
            connManager.activeNetwork
        )
        if (capabilities != null &&
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        ) {
            return false
        }
        return connManager.isActiveNetworkMetered
    }

    @Suppress("DEPRECATION")
    private fun isNetworkCellular(): Boolean {
        val connManager = connectivityManager
        val network = connManager.activeNetwork
            ?: return false // Nothing connected
        connManager.getNetworkInfo(network)
            ?: return true // Better be safe than sorry
        val capabilities = connManager.getNetworkCapabilities(network)
            ?: return true // Better be safe than sorry
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
    }

    private val connectivityManager: ConnectivityManager
        get() = applicationContext().getSystemService(Context.CONNECTIVITY_SERVICE)
            as ConnectivityManager

    fun createWifiLock(tag: String?): WifiLock {
        val wm =
            applicationContext().getSystemService(Context.WIFI_SERVICE) as WifiManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, tag)
        } else {
            @Suppress("DEPRECATION")
            return wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, tag)
        }
    }

    @JvmStatic
    fun isExternalStoragePresent(): Boolean =
        Environment.MEDIA_MOUNTED == Environment.getExternalStorageState()

    fun getScaledHeight(height: Double, width: Double, newWidth: Int): Int {
        // Try to keep correct aspect ratio of the original image, do not force a square
        val aspectRatio = height / width

        // Assume the size given refers to the width of the image, so calculate the new height using
        // the previously determined aspect ratio
        return (newWidth * aspectRatio).roundToInt()
    }

    fun getSongsFromSearchResult(searchResult: SearchResult): MusicDirectory {
        val musicDirectory = MusicDirectory()
        for (entry in searchResult.songs) {
            musicDirectory.add(entry)
        }
        return musicDirectory
    }

    @JvmStatic
    fun getSongsFromBookmarks(bookmarks: Iterable<Bookmark>): MusicDirectory {
        val musicDirectory = MusicDirectory()
        var song: Track
        for (bookmark in bookmarks) {
            song = bookmark.track
            song.bookmarkPosition = bookmark.position
            musicDirectory.add(song)
        }
        return musicDirectory
    }

    @JvmStatic
    @Suppress("MagicNumber")
    fun getNotificationImageSize(context: Context): Int {
        val metrics = context.resources.displayMetrics
        val imageSizeLarge =
            min(metrics.widthPixels, metrics.heightPixels).toFloat().roundToInt()
        return when {
            imageSizeLarge <= 480 -> {
                64
            }

            imageSizeLarge <= 768 -> 128

            else -> 256
        }
    }

    @Suppress("MagicNumber")
    fun getAlbumImageSize(context: Context?): Int {
        val metrics = context!!.resources.displayMetrics
        val imageSizeLarge =
            min(metrics.widthPixels, metrics.heightPixels).toFloat().roundToInt()
        return when {
            imageSizeLarge <= 480 -> {
                128
            }

            imageSizeLarge <= 768 -> 256

            else -> 512
        }
    }

    fun getMaxDisplayMetric(): Int {
        val metrics = applicationContext().resources.displayMetrics
        return max(metrics.widthPixels, metrics.heightPixels)
    }

    fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        // Raw height and width of image
        val height = options.outHeight
        val width = options.outWidth
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            // Calculate ratios of height and width to requested height and
            // width
            val heightRatio = (height.toFloat() / reqHeight.toFloat()).roundToInt()
            val widthRatio = (width.toFloat() / reqWidth.toFloat()).roundToInt()

            // Choose the smallest ratio as inSampleSize value, this will
            // guarantee
            // a final image with both dimensions larger than or equal to the
            // requested height and width.
            inSampleSize = min(heightRatio, widthRatio)
        }
        return inSampleSize
    }

    fun getPlayListFromTimeline(
        timeline: Timeline?,
        isShuffled: Boolean,
        firstIndex: Int? = null,
        count: Int = Int.MAX_VALUE
    ): List<MediaItem> {
        if (timeline == null) return emptyList()
        if (timeline.windowCount < 1) return emptyList()

        val playlist: MutableList<MediaItem> = mutableListOf()
        var i = firstIndex ?: timeline.getFirstWindowIndex(isShuffled)
        if (i == C.INDEX_UNSET) return emptyList()

        while (i != C.INDEX_UNSET && (count >= playlist.count())) {
            val window = timeline.getWindow(i, Timeline.Window())
            playlist.add(window.mediaItem)
            i = timeline.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, isShuffled)
        }
        return playlist
    }

    @JvmStatic
    fun getVersionName(context: Context): String? {
        var versionName: String? = null
        val pm = context.packageManager
        if (pm != null) {
            val packageName = context.packageName
            try {
                versionName = pm.getPackageInfo(packageName, 0).versionName
            } catch (ignored: PackageManager.NameNotFoundException) {
            }
        }
        return versionName
    }

    @Suppress("DEPRECATION")
    fun getVersionCode(context: Context): Int {
        var versionCode = 0
        val pm = context.packageManager
        if (pm != null) {
            val packageName = context.packageName
            try {
                versionCode = pm.getPackageInfo(packageName, 0).versionCode
            } catch (ignored: PackageManager.NameNotFoundException) {
            }
        }
        return versionCode
    }

    @JvmStatic
    fun scanMedia(file: String?) {
        // TODO this doesn't work for URIs
        MediaScannerConnection.scanFile(
            applicationContext(),
            arrayOf(file),
            null,
            null
        )
    }

    fun isFirstRun(): Boolean {
        if (Settings.firstRunExecuted) return false

        Settings.firstRunExecuted = true
        return true
    }

    fun dumpSettingsToLog() {
        Timber.d("Current user preferences")
        Timber.d("========================")
        val keys = Settings.preferences.all

        keys.forEach {
            Timber.d("${it.key}: ${it.value}")
        }
    }

    /**
     * Executes the given block if this is not null.
     * @return: the return of the block, or null if this is null
     */
    fun <T : Any, R> T?.ifNotNull(block: (T) -> R): R? = this?.let(block)

    /**
     * Closes a Closeable while ignoring any errors.
     **/
    fun Closeable?.safeClose() {
        try {
            this?.close()
        } catch (_: Exception) {
            // Ignored
        }
    }
}
