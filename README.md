# Oasis Autoplay

Protótipo Android do autoplay externo para Oasis.

## Estado atual — v0.1

- Aplicativo Android independente.
- Tela inicial com acesso direto às configurações de Acessibilidade.
- `AccessibilityService` configurado para receber eventos e executar gestos.
- GitHub Actions configurado para gerar `app-debug.apk` automaticamente.
- O motor visual/decisório do jogo ainda será incorporado em etapas posteriores.

## Gerar APK

Abra a aba **Actions** do repositório, escolha **Build Android APK** e execute **Run workflow**.
Ao concluir, baixe o artefato **Oasis-Autoplay-debug**.

## Observação

O aplicativo não modifica o APK original do Oasis. Use automação apenas onde ela seja permitida pelas regras do jogo/serviço.
