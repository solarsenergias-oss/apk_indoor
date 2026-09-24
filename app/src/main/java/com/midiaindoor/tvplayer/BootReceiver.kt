package com.midiaindoor.tvplayer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Faz o player abrir sozinho quando a TV Box liga — assim não depende
 * de alguém abrir o app manualmente depois de uma queda de energia.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val launch = Intent(context, PlayerActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            context.startActivity(launch)
        }
    }
}
