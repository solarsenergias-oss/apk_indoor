package com.midiaindoor.tvplayer

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Fala com as rotas PÚBLICAS do backend (não exigem login):
 *   GET  /api/public/midias
 *   POST /api/exibicoes
 * Essas rotas já existem no servidor Mídia Indoor sem necessidade de token.
 */
class ApiClient(private val baseUrl: String) {

    // Timeouts generosos: o servidor (Render free tier) "dorme" quando fica sem uso
    // e pode levar até ~50s pra acordar na primeira requisição depois disso.
    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /** Busca a lista de mídias vinculadas a esta tela no painel ("Vincular telas"). */
    fun fetchMidias(telaId: Int): List<Midia> {
        val req = Request.Builder().url("$baseUrl/api/public/midias?tela_id=$telaId").get().build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}")
            val body = resp.body?.string() ?: "[]"
            val arr = JSONArray(body)
            val lista = mutableListOf<Midia>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                lista.add(
                    Midia(
                        id = o.getInt("id"),
                        nome = o.optString("nome", ""),
                        tipo = o.optString("tipo", "imagem"),
                        url = o.optString("url", null),
                        orientacao = o.optString("orientacao", null),
                        status = o.optString("status", null)
                    )
                )
            }
            return lista.filter { it.status != "inativo" }
        }
    }

    /** Registra proof-of-play: avisa o servidor que esta tela exibiu esta mídia agora. */
    fun reportarExibicao(telaId: Int, midiaId: Int) {
        try {
            val json = JSONObject().apply {
                put("tela_id", telaId)
                put("midia_id", midiaId)
            }
            val body = json.toString().toRequestBody("application/json".toMediaType())
            val req = Request.Builder().url("$baseUrl/api/exibicoes").post(body).build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) Log.w("ApiClient", "Falha ao registrar exibição: HTTP ${resp.code}")
            }
        } catch (e: Exception) {
            // Offline-first: se não conseguir avisar o servidor, apenas segue reproduzindo.
            Log.w("ApiClient", "Não foi possível registrar exibição (offline?): ${e.message}")
        }
    }

    /** Baixa um arquivo de mídia (imagem/vídeo) como bytes. URL pode ser relativa ou absoluta. */
    fun baixarArquivo(url: String): ByteArray {
        val fullUrl = if (url.startsWith("http")) url else "$baseUrl$url"
        val req = Request.Builder().url(fullUrl).get().build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}")
            return resp.body?.bytes() ?: ByteArray(0)
        }
    }
}
