package com.bitchat.android.meshup.shell

import android.content.Context
import android.content.Intent

/**
 * Restarts the app process. Used after turning Internet features OFF: the Tor library keeps its
 * client and connections to Tor nodes alive until the process exits (Decision 013 follow-up).
 */
fun restartApp(context: Context) {
    val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
    val component = launch.component ?: return
    context.startActivity(Intent.makeRestartActivityTask(component))
    Runtime.getRuntime().exit(0)
}
