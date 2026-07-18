---
translation_status: ai-translated
canonical_source: /guide/troubleshooting
---

# Solução de problemas

Comece pelo sintoma que você consegue ver. Isso separa rapidamente problemas
do Android, da rede ou do host do Hermes.

- [Ponto vermelho ou sem conexão](#sem-conexão)
- [“No reachable endpoint”](#nenhum-endpoint-acessível)
- [Chat não transmite a resposta](#chat-não-transmite)
- [Manage ou Voice pede login](#manage-e-voice)
- [Sessões ausentes](#sessões-ausentes)
- [O aplicativo falha ao iniciar](#falha-ao-iniciar)

## Sem conexão

1. Confirme no host que `hermes gateway` está em execução.
2. Abra `http://<host>:8642/health` pelo **celular**.
3. Confira `API_SERVER_ENABLED=true` e as regras do firewall.
4. Não use `localhost` nem `127.0.0.1` no celular; esses endereços apontam para o próprio aparelho.

## Nenhum endpoint acessível

O aplicativo testou todas as rotas salvas — LAN, Tailscale e públicas — sem
receber resposta. Um endereço LAN funciona somente na mesma rede Wi-Fi. Para
Tailscale, o celular e o servidor precisam estar conectados.

## Chat não transmite

- Confira a URL e a chave de API.
- Se aparecer um erro, toque em **Retry** uma vez.
- Verifique os logs do servidor Hermes.
- Modelos locais podem levar vários minutos; se o Android interromper a conexão em segundo plano, a resposta concluída será recuperada ao reconectar.

## Manage e Voice

Manage e a voz padrão usam a sessão do dashboard, não `API_SERVER_KEY`. Faça
login uma vez pelo Manage e confirme que o dashboard está acessível pelo celular.

## Sessões ausentes

O servidor precisa estar acessível durante a troca de sessão. Sessões grandes
podem levar alguns instantes; aguarde o indicador de carregamento.

## Falha ao iniciar

Abra **Configurações do Android → Aplicativos → Hermes-Relay → Armazenamento** e
limpe os dados do aplicativo. Depois configure novamente a URL e a chave.

Para problemas do Relay, `hermes relay doctor` fornece um diagnóstico somente leitura.

[Solução completa em inglês →](/guide/troubleshooting) ·
[Instalação →](/pt-BR/guide/getting-started)
