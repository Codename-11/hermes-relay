---
translation_status: ai-translated
canonical_source: /guide/getting-started
---

# Instalação e configuração

Esta é a referência detalhada para escolher a versão, conectar manualmente,
configurar acesso remoto e revisar a segurança. Se o Hermes Dashboard já estiver
acessível, use o [Início rápido](./quick-start).

<AndroidSetupPath mode="reference" />

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

O login do dashboard usa um bearer nativo em gateways atuais ou cookies da
origem exata em gateways de compatibilidade, junto com tickets temporários do
Gateway. A chave de API é separada.

A rota salva no Android pode ser um endereço LAN, Tailscale ou público. O OIDC
ainda precisa de um callback HTTPS acessível em
`<origem-publica-do-dashboard>/auth/callback`. O Hermes normalmente deriva essa
origem de cabeçalhos confiáveis do proxy. Configure upstream
`dashboard.public_url` / `HERMES_DASHBOARD_PUBLIC_URL` somente se essa detecção
não for confiável; o Android verifica uma origem de login diferente antes de salvá-la.

## 3. Conecte e converse

1. Abra **Connect** no Android.
2. Procure o Hermes na LAN, informe a URL do Dashboard/Gateway ou escaneie um QR; QRs API-first antigos continuam compatíveis.
3. Entre no dashboard quando solicitado.
4. Toque em **Connect** e confirme **Chat · Ready**.
5. Em **Concluir configuração**, ative as notificações do Android se quiser alertas do chat em segundo plano. Câmera, microfone e os demais recursos continuam opcionais e são configurados individualmente; toque em **Agora não** para seguir direto.
6. Adicione API fallback, Relay ou rotas remotas depois em **Advanced**, se necessário.

`hermes-relay-tailscale enable` publica `https://host.ts.net:10443` em uma porta
dedicada do tailnet e encaminha para o Dashboard local `:9119`, junto com a rota Relay
da mesma origem. Uma rota deliberadamente direta como
`http://100.x.y.z:9119` também funciona, mas não tem TLS de aplicação.

A mesma sessão libera Chat, sessões, Manage e Voice. Relay sem pareamento e API
fallback indisponível são estados normais.

## Recomendado: complete a configuração com Relay {#relay-server-optional}

O caminho upstream continua funcionando sem plugin. O Relay é recomendado para
Terminal/TUI, notificações, mídia, ferramentas de desktop, voz avançada,
sessões Relay e Device Control opcional. Os comandos canônicos são
`hermes plugins install Codename-11/hermes-relay/plugin --enable`,
`hermes relay doctor`, `hermes relay start --no-ssl` e `hermes pair`.

No Web Dashboard, abra **Relay**, use primeiro **Connect mobile app** e depois
**Pair new device**, lendo os dois QRs com o Android. Sem QR, continuam
disponíveis URL/código e `hermes pair --register-code`.

Device Control precisa **dos dois**: aplicativo Sideload e Relay pareado.

[Comparar versões →](/pt-BR/guide/release-tracks) ·
[Acesso remoto em inglês →](/guide/remote-access) ·
[Solução de problemas →](/pt-BR/guide/troubleshooting)
