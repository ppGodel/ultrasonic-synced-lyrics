/*
 * CommunicationErrorReportingTest.kt
 * Copyright (C) 2009-2026 Ultrasonic developers
 *
 * Distributed under terms of the GNU GPLv3 license.
 */

package org.moire.ultrasonic.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.IOException
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.reset
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.moire.ultrasonic.app.UApp
import org.moire.ultrasonic.data.ActiveServerProvider
import org.robolectric.RobolectricTestRunner

/**
 * Verifies that [CommunicationError.handleError], the single funnel for communication
 * errors, hands the raw exception over to [ActiveServerProvider.reportNetworkFailure]
 * unchanged and regardless of the presence of a UI context.
 *
 * Whether a bare IOException is classified as a transport failure (and thus switches the
 * library to the local catalog) is asserted in TransportFailureClassifierTest: local file
 * I/O must not trigger automatic offline mode.
 */
// Robolectric is required because mocking ActiveServerProvider initializes its companion
// object, which reads Android settings and resources.
@RunWith(RobolectricTestRunner::class)
class CommunicationErrorReportingTest {

    // The mock must be created lazily after UApp.instance is set, because mocking
    // ActiveServerProvider triggers its companion object initialization, which reads
    // Android settings.
    companion object {
        private val activeServerProvider: ActiveServerProvider by lazy { mock() }
    }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appContext: Context = mock()
        whenever(appContext.getString(any())).thenAnswer { it.arguments[0].toString() }
        whenever(appContext.applicationContext).thenReturn(appContext)
        whenever(appContext.packageName).thenReturn(context.packageName)
        whenever(appContext.getSharedPreferences(any(), any())).thenAnswer {
            context.getSharedPreferences(it.arguments[0] as String, it.arguments[1] as Int)
        }
        val app: UApp = mock()
        whenever(app.applicationContext).thenReturn(appContext)
        UApp.instance = app

        reset(activeServerProvider)
    }

    @After
    fun tearDown() {
        UApp.instance = null
    }

    @Test
    fun `handleError reports the error to the provider even without a UI context`() {
        val error = IOException("Connection reset by peer")

        CommunicationError(activeServerProvider).handleError(error, null)

        verify(activeServerProvider).reportNetworkFailure(error)
    }

    @Test
    fun `handleError reports local file IO errors to the provider untouched`() {
        val error = IOException("No space left on device")

        CommunicationError(activeServerProvider).handleError(error, null)

        verify(activeServerProvider).reportNetworkFailure(error)
    }
}
