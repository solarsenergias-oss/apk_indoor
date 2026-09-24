# Mídia Indoor Player (Android TV Box)

App nativo Android (Kotlin) que roda em qualquer TV Box Android, sem usar
navegador: baixa as mídias cadastradas no painel Mídia Indoor, guarda
localmente (funciona mesmo se a internet cair) e reproduz em loop,
tela cheia, registrando proof-of-play no servidor a cada exibição.

## O que ele faz

- Busca a lista de mídias em `GET /api/public/midias` (rota pública do
  servidor, sem precisar de login).
- Baixa cada imagem/vídeo uma vez e guarda no armazenamento interno do
  aparelho (`offline-first` — se a internet cair, continua tocando o que
  já baixou).
- Reproduz em loop: imagens ficam 10s na tela, vídeos tocam até o fim e
  passam pro próximo.
- Depois de cada exibição, avisa o servidor em `POST /api/exibicoes`
  (mesmo endpoint que o painel já usa para "proof-of-play").
- A cada 5 minutos, verifica se a lista de mídias mudou no painel.
- Abre sozinho quando a TV Box liga (`BOOT_COMPLETED`).
- Aparece na tela inicial de Android TV normal (categoria `LEANBACK_LAUNCHER`).

## O que ainda não faz (limitações conhecidas)

- Mídias do tipo **YouTube**, **Link externo** e **Mídia programática** não
  são baixadas nem tocadas pelo player nativo (elas dependem de um
  navegador/embed, e você pediu pra não usar navegador). Só **Vídeo/imagem**
  enviado por upload é reproduzido aqui.
- Não tem tela de login — a tela precisa já existir, criada antes no painel
  (`Minhas Telas`), e você informa o **ID dessa tela** na configuração do app.

## Como compilar o APK

Duas formas — escolha uma:

### Opção A — GitHub Actions (sem instalar nada no PC)

Este projeto já vem com um workflow pronto em
`.github/workflows/build-apk.yml` que compila o APK na nuvem a cada push.

1. Crie um repositório novo e **vazio** no GitHub (ex: `midia-indoor-player`).
2. No Git Bash, dentro da pasta extraída deste zip:
   ```bash
   git init
   git add .
   git commit -m "Primeira versao do player Android"
   git branch -M main
   git remote add origin https://github.com/SEU_USUARIO/midia-indoor-player.git
   git push -u origin main
   ```
3. No GitHub, abra a aba **Actions** do repositório — o build começa sozinho.
   Aguarde o ícone ficar verde (uns 3-5 minutos).
4. Clique no build concluído → na seção **Artifacts**, baixe
   `midia-indoor-player-apk` (vem como `.zip`; dentro dele está o
   `app-debug.apk`).
5. Copie esse `.apk` pra um pendrive e instale na TV Box (habilite "Fontes
   desconhecidas" nas configurações dela, se pedir).

### Opção B — Android Studio (no seu computador)

1. Baixe e instale o **Android Studio** (gratuito, developer.android.com/studio).
2. Extraia este zip em uma pasta no seu computador.
3. Abra o Android Studio → **File → Open** → selecione a pasta extraída
   (a que tem o arquivo `settings.gradle` dentro).
4. Aguarde o **Gradle Sync** terminar (primeira vez demora, baixa tudo
   automaticamente).
5. Menu **Build → Build Bundle(s) / APK(s) → Build APK(s)**.
6. Quando terminar, clique em **"locate"** no aviso que aparece — o arquivo
   `app-debug.apk` estará em `app/build/outputs/apk/debug/`.
7. Copie esse `.apk` pra um pendrive (ou envie por link) e instale na
   TV Box (habilite "Fontes desconhecidas" nas configurações do Android
   da TV Box, se pedir).

## Como configurar cada TV Box depois de instalado

1. Abra o app "Mídia Indoor Player" na TV Box.
2. Toque e segure em qualquer lugar da tela (ou clique e segure com o
   controle remoto) — abre a caixa de configuração.
3. Preencha:
   - **URL do servidor**: `https://midia-indoor-dtlj.onrender.com`
   - **ID da tela**: o número da tela cadastrada em "Minhas Telas" no painel
     (você pode ver o ID abrindo o F12/inspecionar no painel, ou eu adiciono
     o ID visível na listagem se precisar).
4. Toque em "Salvar e iniciar".

Pronto — a partir daí ele já busca e toca as mídias sozinho.
