/*
 * UiUtil.kt
 * Copyright (C) 2009-2023 Ultrasonic developers
 *
 * Distributed under terms of the GNU GPLv3 license.
 */

package org.moire.ultrasonic.util

import android.app.Activity
import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.annotation.AnyRes
import androidx.annotation.AttrRes
import androidx.annotation.ColorInt
import androidx.annotation.StringRes
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import kotlin.math.roundToInt
import org.moire.ultrasonic.R
import org.moire.ultrasonic.app.UApp.Companion.applicationContext
import timber.log.Timber

/**
 * Contains utility functions for user interface concerns: theming, toasts,
 * keyboard handling and navigation.
 */
object UiUtil {

    @JvmStatic
    fun applyTheme(context: Context?) {
        if (context == null) return
        val style = getStyleFromSettings(context)
        // First set the theme (light, dark, etc.)
        context.setTheme(style)
        // Then set an overlay controlling the status bar behaviour etc.
        context.setTheme(R.style.UltrasonicTheme_Base)
    }

    private fun getStyleFromSettings(context: Context): Int = when (Settings.theme.lowercase()) {
        context.getString(R.string.setting_key_theme_dark) -> {
            R.style.UltrasonicTheme_Dark
        }

        context.getString(R.string.setting_key_theme_black) -> {
            R.style.UltrasonicTheme_Black
        }

        context.getString(R.string.setting_key_theme_light) -> {
            R.style.UltrasonicTheme_Light
        }

        else -> {
            R.style.UltrasonicTheme_DayNight
        }
    }

    fun getString(@StringRes resId: Int): String = applicationContext().resources.getString(resId)

    @JvmOverloads
    fun toast(messageId: Int, shortDuration: Boolean = true, context: Context?) {
        toast(applicationContext().getString(messageId), shortDuration, context)
    }

    @JvmStatic
    fun toast(message: CharSequence, context: Context?) {
        toast(message, true, context)
    }

    // Toast needs a real context or it will throw a IllegalAccessException
    // We wrap it in a try-catch block, because if called after doing
    // some background processing, our context might have expired!
    @JvmStatic
    fun toast(message: CharSequence, shortDuration: Boolean, context: Context?) {
        try {
            Toast.makeText(
                context,
                message,
                if (shortDuration) Toast.LENGTH_SHORT else Toast.LENGTH_LONG
            ).show()
        } catch (all: Exception) {
            Timber.w(all)
        }
    }

    fun Fragment.toast(message: CharSequence, shortDuration: Boolean = true) {
        toast(
            message,
            shortDuration = shortDuration,
            context = this.context
        )
    }

    fun Fragment.toast(messageId: Int = 0, shortDuration: Boolean = true) {
        toast(
            messageId = messageId,
            shortDuration = shortDuration,
            context = this.context
        )
    }

    fun hideKeyboard(activity: Activity?) {
        val inputManager =
            activity!!.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val currentFocusedView = activity.currentFocus
        if (currentFocusedView != null) {
            inputManager.hideSoftInputFromWindow(
                currentFocusedView.windowToken,
                0
            )
        }
    }

    fun getUriToDrawable(context: Context, @AnyRes drawableId: Int): Uri = (
        ContentResolver.SCHEME_ANDROID_RESOURCE +
            "://" + context.resources.getResourcePackageName(drawableId) +
            '/' + context.resources.getResourceTypeName(drawableId) +
            '/' + context.resources.getResourceEntryName(drawableId)
        ).toUri()

    fun dpToPx(dp: Int, activity: Activity): Int =
        (dp * (activity.resources.displayMetrics.xdpi / DisplayMetrics.DENSITY_DEFAULT))
            .roundToInt()

    @ColorInt
    fun Context.themeColor(@AttrRes attrRes: Int): Int = TypedValue()
        .apply { theme.resolveAttribute(attrRes, this, true) }
        .data

    fun Fragment.navigateToCurrent() {
        if (Settings.shouldTransitionOnPlayback) {
            findNavController().popBackStack(R.id.playerFragment, true)
            findNavController().navigate(R.id.playerFragment)
        }
    }
}
