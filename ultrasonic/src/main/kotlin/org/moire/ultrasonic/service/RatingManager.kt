/*
 * RatingManager.kt
 * Copyright (C) 2009-2023 Ultrasonic developers
 *
 * Distributed under terms of the GNU GPLv3 license.
 */

package org.moire.ultrasonic.service

import androidx.media3.common.HeartRating
import androidx.media3.common.StarRating
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.moire.ultrasonic.data.RatingUpdate
import org.moire.ultrasonic.util.CoroutinePatterns
import timber.log.Timber

/*
* This class subscribes to RatingEvents and submits them to the server.
* In the future it could be extended to store the ratings when offline
* and submit them when back online.
* Only the manager should publish RatingSubmitted events
*/
class RatingManager(private val musicServiceFactory: MusicServiceFactory) :
    CoroutineScope by CoroutinePatterns.loggingScope(Dispatchers.Default) {
    private var ratingSubscription: Job? = null

    @Volatile
    var lastUpdate: RatingUpdate? = null

    init {
        ratingSubscription = launch {
            UltrasonicBus.ratingSubmitter.collect {
                submitRating(it)
            }
        }
    }

    internal fun submitRating(update: RatingUpdate) {
        // Don't submit the same rating twice. The marker is set immediately so that
        // identical updates arriving while this one is still in flight are suppressed.
        if (update.id == lastUpdate?.id && update.rating == lastUpdate?.rating) return
        lastUpdate = update

        val service = musicServiceFactory.getMusicService()
        val id = update.id

        Timber.i("Submitting rating to server: ${update.rating} for $id")

        launch {
            var success = false
            withContext(Dispatchers.IO) {
                try {
                    when (val rating = update.rating) {
                        is HeartRating -> {
                            if (rating.isHeart) {
                                service.star(id)
                            } else {
                                service.unstar(id)
                            }
                        }

                        is StarRating -> service.setRating(id, rating.starRating.toInt())
                    }
                    success = true
                } catch (all: Exception) {
                    Timber.e(all)
                }
            }

            // A failed submission must stay retryable: drop the dedupe marker, otherwise
            // submitting the exact same rating again would silently do nothing.
            if (!success) clearLastUpdate(update)

            UltrasonicBus.publishRatingPublished(
                update.copy(success = success)
            )
        }
    }

    @Synchronized
    private fun clearLastUpdate(update: RatingUpdate) {
        // Only clear if no newer rating was recorded in the meantime.
        if (lastUpdate == update) lastUpdate = null
    }
}
