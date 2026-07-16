---
translation_status: ai-translated
canonical_source: /guide/getting-started
---

# Instalación y configuración

Tres pasos: instala la aplicación, conéctala a Hermes y envía el primer mensaje.
Si Hermes ya está funcionando, no necesitas instalar nada adicional en el servidor.

::: tip Estado de la traducción
Esta guía resumida cubre la ruta habitual. Las opciones avanzadas de servidor,
TLS y operación están en la [guía completa en inglés](/guide/getting-started).
:::

## 1. Elige la aplicación

| | Google Play | Sideload |
|---|---|---|
| Recomendado para | La mayoría de usuarios | Usuarios de Device Control |
| Actualizaciones | Automáticas | Actualización manual del APK |
| Chat, Voice y Manage | Incluidos | Incluidos |
| Terminal, multimedia y notificaciones con Relay | Incluidos | Incluidos |
| Leer la pantalla, tocar, escribir y navegar | No incluido | Incluido |

<StoreBadge />

El archivo firmado de Sideload termina en `-sideload-release.apk` y se publica
en [GitHub Releases](https://github.com/Codename-11/hermes-relay/releases). No
descargues el archivo `.aab`; está destinado a Google Play.

## 2. Haz que Hermes sea accesible

Android necesita el servidor de API de Hermes, normalmente en `:8642`:

- `API_SERVER_ENABLED=true` activa el servidor.
- `API_SERVER_HOST=0.0.0.0` permite el acceso desde la red.
- `API_SERVER_KEY` protege las solicitudes de Chat con una clave bearer.
- `hermes gateway` inicia Hermes y el servidor de API activado.

::: warning Protege el acceso de red
`0.0.0.0` permite que otros dispositivos de la red lleguen al servicio. Usa una
clave segura. No expongas directamente un puerto sin cifrar a Internet; para el
acceso remoto utiliza Tailscale, una VPN o HTTPS.
:::

El dashboard en `:9119` es opcional. Se utiliza para Manage y la voz estándar,
y tiene su propio inicio de sesión; la clave de API no inicia sesión en el dashboard.

## 3. Conecta y conversa

1. Abre **Connect** en Android.
2. Busca Hermes en la LAN, escanea un QR de configuración o introduce la URL y la clave.
3. Pulsa **Connect**.
4. Comprueba que aparezca **Chat · Ready**.
5. Abre Chat y envía el primer mensaje.

Manage y Voice todavía pueden pedir una sesión. También es normal que Relay
aparezca sin emparejar.

## Opcional: añade las herramientas de Relay

Instala el complemento solo para terminal, Device Control, multimedia,
notificaciones o herramientas remotas avanzadas. Los comandos canónicos son
`hermes plugins install Codename-11/hermes-relay/plugin --enable`,
`hermes relay doctor`, `hermes relay start --no-ssl` y `hermes pair`.

Device Control necesita **las dos cosas**: la aplicación Sideload y un Relay emparejado.

[Comparar versiones →](/es/guide/release-tracks) ·
[Acceso remoto en inglés →](/guide/remote-access) ·
[Solución de problemas →](/es/guide/troubleshooting)
