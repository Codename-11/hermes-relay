# Hermes-Relay

**Roda na sua máquina. Acompanha você nos seus dispositivos.**

[English](../../README.md) · [Deutsch](README.de.md) · [Español](README.es.md) · [日本語](README.ja.md) · **Português (Brasil)** · [Русский](README.ru.md) · [简体中文](README.zh-CN.md)

> O inglês é a descrição canônica e completa do projeto. Esta tradução assistida
> por IA é uma introdução compacta e mantida.

O Hermes-Relay leva seu [Hermes Agent](https://github.com/NousResearch/hermes-agent)
para o Android e computadores conectados. O Hermes continua rodando na sua própria
máquina; o Hermes-Relay fornece interfaces nativas e extensões opcionais.

## Recursos principais

- **Android:** chat com streaming, sessões, arquivos recebidos, Manage, voz e Petdex.
- **Conexão padrão:** chat, Manage, sessões, voz e arquivos se conectam diretamente ao Dashboard/Gateway do Hermes sem modificações.
- **Extensão Relay opcional:** Terminal/TUI, notificações, ferramentas de desktop, voz aprimorada, sessões Relay e recursos de mídia.
- **Versão sideload:** adiciona Device Control com confirmação para ler a tela, tocar e navegar.
- **CLI / UI:** conecta computadores diretamente ao Relay e oferece ferramentas de arquivos, terminal, busca e captura de tela com consentimento.

## Início rápido no Android

1. Instale pelo [Google Play](https://play.google.com/store/apps/details?id=com.axiomlabs.hermesrelay) ou baixe o APK sideload assinado na versão [`android-v*` mais recente](https://github.com/Codename-11/hermes-relay/releases).
2. Inicie a superfície padrão na máquina que executa o Hermes:

   ```bash
   hermes dashboard
   ```

3. No Android, abra **Connect**, procure o Hermes na rede local ou informe o endereço do Dashboard e entre. O caminho padrão não exige Relay nem uma chave de API separada.
4. Instale o Relay apenas se quiser os recursos adicionais:

   ```bash
   hermes plugins install Codename-11/hermes-relay/plugin --enable
   hermes relay doctor
   hermes relay start --no-ssl
   ```

   Use `--no-ssl` somente em uma rede local ou VPN confiável. Depois, faça o pareamento em **Relay → Pair new device** no Dashboard.

## Saiba mais

[Início rápido em português](https://hermes-relay.dev/docs/pt-BR/guide/quick-start) ·
[Instalação](https://hermes-relay.dev/docs/pt-BR/guide/getting-started) ·
[Solução de problemas](https://hermes-relay.dev/docs/pt-BR/guide/troubleshooting) ·
[Documentação completa em inglês](https://hermes-relay.dev/docs/)

[Licença MIT](../../LICENSE)
