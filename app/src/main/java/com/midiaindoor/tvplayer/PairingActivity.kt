package com.midiaindoor.tvplayer

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.Executors

/**
 * Tela de pareamento do TV Box, no estilo "faça login e escolha o terminal"
 * (como o Yeloo Player): usa o MESMO usuário/senha do painel web pra listar
 * as telas cadastradas em "Minhas Telas" e vincular este aparelho a uma delas.
 *
 * Depois de pareado uma vez, o app abre direto no player (PlayerActivity) —
 * pra trocar de tela depois, basta tocar e segurar na tela do player.
 */
class PairingActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private val mainHandler = Handler(Looper.getMainLooper())
    private val bg = Executors.newSingleThreadExecutor()

    private var todosOsTerminais: List<Terminal> = emptyList()
    private var terminalSelecionado: Terminal? = null
    private var serverUrlAtual: String = ""

    private lateinit var pairingError: TextView
    private lateinit var pairingProgress: ProgressBar
    private lateinit var step1Login: View
    private lateinit var step2Select: View
    private lateinit var step3Confirm: View
    private lateinit var listTerminais: ListView
    private lateinit var adapter: ArrayAdapter<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)

        // Já pareado antes — vai direto pro player, sem passar pela tela de login.
        if (prefs.isConfigured()) {
            abrirPlayer()
            return
        }

        setContentView(R.layout.activity_pairing)

        pairingError = findViewById(R.id.pairingError)
        pairingProgress = findViewById(R.id.pairingProgress)
        step1Login = findViewById(R.id.step1Login)
        step2Select = findViewById(R.id.step2Select)
        step3Confirm = findViewById(R.id.step3Confirm)
        listTerminais = findViewById(R.id.listTerminais)

        val inputServerUrl = findViewById<EditText>(R.id.inputServerUrl)
        val inputEmail = findViewById<EditText>(R.id.inputEmail)
        val inputSenha = findViewById<EditText>(R.id.inputSenha)
        val inputBusca = findViewById<EditText>(R.id.inputBuscaTerminal)

        // Se já tinha uma URL de servidor salva de uma tentativa anterior, pré-preenche.
        if (prefs.serverUrl.isNotBlank()) inputServerUrl.setText(prefs.serverUrl)

        findViewById<Button>(R.id.btnLoginContinuar).setOnClickListener {
            val url = inputServerUrl.text.toString().trim().trimEnd('/')
            val email = inputEmail.text.toString().trim()
            val senha = inputSenha.text.toString()
            if (url.isBlank() || email.isBlank() || senha.isBlank()) {
                mostrarErro("Preencha a URL do servidor, o e-mail e a senha.")
                return@setOnClickListener
            }
            fazerLogin(url, email, senha)
        }

        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf())
        listTerminais.adapter = adapter
        listTerminais.setOnItemClickListener { _, _, position, _ ->
            val nomeExibido = adapter.getItem(position)
            val terminal = todosOsTerminais.find { "${it.nome}${if (it.endereco != null) " — ${it.endereco}" else ""}" == nomeExibido }
            if (terminal != null) selecionarTerminal(terminal)
        }

        inputBusca.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { filtrarTerminais(s?.toString() ?: "") }
            override fun afterTextChanged(s: Editable?) {}
        })

        findViewById<Button>(R.id.btnVoltarLista).setOnClickListener {
            step3Confirm.visibility = View.GONE
            step2Select.visibility = View.VISIBLE
        }

        findViewById<Button>(R.id.btnConfirmarTerminal).setOnClickListener {
            val terminal = terminalSelecionado ?: return@setOnClickListener
            prefs.serverUrl = serverUrlAtual
            prefs.telaId = terminal.id
            abrirPlayer()
        }
    }

    private fun mostrarErro(msg: String) {
        pairingError.text = msg
        pairingError.visibility = View.VISIBLE
    }

    private fun esconderErro() {
        pairingError.visibility = View.GONE
    }

    private fun fazerLogin(url: String, email: String, senha: String) {
        esconderErro()
        pairingProgress.visibility = View.VISIBLE
        step1Login.visibility = View.GONE
        val client = ApiClient(url)
        bg.execute {
            try {
                val terminais = client.loginPareamento(email, senha)
                mainHandler.post {
                    pairingProgress.visibility = View.GONE
                    serverUrlAtual = url
                    todosOsTerminais = terminais
                    exibirListaTerminais(terminais)
                    step2Select.visibility = View.VISIBLE
                }
            } catch (e: Exception) {
                mainHandler.post {
                    pairingProgress.visibility = View.GONE
                    step1Login.visibility = View.VISIBLE
                    mostrarErro(e.message ?: "Não foi possível conectar ao servidor.")
                }
            }
        }
    }

    private fun exibirListaTerminais(terminais: List<Terminal>) {
        adapter.clear()
        adapter.addAll(terminais.map { "${it.nome}${if (it.endereco != null) " — ${it.endereco}" else ""}" })
        adapter.notifyDataSetChanged()
    }

    private fun filtrarTerminais(consulta: String) {
        val filtrados = if (consulta.isBlank()) todosOsTerminais
        else todosOsTerminais.filter { it.nome.contains(consulta, ignoreCase = true) }
        exibirListaTerminais(filtrados)
    }

    private fun selecionarTerminal(terminal: Terminal) {
        terminalSelecionado = terminal
        findViewById<TextView>(R.id.confirmNome).text = terminal.nome
        findViewById<TextView>(R.id.confirmEndereco).text = terminal.endereco ?: "Não informado"
        findViewById<TextView>(R.id.confirmOrientacao).text = terminal.orientacao
        findViewById<TextView>(R.id.confirmCiclo).text =
            if (terminal.cicloAtualizacaoMin != null) "${terminal.cicloAtualizacaoMin} minutos" else "Padrão"
        findViewById<TextView>(R.id.confirmTotalMidias).text = "${terminal.totalMidias} mídias"

        step2Select.visibility = View.GONE
        step3Confirm.visibility = View.VISIBLE
    }

    private fun abrirPlayer() {
        startActivity(Intent(this, PlayerActivity::class.java))
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        bg.shutdownNow()
    }
}
