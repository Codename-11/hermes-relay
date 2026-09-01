---
translation_status: ai-translated
canonical_source: /guide/quick-start
---

# Início rápido

Comece pela conexão padrão upstream. Depois, pareie o Relay quando quiser as
ferramentas e os aprimoramentos adicionais que ele oferece.

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
Essa é toda a configuração do servidor necessária para Chat, sessões, Manage,
voz e arquivos recebidos padrão.

## 3. Adicione a conexão padrão {#other-supported-paths}

No Android, abra **Connect**. Use **Find Hermes on LAN** ou informe manualmente
o endereço do Dashboard, normalmente `http://<host>:9119`. Entre quando
solicitado. Isso cria uma conexão padrão completa sem plugin nem URL do Relay.

## 4. Opcional: instale e pareie o Relay

Para a experiência completa recomendada, instale o Relay depois que a conexão
padrão estiver funcionando:

```bash
hermes plugins install Codename-11/hermes-relay/plugin --enable
hermes relay doctor
hermes relay start --no-ssl
```

Use `--no-ssl` somente em uma LAN ou VPN confiável. Para acesso fora de casa,
[o Tailscale é recomendado](/guide/remote-access).

Depois, abra **Relay → Pair new device** no Web Dashboard e leia o QR de uso
único em **Settings → Connections → Pair Hermes Relay**.

O servidor de API continua sendo um fallback opcional. Relay não é obrigatório
para upstream, mas é recomendado para Terminal/TUI, notificações, ferramentas
de desktop, voz avançada, sessões Relay, Device Control e compatibilidade ou
metadados de mídia. Arquivos recebidos comuns usam as rotas atuais do Dashboard.

## 5. Confira o status

- **Chat · Ready** significa que você já pode enviar mensagens.
- **Manage** pode pedir login no dashboard.
- **Voice** é liberado pela mesma sessão do dashboard.
- **API fallback** pode ficar indisponível sem bloquear o Chat.
- **Relay · Paired** confirma as extensões recomendadas; uma falha do Relay não
  deve bloquear o caminho upstream padrão.

## 6. Envie a primeira mensagem

Abra o Chat e envie uma mensagem. O indicador verde no cabeçalho confirma que a
conexão ativa com o Hermes está disponível.

[Instalação detalhada →](/pt-BR/guide/getting-started) ·
[Solução de problemas →](/pt-BR/guide/troubleshooting) ·
[Guia canônico em inglês →](/guide/quick-start)
