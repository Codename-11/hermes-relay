---
translation_status: ai-translated
canonical_source: /guide/quick-start
---

# Início rápido

Instale → conecte → converse, em cerca de dois minutos. Este caminho funciona
com um Hermes Agent comum; o plugin Relay não é necessário.

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

O servidor de API do Hermes precisa estar ativo e acessível pelo celular. Se
necessário, inicie o host com `hermes gateway`. Consulte
[Instalação e configuração](/pt-BR/guide/getting-started) para preparar o servidor.

## 3. Conecte

Abra o aplicativo e vá até **Connect**. Você pode:

1. Usar **Scan for Hermes on LAN** para localizar o servidor na rede local.
2. Informar um endereço como `http://<host>:8642` e a chave de API configurada.
3. Escanear um QR de configuração que contenha a URL e a chave.

Se o host estiver intencionalmente sem `API_SERVER_KEY`, deixe a chave vazia.

## 4. Confira o status

- **Chat · Ready** significa que você já pode enviar mensagens.
- **Manage** pode pedir login no dashboard.
- **Voice** é liberado pela mesma sessão do dashboard.
- **Relay** pode continuar sem pareamento e não bloqueia o caminho padrão.

## 5. Envie a primeira mensagem

Abra o Chat e envie uma mensagem. O indicador verde no cabeçalho confirma que a
conexão ativa com o Hermes está disponível.

[Instalação detalhada →](/pt-BR/guide/getting-started) ·
[Solução de problemas →](/pt-BR/guide/troubleshooting) ·
[Guia canônico em inglês →](/guide/quick-start)
