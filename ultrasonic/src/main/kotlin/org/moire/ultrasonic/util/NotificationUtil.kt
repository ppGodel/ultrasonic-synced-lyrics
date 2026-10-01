/*
 * NotificationUtil.kt
 * Copyright (C) 2009-2023 Ultrasonic developers
 *
 * Distributed under terms of the GNU GPLv3 license.
 */

package org.moire.ultrasonic.util

import android.Manifest.permission.POST_NOTIFICATIONS
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.Service.STOP_FOREGROUND_REMOVE
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import org.moire.ultrasonic.R
import org.moire.ultrasonic.activity.NavigationActivity
import org.moire.ultrasonic.app.UApp.Companion.applicationContext

/**
 * Contains utility functions for posting notifications and asking
 * for the notification permission.
 */
object NotificationUtil {

    fun ensureNotificationChannel(
        id: String,
        name: String,
        importance: Int? = null,
        notificationManager: NotificationManagerCompat
    ) {
        // The suggested importance of a startForeground service notification is IMPORTANCE_LOW
        val channel = NotificationChannel(
            id,
            name,
            importance ?: NotificationManager.IMPORTANCE_DEFAULT
        )

        channel.lightColor = android.R.color.holo_blue_dark
        channel.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        channel.setShowBadge(false)

        notificationManager.createNotificationChannel(channel)
    }

    fun ensurePermissionToPostNotification(
        fragment: ComponentActivity,
        onGranted: (() -> Unit)? = null
    ) {
        if (ContextCompat.checkSelfPermission(
                applicationContext(),
                POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        ) {
            val requestPermissionLauncher =
                fragment.registerForActivityResult(ActivityResultContracts.RequestPermission()) {
                    if (!it) {
                        UiUtil.toast(R.string.notification_permission_required, context = fragment)
                    }
                }

            requestPermissionLauncher.launch(POST_NOTIFICATIONS)
        } else {
            // Execute the closure
            if (onGranted != null) {
                onGranted()
            }
        }
    }

    fun postNotificationIfPermitted(
        notificationManagerCompat: NotificationManagerCompat,
        id: Int,
        notification: Notification
    ) {
        if (ContextCompat.checkSelfPermission(
                applicationContext(),
                POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        ) {
            notificationManagerCompat.notify(id, notification)
        }
    }

    fun getPendingIntentToShowPlayer(context: Context): PendingIntent {
        val intent = Intent(context, NavigationActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        // needed starting Android 12 (S = 31)
        flags = flags or PendingIntent.FLAG_IMMUTABLE
        intent.putExtra(Constants.INTENT_SHOW_PLAYER, true)
        return PendingIntent.getActivity(context, 0, intent, flags)
    }

    fun Service.stopForegroundRemoveNotification() {
        stopForeground(STOP_FOREGROUND_REMOVE)
    }
}
