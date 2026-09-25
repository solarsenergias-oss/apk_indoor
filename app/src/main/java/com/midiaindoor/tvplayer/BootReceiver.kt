package com.midiaindoor.tvplayer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Faz o app abrir sozinho quando a TV Box liga — assim não depende
 * de alguém abrir manualmente depois de uma queda de energia. Abre pela
 * PairingActivity, que decide: se já está pareado, vai direto pro player;
 * senão, mostra a tela de login/escolha de terminal.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val launch = Intent(context, PairingActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            context.startActivity(launch)
        }
    }
}
