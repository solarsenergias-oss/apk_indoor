package com.midiaindoor.tvplayer

/**
 * Representa uma mídia recebida da API pública do servidor
 * (GET /api/public/midias).
 */
data class Midia(
    val id: Int,
    val nome: String,
    val tipo: String,      // "imagem" | "video" | "youtube" | "link" | "programatica"
    val url: String?,
    val orientacao: String?,
    val status: String?
) {
    /** Tipos que o player nativo consegue baixar e reproduzir localmente. */
    fun isReproduzivelLocalmente(): Boolean =
        (tipo == "imagem" || tipo == "video") && !url.isNullOrBlank()

    fun isVideo(): Boolean {
        if (!url.isNullOrBlank()) {
            val u = url.lowercase()
            if (u.endsWith(".mp4") || u.endsWith(".webm") || u.endsWith(".mkv") || u.endsWith(".mov")) return true
        }
        return tipo == "video"
    }
}
