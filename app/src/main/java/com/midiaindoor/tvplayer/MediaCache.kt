package com.midiaindoor.tvplayer

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Cache local de arquivos de mídia, para o player funcionar mesmo se a internet
 * cair (offline-first): baixa uma vez, guarda em disco, e só baixa de novo se
 * a mídia mudar de URL no servidor.
 */
class MediaCache(context: Context) {
    private val dir: File = File(context.filesDir, "midias").apply { mkdirs() }

    private fun arquivoFor(midia: Midia): File {
        val ext = midia.url?.substringAfterLast('.', "bin")?.take(4) ?: "bin"
        return File(dir, "midia_${midia.id}.$ext")
    }

    fun caminhoLocal(midia: Midia): File = arquivoFor(midia)

    fun estaEmCache(midia: Midia): Boolean = arquivoFor(midia).exists() && arquivoFor(midia).length() > 0

    fun salvar(midia: Midia, bytes: ByteArray) {
        arquivoFor(midia).writeBytes(bytes)
    }

    /** Remove do disco arquivos de mídias que não existem mais na lista atual do servidor. */
    fun limparOrfaos(midiasAtuais: List<Midia>) {
        val idsValidos = midiasAtuais.map { it.id }.toSet()
        dir.listFiles()?.forEach { f ->
            val id = f.name.removePrefix("midia_").substringBefore('.').toIntOrNull()
            if (id != null && id !in idsValidos) {
                Log.i("MediaCache", "Removendo mídia órfã do cache: ${f.name}")
                f.delete()
            }
        }
    }
}
