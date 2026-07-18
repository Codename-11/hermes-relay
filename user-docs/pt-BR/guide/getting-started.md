---
translation_status: ai-translated
canonical_source: /guide/getting-started
---

# Instalação e configuração

Três passos: instale o aplicativo, conecte ao Hermes e envie a primeira mensagem.
Se o Hermes já estiver funcionando, não é preciso instalar nada extra no servidor.

::: tip Status da tradução
Este guia resumido cobre o caminho mais comum. Opções avançadas de servidor,
TLS e operação estão no [guia completo em inglês](/guide/getting-started).
:::

## 1. Escolha o aplicativo

| | Google Play | Sideload |
|---|---|---|
| Recomendado para | A maioria dos usuários | Usuários de Device Control |
| Atualizações | Automáticas | Atualização manual do APK |
| Chat, Voice e Manage | Incluídos | Incluídos |
| Terminal, mídia e notificações com Relay | Incluídos | Incluídos |
| Ler a tela, tocar, digitar e navegar | Não incluído | Incluído |

<StoreBadge />

O arquivo assinado de Sideload termina em `-sideload-release.apk` e está em
[GitHub Releases](https://github.com/Codename-11/hermes-relay/releases). Não
baixe o arquivo `.aab`; ele é destinado ao Google Play.

## 2. Deixe o Hermes acessível

O Android precisa do servidor de API do Hermes, normalmente em `:8642`:

- `API_SERVER_ENABLED=true` ativa o servidor.
- `API_SERVER_HOST=0.0.0.0` permite acesso pela rede.
- `API_SERVER_KEY` protege as solicitações de Chat com uma chave bearer.
- `hermes gateway` inicia o Hermes e o servidor de API ativado.

::: warning Proteja o acesso pela rede
`0.0.0.0` permite que outros dispositivos na rede alcancem o serviço. Use uma
chave forte. Não exponha diretamente uma porta sem criptografia à Internet;
para acesso remoto, use Tailscale, VPN ou HTTPS.
:::

O dashboard em `:9119` é opcional. Ele é usado pelo Manage e pela voz padrão e
possui login próprio; a chave de API não autentica no dashboard.

## 3. Conecte e converse

1. Abra **Connect** no Android.
2. Procure o Hermes na LAN, escaneie um QR de configuração ou informe a URL e a chave.
3. Toque em **Connect**.
4. Confirme que **Chat · Ready** aparece.
5. Abra o Chat e envie a primeira mensagem.

Manage e Voice ainda podem solicitar login. Também é normal que o Relay apareça sem pareamento.

## Opcional: adicione as ferramentas do Relay

Instale o plugin somente para terminal, Device Control, mídia, notificações ou
ferramentas remotas avançadas. Os comandos canônicos são
`hermes plugins install Codename-11/hermes-relay/plugin --enable`,
`hermes relay doctor`, `hermes relay start --no-ssl` e `hermes pair`.

Device Control precisa **dos dois**: aplicativo Sideload e Relay pareado.

[Comparar versões →](/pt-BR/guide/release-tracks) ·
[Acesso remoto em inglês →](/guide/remote-access) ·
[Solução de problemas →](/pt-BR/guide/troubleshooting)
