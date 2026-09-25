package com.midiaindoor.tvplayer

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import java.io.File

/**
 * Coleta informações do dispositivo pra reportar no painel (cartão "Dispositivo"
 * da tela de detalhe): modelo, processador, versão do Android, se está
 * rooteado, versão do app e uso de memória.
 */
object DeviceInfo {

    fun modelo(): String = "${Build.MANUFACTURER} ${Build.MODEL}".trim()

    fun processador(): String {
        val soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else null
        return (soc ?: Build.HARDWARE ?: Build.BOARD ?: "desconhecido")
    }

    fun versaoAndroid(): String = Build.VERSION.RELEASE ?: Build.VERSION.SDK_INT.toString()

    fun versaoApp(): String = BuildConfig.VERSION_NAME

    /** Checagem simples e não invasiva: procura pelo binário "su" nos caminhos usuais. */
    fun isRooteado(): Boolean {
        val caminhos = arrayOf(
            "/system/app/Superuser.apk", "/sbin/su", "/system/bin/su", "/system/xbin/su",
            "/data/local/xbin/su", "/data/local/bin/su", "/system/sd/xbin/su",
            "/system/bin/failsafe/su", "/data/local/su", "/su/bin/su"
        )
        return caminhos.any { File(it).exists() }
    }

    /** Memória RAM em uso pelo sistema (total - disponível), em MB. */
    fun usoMemoriaMb(context: Context): Double {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val info = ActivityManager.MemoryInfo()
            am.getMemoryInfo(info)
            val usada = info.totalMem - info.availMem
            usada / (1024.0 * 1024.0)
        } catch (e: Exception) {
            0.0
        }
    }
}
