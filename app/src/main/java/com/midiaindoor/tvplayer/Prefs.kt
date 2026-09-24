package com.midiaindoor.tvplayer

import android.content.Context

/**
 * Guarda a configuração do dispositivo: URL do servidor e ID da tela
 * (a tela precisa já existir, cadastrada em "Minhas Telas" no painel admin).
 */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("midia_indoor_prefs", Context.MODE_PRIVATE)

    var serverUrl: String
        get() = sp.getString("server_url", "") ?: ""
        set(value) = sp.edit().putString("server_url", value.trimEnd('/')).apply()

    var telaId: Int
        get() = sp.getInt("tela_id", -1)
        set(value) = sp.edit().putInt("tela_id", value).apply()

    fun isConfigured(): Boolean = serverUrl.isNotBlank() && telaId > 0
}
