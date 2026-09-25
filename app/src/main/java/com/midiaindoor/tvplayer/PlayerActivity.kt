package com.midiaindoor.tvplayer

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.midiaindoor.tvplayer.databinding.ActivityPlayerBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Tela única do player: fica em loop trocando entre imagens e vídeos baixados
 * do painel VizzoPlay. Sem navegador — cada mídia é baixada e guardada
 * localmente (offline-first) e reproduzida nativamente.
 *
 * O pareamento (login + escolha da tela) acontece na PairingActivity, antes
 * desta tela abrir. Pra trocar de terminal depois, toque e segure em
 * qualquer lugar da tela do player.
 */
class PlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlayerBinding
    private lateinit var prefs: Prefs
    private lateinit var cache: MediaCache
    private var apiClient: ApiClient? = null

    private lateinit var exoPlayer: ExoPlayer
    private val mainHandler = Handler(Looper.getMainLooper())
    private val bg: ExecutorService = Executors.newSingleThreadExecutor()

    private var playlist: List<Midia> = emptyList()
    private var currentIndex = 0
    private var tocandoAgora = false
    private var totalMidiasVinculadas = 0
    private var totalMidiasBaixadas = 0

    private val clockFormat = SimpleDateFormat("HH:mm", Locale("pt", "BR"))
    private val clockRunnable = object : Runnable {
        override fun run() {
            binding.clockText.text = clockFormat.format(Date())
            mainHandler.postDelayed(this, 30_000)
        }
    }

    private val refreshRunnable = Runnable { carregarMidias() }
    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            enviarHeartbeat()
            mainHandler.postDelayed(this, HEARTBEAT_INTERVAL_MS)
        }
    }
    private val comandosRunnable = object : Runnable {
        override fun run() {
            verificarComandosPendentes()
            mainHandler.postDelayed(this, COMANDOS_INTERVAL_MS)
        }
    }

    companion object {
        private const val TAG = "PlayerActivity"
        private const val REFRESH_INTERVAL_MS = 5 * 60_000L // recarrega a playlist a cada 5 min quando está tudo ok
        private const val RETRY_INTERVAL_MS = 30_000L // tenta de novo bem mais rápido quando falhou (ex: servidor "acordando")
        private const val HEARTBEAT_INTERVAL_MS = 60_000L // avisa o painel que está "vivo" a cada 1 min
        private const val COMANDOS_INTERVAL_MS = 30_000L // verifica comandos remotos (reiniciar, atualizar) a cada 30s
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        esconderBarraDeSistema()

        prefs = Prefs(this)
        cache = MediaCache(this)
        exoPlayer = ExoPlayer.Builder(this).build()
        binding.playerView.player = exoPlayer

        exoPlayer.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) proximaMidia()
            }
            override fun onPlayerError(error: PlaybackException) {
                Log.w(TAG, "Erro no player: ${error.message}")
                proximaMidia()
            }
        })

        binding.root.setOnLongClickListener { trocarTerminal(); true }

        mainHandler.post(clockRunnable)

        if (prefs.isConfigured()) {
            iniciar()
        } else {
            // Não deveria acontecer (a PairingActivity só abre esta tela depois de
            // pareado), mas por segurança volta pro pareamento se não houver configuração.
            trocarTerminal()
        }
    }

    /** Toque e segure na tela: limpa a vinculação atual e volta pro pareamento
        (login + escolha de terminal), pra trocar qual tela este aparelho representa. */
    private fun trocarTerminal() {
        prefs.telaId = -1
        startActivity(Intent(this, PairingActivity::class.java))
        finish()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) esconderBarraDeSistema()
    }

    private fun esconderBarraDeSistema() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
            )
    }

    private fun iniciar() {
        apiClient = ApiClient(prefs.serverUrl)
        binding.statusText.text = getString(R.string.status_loading)
        mainHandler.removeCallbacks(refreshRunnable)
        mainHandler.post(refreshRunnable)

        mainHandler.removeCallbacks(heartbeatRunnable)
        mainHandler.postDelayed(heartbeatRunnable, 5_000L)
        mainHandler.removeCallbacks(comandosRunnable)
        mainHandler.postDelayed(comandosRunnable, 10_000L)
    }

    /** Reporta ao painel que a tela está online + dados do dispositivo
        (aparece no cartão "Dispositivo" da página de detalhe da tela). */
    private fun enviarHeartbeat() {
        val client = apiClient ?: return
        val telaId = prefs.telaId
        bg.execute {
            client.enviarHeartbeat(
                telaId = telaId,
                modelo = DeviceInfo.modelo(),
                processador = DeviceInfo.processador(),
                versaoAndroid = DeviceInfo.versaoAndroid(),
                rooteado = DeviceInfo.isRooteado(),
                versaoApp = DeviceInfo.versaoApp(),
                usoMemoriaMb = DeviceInfo.usoMemoriaMb(applicationContext),
                midiasBaixadasTotal = totalMidiasVinculadas,
                midiasBaixadasOk = totalMidiasBaixadas
            )
        }
    }

    /** Busca comandos remotos enviados pelo painel ("Enviar comando") e executa. */
    private fun verificarComandosPendentes() {
        val client = apiClient ?: return
        val telaId = prefs.telaId
        bg.execute {
            val pendentes = client.buscarComandosPendentes(telaId)
            pendentes.forEach { (comandoId, comando) ->
                Log.i(TAG, "Executando comando remoto: $comando")
                when (comando) {
                    "atualizar_midias" -> mainHandler.post {
                        mainHandler.removeCallbacks(refreshRunnable)
                        mainHandler.post(refreshRunnable)
                    }
                    "reiniciar_app" -> mainHandler.post { recreate() }
                    "reiniciar_dispositivo" -> tentarReiniciarDispositivo()
                }
                client.concluirComando(telaId, comandoId)
            }
        }
    }

    /** Reinício de dispositivo exige root ou permissão de sistema — tenta via
        "su" quando o TV Box está rooteado; sem root, não há como forçar isso
        de dentro de um app comum, então apenas registra no log. */
    private fun tentarReiniciarDispositivo() {
        if (!DeviceInfo.isRooteado()) {
            Log.w(TAG, "Comando 'reiniciar dispositivo' recebido, mas o aparelho não está rooteado — ignorado.")
            return
        }
        try {
            Runtime.getRuntime().exec(arrayOf("su", "-c", "reboot"))
        } catch (e: Exception) {
            Log.w(TAG, "Falha ao tentar reiniciar via root: ${e.message}")
        }
    }

    /** Busca a lista de mídias no servidor e baixa pro cache local (roda em background). */
    private fun carregarMidias() {
        val client = apiClient ?: return
        bg.execute {
            try {
                val midias = client.fetchMidias(prefs.telaId).filter { it.isReproduzivelLocalmente() }
                midias.forEach { midia ->
                    if (!cache.estaEmCache(midia)) {
                        try {
                            val bytes = client.baixarArquivo(midia.url!!)
                            cache.salvar(midia, bytes)
                            Log.i(TAG, "Mídia baixada: ${midia.nome}")
                        } catch (e: Exception) {
                            Log.w(TAG, "Falha ao baixar '${midia.nome}': ${e.message}")
                        }
                    }
                }
                cache.limparOrfaos(midias)

                val prontas = midias.filter { cache.estaEmCache(it) }
                totalMidiasVinculadas = midias.size
                totalMidiasBaixadas = prontas.size
                mainHandler.post {
                    val listaMudou = prontas.map { it.id } != playlist.map { it.id }
                    playlist = prontas
                    if (playlist.isEmpty()) {
                        binding.statusText.text = getString(R.string.status_empty)
                    } else {
                        binding.statusText.text = ""
                        if (!tocandoAgora || listaMudou) {
                            currentIndex = 0
                            tocandoAgora = true
                            reproduzirAtual()
                        }
                    }
                    mainHandler.removeCallbacks(refreshRunnable)
                    mainHandler.postDelayed(refreshRunnable, REFRESH_INTERVAL_MS)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Falha ao atualizar playlist (offline?): ${e.message}")
                mainHandler.post {
                    if (playlist.isEmpty()) {
                        binding.statusText.text = getString(R.string.status_offline)
                    } else if (!tocandoAgora) {
                        // Já tínhamos mídias em cache de antes — toca offline mesmo assim.
                        tocandoAgora = true
                        reproduzirAtual()
                    }
                    // Tenta de novo rapidinho (o servidor pode só estar "acordando").
                    mainHandler.removeCallbacks(refreshRunnable)
                    mainHandler.postDelayed(refreshRunnable, RETRY_INTERVAL_MS)
                }
            }
        }
    }

    private fun reproduzirAtual() {
        if (playlist.isEmpty()) return
        val midia = playlist[currentIndex % playlist.size]
        val arquivo = cache.caminhoLocal(midia)
        if (!arquivo.exists()) { proximaMidia(); return }

        bg.execute { apiClient?.reportarExibicao(prefs.telaId, midia.id) }

        if (midia.isVideo()) {
            binding.imageView.visibility = View.GONE
            binding.playerView.visibility = View.VISIBLE
            val item = MediaItem.fromUri(android.net.Uri.fromFile(arquivo))
            exoPlayer.setMediaItem(item)
            exoPlayer.prepare()
            exoPlayer.playWhenReady = true
        } else {
            exoPlayer.pause()
            binding.playerView.visibility = View.GONE
            bg.execute {
                val bmp = BitmapFactory.decodeFile(arquivo.absolutePath)
                mainHandler.post {
                    binding.imageView.setImageBitmap(bmp)
                    binding.imageView.visibility = View.VISIBLE
                }
            }
            mainHandler.postDelayed({ proximaMidia() }, midia.duracaoSegundos * 1000L)
        }
    }

    private fun proximaMidia() {
        if (playlist.isEmpty()) return
        currentIndex = (currentIndex + 1) % playlist.size
        reproduzirAtual()
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacks(heartbeatRunnable)
        mainHandler.removeCallbacks(comandosRunnable)
        mainHandler.removeCallbacksAndMessages(null)
        bg.shutdownNow()
        exoPlayer.release()
    }
}
