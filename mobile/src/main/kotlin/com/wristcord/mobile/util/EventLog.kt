package com.wristcord.mobile.util

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A small in-app ring buffer so the onboarding screen can show what actually happened without
 * the user needing adb. Everything here also goes to logcat.
 */
object EventLog {
    private const val CAPACITY = 120
    private val stamp = SimpleDateFormat("HH:mm:ss", Locale.US)

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries

    data class Entry(val at: String, val level: String, val tag: String, val message: String) {
        override fun toString() = "$at $level $tag  $message"
    }

    fun i(tag: String, message: String) = add("I", tag, message).also { Log.i(tag, message) }
    fun w(tag: String, message: String) = add("W", tag, message).also { Log.w(tag, message) }
    fun e(tag: String, message: String) = add("E", tag, message).also { Log.e(tag, message) }

    private fun add(level: String, tag: String, message: String) {
        val entry = Entry(stamp.format(Date()), level, tag.substringAfterLast('/'), message)
        _entries.value = (_entries.value + entry).takeLast(CAPACITY)
    }

    fun clear() {
        _entries.value = emptyList()
    }
}
