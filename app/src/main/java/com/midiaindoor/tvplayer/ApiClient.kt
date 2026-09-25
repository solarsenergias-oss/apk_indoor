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
                        status = o.optString("status", null),
                        duracaoSegundos = if (o.optInt("duracao_segundos", 10) > 0) o.optInt("duracao_segundos", 10) else 10
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

    /** Login de pareamento: mesmas credenciais do painel web. Se válidas, devolve a
        lista de telas cadastradas em "Minhas Telas" pra escolher qual é este aparelho. */
    fun loginPareamento(email: String, senha: String): List<Terminal> {
        val json = JSONObject().apply {
            put("email", email)
            put("senha", senha)
        }
        val body = json.toString().toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url("$baseUrl/api/tv/login").post(body).build()
        client.newCall(req).execute().use { resp ->
            val texto = resp.body?.string() ?: "{}"
            if (!resp.isSuccessful) {
                val msg = try { JSONObject(texto).optString("error", "Falha no login") } catch (e: Exception) { "Falha no login (HTTP ${resp.code})" }
                throw RuntimeException(msg)
            }
            val obj = JSONObject(texto)
            val arr = obj.getJSONArray("telas")
            val lista = mutableListOf<Terminal>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                lista.add(
                    Terminal(
                        id = o.getInt("id"),
                        nome = o.optString("nome", "Tela ${o.getInt("id")}"),
                        endereco = if (o.isNull("endereco")) null else o.optString("endereco", null),
                        orientacao = o.optString("orientacao", "Horizontal"),
                        cicloAtualizacaoMin = if (o.isNull("ciclo_atualizacao_min")) null else o.optString("ciclo_atualizacao_min", null),
                        totalMidias = o.optInt("total_midias", 0)
                    )
                )
            }
            return lista
        }
    }

    /** Avisa o painel que este dispositivo está vivo + dados dele (cartão "Dispositivo"). */
    fun enviarHeartbeat(
        telaId: Int, modelo: String, processador: String, versaoAndroid: String,
        rooteado: Boolean, versaoApp: String, usoMemoriaMb: Double,
        midiasBaixadasTotal: Int, midiasBaixadasOk: Int
    ) {
        try {
            val json = JSONObject().apply {
                put("modelo", modelo)
                put("processador", processador)
                put("versao_android", versaoAndroid)
                put("rooteado", rooteado)
                put("versao_app", versaoApp)
                put("uso_memoria_mb", usoMemoriaMb)
                put("midias_baixadas_total", midiasBaixadasTotal)
                put("midias_baixadas_ok", midiasBaixadasOk)
            }
            val body = json.toString().toRequestBody("application/json".toMediaType())
            val req = Request.Builder().url("$baseUrl/api/public/telas/$telaId/heartbeat").post(body).build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) Log.w("ApiClient", "Falha no heartbeat: HTTP ${resp.code}")
            }
        } catch (e: Exception) {
            Log.w("ApiClient", "Não foi possível enviar heartbeat (offline?): ${e.message}")
        }
    }

    /** Busca comandos remotos pendentes enviados pelo painel (reiniciar app, atualizar mídias, etc). */
    fun buscarComandosPendentes(telaId: Int): List<Pair<Int, String>> {
        return try {
            val req = Request.Builder().url("$baseUrl/api/public/telas/$telaId/comandos").get().build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val arr = JSONArray(resp.body?.string() ?: "[]")
                val lista = mutableListOf<Pair<Int, String>>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    lista.add(o.getInt("id") to o.getString("comando"))
                }
                lista
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Avisa o painel que um comando remoto terminou de ser executado. */
    fun concluirComando(telaId: Int, comandoId: Int) {
        try {
            val req = Request.Builder()
                .url("$baseUrl/api/public/telas/$telaId/comandos/$comandoId/concluir")
                .post(ByteArray(0).toRequestBody(null))
                .build()
            client.newCall(req).execute().close()
        } catch (e: Exception) {
            Log.w("ApiClient", "Não foi possível confirmar comando: ${e.message}")
        }
    }
}
