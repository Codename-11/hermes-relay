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

Android usa normalmente el Dashboard/Gateway de Hermes en `:9119`. Proporciona
Chat, sesiones, inicio de sesión, Manage y voz estándar. Inícialo con
`hermes dashboard` y haz que esa dirección sea accesible desde el teléfono.

El servidor de API en `:8642` es opcional: sirve como fallback automático de
Chat o para compatibilidad headless avanzada. Solo necesitas una clave de API
si configuras ese endpoint opcional. El operador del servidor crea
`API_SERVER_KEY`; el Dashboard no proporciona esa clave.

::: warning Protege el acceso de red
No expongas directamente a Internet un puerto de Dashboard, API o Relay sin
cifrar; para el acceso remoto utiliza Tailscale, una VPN o HTTPS.
:::

El inicio de sesión del dashboard usa cookies y tickets efímeros del Gateway.
La clave de API es independiente y no inicia sesión en el dashboard.

## 3. Conecta y conversa

1. Abre **Connect** en Android.
2. Busca Hermes en la LAN, introduce la URL del Dashboard/Gateway o escanea un QR; los QR API-first antiguos siguen siendo compatibles.
3. Inicia sesión en el dashboard cuando se solicite.
4. Pulsa **Connect** y comprueba **Chat · Ready**.
5. Añade API fallback, Relay o rutas remotas después desde **Advanced** si lo necesitas.

Puedes añadir y probar una dirección Dashboard de Tailscale como
`http://100.x.y.z:9119`, o una dirección `.ts.net` publicada por separado, sin configurar el servidor API ni una clave API.

La misma sesión habilita Chat, sesiones, Manage y Voice. Es normal que Relay
esté sin emparejar y que API fallback no esté disponible.

## Opcional: añade las herramientas de Relay

Instala el complemento solo para terminal, Device Control, multimedia,
notificaciones o herramientas remotas avanzadas. Los comandos canónicos son
`hermes plugins install Codename-11/hermes-relay/plugin --enable`,
`hermes relay doctor`, `hermes relay start --no-ssl` y `hermes pair`.

Device Control necesita **las dos cosas**: la aplicación Sideload y un Relay emparejado.

[Comparar versiones →](/es/guide/release-tracks) ·
[Acceso remoto en inglés →](/guide/remote-access) ·
[Solución de problemas →](/es/guide/troubleshooting)
