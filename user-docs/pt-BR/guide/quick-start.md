---
translation_status: ai-translated
canonical_source: /guide/quick-start
---

# Início rápido

Instale → conecte → converse. O caminho padrão continua upstream; o plugin
Relay é recomendado para a experiência completa do Hermes-Relay.

<AndroidSetupPath mode="quick" />

::: tip Status da tradução
Esta página foi traduzida com assistência de IA e passou pelas verificações
técnicas. O inglês continua sendo a fonte canônica do significado do produto e da segurança.
:::

## 1. Instale o aplicativo

Para a maioria das pessoas, o **Google Play** é o caminho mais rápido: instalação
com um toque e atualizações automáticas.

<StoreBadge />

Se você quer que o Hermes leia a tela, toque, digite ou navegue pelo celular,
instale o APK assinado de **Sideload**. As duas versões podem ficar instaladas ao mesmo tempo.

## 2. Inicie o Hermes

O Dashboard/Gateway do Hermes precisa estar ativo e acessível pelo celular. Se
necessário, inicie-o com `hermes dashboard`. Consulte
[Instalação e configuração](/pt-BR/guide/getting-started) para preparar o servidor.

Para o caminho completo recomendado, instale também:

```bash
hermes plugins install Codename-11/hermes-relay/plugin --enable
hermes relay doctor
hermes relay start --no-ssl
```

Use `--no-ssl` somente em uma LAN ou VPN confiável. Para acesso fora de casa,
[o Tailscale é recomendado](/guide/remote-access).

## 3. Conecte {#other-supported-paths}

Abra o aplicativo e vá até **Connect**. Você pode:

1. No Web Dashboard, abra **Relay → Connect mobile app** e leia o QR sem
   credenciais em Android **Connect → Scan Hermes setup QR**.
2. Depois abra **Relay → Pair new device** e leia o QR de uso único em
   **Settings → Connections → Pair Hermes Relay**.
3. Sem o plugin do Dashboard, use **Find Hermes on LAN** ou informe manualmente
   o endereço como `http://<host>:9119`.
4. Sem câmera, `hermes pair` gera o mesmo QR e um convite copiável; URL e código
   continuam disponíveis como fallback manual.
5. Entre com o provedor do dashboard quando solicitado.

O servidor de API continua sendo um fallback opcional. Relay não é obrigatório
para upstream, mas é recomendado para Terminal/TUI, notificações, mídia,
ferramentas de desktop, voz avançada e Device Control.

## 4. Confira o status

- **Chat · Ready** significa que você já pode enviar mensagens.
- **Manage** pode pedir login no dashboard.
- **Voice** é liberado pela mesma sessão do dashboard.
- **Direct API** pode ficar indisponível sem bloquear o Chat.
- **Relay · Paired** confirma as extensões recomendadas; uma falha do Relay não
  deve bloquear o caminho upstream padrão.

## 5. Envie a primeira mensagem

Abra o Chat e envie uma mensagem. O indicador verde no cabeçalho confirma que a
conexão ativa com o Hermes está disponível.

[Instalação detalhada →](/pt-BR/guide/getting-started) ·
[Solução de problemas →](/pt-BR/guide/troubleshooting) ·
[Guia canônico em inglês →](/guide/quick-start)
