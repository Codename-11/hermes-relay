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

O Android usa normalmente o Dashboard/Gateway do Hermes em `:9119`. Ele fornece
Chat, sessões, login, Manage e voz padrão. Inicie-o com `hermes dashboard` e
deixe esse endereço acessível pelo celular.

O servidor de API em `:8642` é opcional: funciona como fallback automático do
Chat ou para compatibilidade headless avançada. Uma chave de API só é necessária
quando esse endpoint opcional é configurado. O operador do servidor cria
`API_SERVER_KEY`; o Dashboard não fornece essa chave.

::: warning Proteja o acesso pela rede
Não exponha diretamente uma porta de Dashboard, API ou Relay sem criptografia à Internet;
para acesso remoto, use Tailscale, VPN ou HTTPS.
:::

O login do dashboard usa cookies e tickets temporários do Gateway. A chave de
API é separada e não autentica no dashboard.

## 3. Conecte e converse

1. Abra **Connect** no Android.
2. Procure o Hermes na LAN, informe a URL do Dashboard/Gateway ou escaneie um QR; QRs API-first antigos continuam compatíveis.
3. Entre no dashboard quando solicitado.
4. Toque em **Connect** e confirme **Chat · Ready**.
5. Adicione API fallback, Relay ou rotas remotas depois em **Advanced**, se necessário.

Você pode adicionar e testar um endereço Dashboard do Tailscale como
`http://100.x.y.z:9119`, ou um endereço `.ts.net` publicado separadamente, sem configurar o servidor de API nem uma chave de API.

A mesma sessão libera Chat, sessões, Manage e Voice. Relay sem pareamento e API
fallback indisponível são estados normais.

## Opcional: adicione as ferramentas do Relay

Instale o plugin somente para terminal, Device Control, mídia, notificações ou
ferramentas remotas avançadas. Os comandos canônicos são
`hermes plugins install Codename-11/hermes-relay/plugin --enable`,
`hermes relay doctor`, `hermes relay start --no-ssl` e `hermes pair`.

Device Control precisa **dos dois**: aplicativo Sideload e Relay pareado.

[Comparar versões →](/pt-BR/guide/release-tracks) ·
[Acesso remoto em inglês →](/guide/remote-access) ·
[Solução de problemas →](/pt-BR/guide/troubleshooting)
