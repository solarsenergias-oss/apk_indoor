package com.midiaindoor.tvplayer

/** Uma "tela" cadastrada no painel (Minhas Telas), retornada no login de pareamento do app. */
data class Terminal(
    val id: Int,
    val nome: String,
    val endereco: String?,
    val orientacao: String,
    val cicloAtualizacaoMin: String?,
    val totalMidias: Int
)
