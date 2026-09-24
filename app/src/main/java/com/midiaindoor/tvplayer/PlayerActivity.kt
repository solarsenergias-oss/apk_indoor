package com.midiaindoor.tvplayer

import android.app.AlertDialog
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
import com.midiaindoor.tvplayer.databinding.DialogConfigBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Tela única do player: fica em loop trocando entre imagens e vídeos baixados
 * do painel Mídia Indoor. Sem navegador — cada mídia é baixada e guardada
 * localmente (offline-first) e reproduzida nativamente.
 *
 * Configuração (URL do servidor + ID da tela): toque e segure em qualquer
 * lugar da tela para abrir o diálogo.
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

    private val clockFormat = SimpleDateFormat("HH:mm", Locale("pt", "BR"))
    private val clockRunnable = object : Runnable {
        override fun run() {
            binding.clockText.text = clockFormat.format(Date())
            mainHandler.postDelayed(this, 30_000)
        }
    }

    private val refreshRunnable = object : Runnable {
        override fun run() {
            carregarMidias()
            mainHandler.postDelayed(this, REFRESH_INTERVAL_MS)
        }
    }

    companion object {
        private const val TAG = "PlayerActivity"
        private const val DURACAO_IMAGEM_MS = 10_000L
        private const val REFRESH_INTERVAL_MS = 5 * 60_000L // recarrega a playlist a cada 5 min
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

        binding.root.setOnLongClickListener { mostrarDialogConfig(); true }

        mainHandler.post(clockRunnable)

        if (prefs.isConfigured()) {
            iniciar()
        } else {
            binding.statusText.text = getString(R.string.status_no_config)
            mostrarDialogConfig()
        }
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

    private fun mostrarDialogConfig() {
        val dlgBinding = DialogConfigBinding.inflate(layoutInflater)
        dlgBinding.inputServerUrl.setText(prefs.serverUrl)
        if (prefs.telaId > 0) dlgBinding.inputTelaId.setText(prefs.telaId.toString())

        AlertDialog.Builder(this)
            .setTitle(R.string.config_title)
            .setView(dlgBinding.root)
            .setPositiveButton(R.string.config_save) { _, _ ->
                val url = dlgBinding.inputServerUrl.text.toString().trim()
                val telaId = dlgBinding.inputTelaId.text.toString().trim().toIntOrNull() ?: -1
                if (url.isNotBlank() && telaId > 0) {
                    prefs.serverUrl = url
                    prefs.telaId = telaId
                    iniciar()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setCancelable(prefs.isConfigured())
            .show()
    }

    private fun iniciar() {
        apiClient = ApiClient(prefs.serverUrl)
        binding.statusText.text = getString(R.string.status_loading)
        mainHandler.removeCallbacks(refreshRunnable)
        mainHandler.post(refreshRunnable)
    }

    /** Busca a lista de mídias no servidor e baixa pro cache local (roda em background). */
    private fun carregarMidias() {
        val client = apiClient ?: return
        bg.execute {
            try {
                val midias = client.fetchMidias().filter { it.isReproduzivelLocalmente() }
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
            mainHandler.postDelayed({ proximaMidia() }, DURACAO_IMAGEM_MS)
        }
    }

    private fun proximaMidia() {
        if (playlist.isEmpty()) return
        currentIndex = (currentIndex + 1) % playlist.size
        reproduzirAtual()
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacksAndMessages(null)
        bg.shutdownNow()
        exoPlayer.release()
    }
}
