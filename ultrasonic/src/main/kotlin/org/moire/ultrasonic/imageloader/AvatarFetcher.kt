/*
 * AvatarFetcher.kt
 * Copyright (C) 2009-2026 Ultrasonic developers
 *
 * Distributed under terms of the GNU GPLv3 license.
 */

package org.moire.ultrasonic.imageloader

import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.key.Keyer
import coil3.request.Options
import java.io.IOException
import okio.buffer
import okio.source
import org.moire.ultrasonic.api.subsonic.SubsonicAPIClient

data class AvatarRequest(val username: String)

class AvatarKeyer : Keyer<AvatarRequest> {
    override fun key(data: AvatarRequest, options: Options): String = data.username
}

class AvatarFetcher(
    private val avatarRequest: AvatarRequest,
    private val options: Options,
    private val client: SubsonicAPIClient
) : Fetcher {
    override suspend fun fetch(): FetchResult {
        // Inverted call order, because Mockito has problems with chained calls.
        val response = client.toStreamResponse(
            client.api.getAvatar(avatarRequest.username).execute()
        )

        if (response.hasError() || response.stream == null) {
            throw IOException("${response.apiError}")
        }
        return SourceFetchResult(
            ImageSource(response.stream!!.source().buffer(), options.fileSystem),
            null,
            DataSource.NETWORK
        )
    }

    /**
     * The client is resolved lazily through [clientProvider] at request time, because the
     * SubsonicAPIClient is regenerated whenever the active server changes.
     */
    class Factory(private val clientProvider: () -> SubsonicAPIClient) :
        Fetcher.Factory<AvatarRequest> {
        override fun create(
            data: AvatarRequest,
            options: Options,
            imageLoader: coil3.ImageLoader
        ): Fetcher = AvatarFetcher(data, options, clientProvider())
    }
}
