# TikTok Sticker Cache

App Android que usa **Shizuku** para acessar, sem root, somente o cache de stickers do TikTok em:

```text
/storage/emulated/0/Android/data/com.zhiliaoapp.musically/cache/picture/fresco_custom_cache/stable_sticker
```

A busca é recursiva: o app coleta os arquivos `.cnt` da pasta `stable_sticker` e de qualquer subpasta existente dentro dela. O TikTok/Fresco normalmente salva o conteúdo com extensão `.cnt`; o app lê a assinatura binária real, identifica o formato e mostra uma grade com preview.

## Recursos

- Integração com Shizuku por `UserService` (UID shell/root).
- Busca recursiva de todos os arquivos `.cnt` dentro de `stable_sticker`.
- Deduplicação por SHA-256 do conteúdo real da mídia, ignorando cabeçalhos extras do cache.
- Quando existem cópias repetidas, mantém somente o arquivo mais recente.
- Cache do índice para não recalcular todos os hashes quando os arquivos não mudaram.
- Detecção por magic bytes, mesmo quando existe um pequeno cabeçalho antes da mídia.
- Preview de PNG, JPEG, GIF, WebP, AVIF/HEIC compatíveis com o aparelho.
- Miniatura e reprodução em loop de MP4, WebM, 3GPP, AVI, Ogg e FLV compatíveis com o aparelho.
- Ordenação pelos arquivos mais recentes.
- Paginação de metadados para não estourar o limite do Binder.
- Streaming por `ParcelFileDescriptor`; o arquivo inteiro não passa pela transação Binder.
- Cópias temporárias ficam apenas no cache privado deste app.

## Como usar

1. Instale e inicie o Shizuku. Em Android 11 ou superior, ele pode ser iniciado pela **Depuração sem fio**.
2. Abra alguns stickers no TikTok para preencher o cache.
3. Abra este app e toque em **Conceder acesso**.
4. Autorize no Shizuku e toque em **Atualizar**.
5. Toque em qualquer item para abrir o preview maior.

O Shizuku iniciado por ADB usa UID `shell` (2000). Alguns fabricantes podem aplicar restrições adicionais; nesse caso, a tela mostrará que a pasta não pôde ser lida.

## Formatos detectados

PNG, JPEG, GIF, WebP (inclusive animado), AVIF, HEIF/HEIC, MP4, WebM/Matroska, 3GPP, AVI, Ogg, FLV, JSON/Lottie, PDF e SQLite. Formatos não renderizáveis ainda aparecem identificados na lista.

## Build

O workflow `.github/workflows/android.yml` compila automaticamente em pushes e pull requests. O APK fica em:

**Actions → Build Android APK → Artifacts → Stickers-debug-apk**

Build local, com Java 17, Android SDK 35 e Gradle 8.9:

```bash
gradle assembleDebug
```

Saída:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Privacidade e segurança

O serviço aceita somente arquivos cujo caminho canônico esteja dentro de `stable_sticker`. O app não envia arquivos para a internet e não solicita permissões comuns de armazenamento.

## Aviso

Este projeto não é afiliado ao TikTok, ByteDance ou Shizuku. Use apenas em seu próprio aparelho e respeite os direitos dos criadores das mídias.
