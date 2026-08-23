package com.wristcord.mobile.util

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast

/**
 * Failures always reach the phone screen too, not just the watch. Toasts must be posted to the
 * main looper - the Data Layer delivers messages on a binder thread.
 */
object Toasts {
    private val main = Handler(Looper.getMainLooper())

    fun show(ctx: Context, text: String, long: Boolean = true) {
        main.post {
            Toast.makeText(
                ctx.applicationContext,
                text,
                if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT,
            ).show()
        }
    }
}
