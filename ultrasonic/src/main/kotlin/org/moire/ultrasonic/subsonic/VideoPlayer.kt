package org.moire.ultrasonic.subsonic

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import org.moire.ultrasonic.R
import org.moire.ultrasonic.domain.Track
import org.moire.ultrasonic.service.MusicServiceFactory
import org.moire.ultrasonic.util.UiUtil
import org.moire.ultrasonic.util.Util

/**
 * This utility class helps starting video playback
 */
class VideoPlayer(private val musicServiceFactory: MusicServiceFactory) {

    fun playVideo(context: Context, track: Track?) {
        if (!Util.hasUsableNetwork() || track == null) {
            UiUtil.toast(R.string.select_album_no_network, true, context)
            return
        }
        try {
            val intent = Intent(Intent.ACTION_VIEW)
            val url = musicServiceFactory.getMusicService().getStreamUrl(
                track.id,
                maxBitRate = null,
                format = "raw"
            )
            intent.setDataAndType(
                url?.toUri(),
                "video/*"
            )
            context.startActivity(intent)
        } catch (all: Exception) {
            UiUtil.toast(all.toString(), false, context)
        }
    }
}
