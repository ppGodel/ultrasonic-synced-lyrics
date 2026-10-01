/*
 * FormatUtil.kt
 * Copyright (C) 2009-2023 Ultrasonic developers
 *
 * Distributed under terms of the GNU GPLv3 license.
 */

package org.moire.ultrasonic.util

import android.text.TextUtils
import java.io.UnsupportedEncodingException
import java.security.MessageDigest
import java.text.DecimalFormat
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import org.moire.ultrasonic.R
import org.moire.ultrasonic.app.UApp.Companion.applicationContext
import org.moire.ultrasonic.domain.Track

private const val LINE_LENGTH = 60
private const val DEGRADE_PRECISION_AFTER = 10
private const val MINUTES_IN_HOUR = 60
private const val KBYTE = 1024

/**
 * Contains utility functions for formatting and encoding values for display or transport.
 */
@Suppress("TooManyFunctions")
object FormatUtil {

    private val GIGA_BYTE_FORMAT = DecimalFormat("0.00 GB")
    private val MEGA_BYTE_FORMAT = DecimalFormat("0.00 MB")
    private val KILO_BYTE_FORMAT = DecimalFormat("0 KB")

    // Used by hexEncode()
    private val HEX_DIGITS =
        charArrayOf('0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f')

    /**
     * Converts a byte-count to a formatted string suitable for display to the user.
     * For instance:
     *
     *  * `format(918)` returns *"918 B"*.
     *  * `format(98765)` returns *"96 KB"*.
     *  * `format(1238476)` returns *"1.2 MB"*.
     *
     * This method assumes that 1 KB is 1024 bytes.
     * To get a localized string, please use formatLocalizedBytes instead.
     *
     * @param byteCount The number of bytes.
     * @return The formatted string.
     */
    @JvmStatic
    @Synchronized
    fun formatBytes(byteCount: Long): String {
        // More than 1 GB?
        if (byteCount >= KBYTE * KBYTE * KBYTE) {
            return GIGA_BYTE_FORMAT.format(byteCount.toDouble() / (KBYTE * KBYTE * KBYTE))
        }

        // More than 1 MB?
        if (byteCount >= KBYTE * KBYTE) {
            return MEGA_BYTE_FORMAT.format(byteCount.toDouble() / (KBYTE * KBYTE))
        }

        // More than 1 KB?
        return if (byteCount >= KBYTE) {
            KILO_BYTE_FORMAT.format(byteCount.toDouble() / KBYTE)
        } else {
            "$byteCount B"
        }
    }

    @JvmOverloads
    fun formatTotalDuration(totalDuration: Long?, inMilliseconds: Boolean = false): String {
        if (totalDuration == null) return ""
        var millis = totalDuration
        if (!inMilliseconds) {
            millis = totalDuration * 1000
        }
        val hours = TimeUnit.MILLISECONDS.toHours(millis)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(millis) - TimeUnit.HOURS.toMinutes(hours)
        val seconds = TimeUnit.MILLISECONDS.toSeconds(millis) -
            TimeUnit.MINUTES.toSeconds(hours * MINUTES_IN_HOUR + minutes)

        return when {
            hours >= DEGRADE_PRECISION_AFTER -> {
                String.format(
                    Locale.getDefault(),
                    "%02d:%02d:%02d",
                    hours,
                    minutes,
                    seconds
                )
            }

            hours > 0 -> {
                String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
            }

            minutes >= DEGRADE_PRECISION_AFTER -> {
                String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
            }

            minutes > 0 -> String.format(
                Locale.getDefault(),
                "%d:%02d",
                minutes,
                seconds
            )

            else -> String.format(Locale.getDefault(), "0:%02d", seconds)
        }
    }

    data class ReadableEntryDescription(
        var artist: String,
        var title: String,
        val trackNumber: String,
        val duration: String,
        var bitrate: String?,
        var fileFormat: String?
    )

    @Suppress("ComplexMethod", "LongMethod")
    fun readableEntryDescription(song: Track): ReadableEntryDescription {
        val artist = StringBuilder(LINE_LENGTH)
        var bitRate: String? = null
        var trackText = ""

        val duration = song.duration

        if (song.bitRate != null && song.bitRate!! > 0) {
            bitRate = String.format(
                applicationContext().getString(R.string.song_details_kbps),
                song.bitRate
            )
        }

        val fileFormat: String?
        val suffix = song.suffix
        val transcodedSuffix = song.transcodedSuffix

        fileFormat = if (
            TextUtils.isEmpty(transcodedSuffix) || transcodedSuffix == suffix || song.isVideo
        ) {
            suffix
        } else {
            String.format(Locale.ROOT, "%s > %s", suffix, transcodedSuffix)
        }

        val artistName = song.artist

        if (artistName != null) {
            if (Settings.shouldDisplayBitrateWithArtist && (
                    !bitRate.isNullOrBlank() || !fileFormat.isNullOrBlank()
                    )
            ) {
                artist.append(artistName).append(" (").append(
                    String.format(
                        applicationContext().getString(R.string.song_details_all),
                        if (bitRate == null) {
                            ""
                        } else {
                            String.format(Locale.ROOT, "%s ", bitRate)
                        },
                        fileFormat
                    )
                ).append(')')
            } else {
                artist.append(artistName)
            }
        }

        val trackNumber = song.track ?: 0

        val title = StringBuilder(LINE_LENGTH)
        if (Settings.shouldShowTrackNumber && trackNumber > 0) {
            trackText = String.format(Locale.ROOT, "%02d.", trackNumber)
        }

        title.append(song.title)

        if (song.isVideo && Settings.shouldDisplayBitrateWithArtist) {
            title.append(" (").append(
                String.format(
                    applicationContext().getString(R.string.song_details_all),
                    if (bitRate == null) {
                        ""
                    } else {
                        String.format(Locale.ROOT, "%s ", bitRate)
                    },
                    fileFormat
                )
            ).append(')')
        }

        return ReadableEntryDescription(
            artist = artist.toString(),
            title = title.toString(),
            trackNumber = trackText,
            duration = formatTotalDuration(duration?.toLong()),
            bitrate = bitRate,
            fileFormat = fileFormat
        )
    }

    @Suppress("SuspiciousEqualsCombination")
    fun equals(object1: Any?, object2: Any?): Boolean = object1 === object2 ||
        (!(object1 == null || object2 == null) && object1 == object2)

    /**
     * Encodes the given string by using the hexadecimal representation of its UTF-8 bytes.
     *
     * @param s The string to encode.
     * @return The encoded string.
     */
    @Suppress("TooGenericExceptionThrown")
    fun utf8HexEncode(s: String?): String? {
        if (s == null) {
            return null
        }
        val utf8: ByteArray = try {
            s.toByteArray(charset(Constants.UTF_8))
        } catch (all: UnsupportedEncodingException) {
            // TODO: Why is it needed to change the exception type here?
            throw RuntimeException(all)
        }
        return hexEncode(utf8)
    }

    /**
     * Converts an array of bytes into an array of characters representing the hexadecimal values of each byte in order.
     * The returned array will be double the length of the passed array, as it takes two characters to represent any
     * given byte.
     *
     * @param data Bytes to convert to hexadecimal characters.
     * @return A string containing hexadecimal characters.
     */
    @Suppress("MagicNumber")
    private fun hexEncode(data: ByteArray): String {
        val length = data.size
        val out = CharArray(length shl 1)
        var j = 0

        // two characters form the hex value.
        for (aData in data) {
            out[j++] = HEX_DIGITS[0xF0 and aData.toInt() ushr 4]
            out[j++] = HEX_DIGITS[0x0F and aData.toInt()]
        }
        return String(out)
    }

    /**
     * Calculates the MD5 digest and returns the value as a 32 character hex string.
     *
     * @param s Data to digest.
     * @return MD5 digest as a hex string.
     */
    @JvmStatic
    @Suppress("TooGenericExceptionThrown")
    fun md5Hex(s: String?): String? = if (s == null) {
        null
    } else {
        try {
            val md5 = MessageDigest.getInstance("MD5")
            hexEncode(md5.digest(s.toByteArray(charset(Constants.UTF_8))))
        } catch (all: Exception) {
            // TODO: Why is it needed to change the exception type here?
            throw RuntimeException(all.message, all)
        }
    }

    @JvmStatic
    fun getGrandparent(path: String?): String? {
        // Find the top level folder, assume it is the album artist
        if (path != null) {
            val slashIndex = path.indexOf('/')
            if (slashIndex > 0) {
                return path.substring(0, slashIndex)
            }
        }
        return null
    }

    @JvmStatic
    fun isNullOrWhiteSpace(string: String?): Boolean =
        string.isNullOrEmpty() || string.trim().isEmpty()

    /**
     * Removes diacritics (~= accents) from a string. The case will not be altered.
     * Note that ligatures will be left as is.
     *
     * @param input String to be stripped
     * @return input text with diacritics removed
     *
     */
    fun stripAccents(input: String): String {
        val decomposed: java.lang.StringBuilder =
            java.lang.StringBuilder(Normalizer.normalize(input, Normalizer.Form.NFD))
        convertRemainingAccentCharacters(decomposed)
        return STRIP_ACCENTS_PATTERN.matcher(decomposed).replaceAll("")
    }

    /**
     * Pattern used in [.stripAccents].
     */
    private val STRIP_ACCENTS_PATTERN: Pattern =
        Pattern.compile("\\p{InCombiningDiacriticalMarks}+") // $NON-NLS-1$

    private fun convertRemainingAccentCharacters(decomposed: java.lang.StringBuilder) {
        for (i in decomposed.indices) {
            if (decomposed[i] == '\u0141') {
                decomposed.setCharAt(i, 'L')
            } else if (decomposed[i] == '\u0142') {
                decomposed.setCharAt(i, 'l')
            }
        }
    }
}
