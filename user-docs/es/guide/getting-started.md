---
translation_status: ai-translated
canonical_source: /guide/getting-started
---

# Instalación y configuración

Esta es la referencia detallada para elegir versión, conectar manualmente,
configurar acceso remoto y revisar la seguridad. Si Hermes Dashboard ya es
accesible, usa el [Inicio rápido](./quick-start).

<AndroidSetupPath mode="reference" />

::: tip Estado de la traducción
Esta guía resumida cubre la ruta habitual. Las opciones avanzadas de servidor,
TLS y operación están en la [guía completa en inglés](/guide/getting-started).
:::

## 1. Elige la aplicación

| | Google Play | Sideload |
|---|---|---|
| Recomendado para | La mayoría de usuarios | Usuarios de Device Control |
| Actualizaciones | Automáticas | Actualización manual del APK |
| Chat, Voice, Manage y archivos entrantes | Incluidos | Incluidos |
| Terminal, mejoras multimedia y notificaciones con Relay | Incluidos | Incluidos |
| Leer la pantalla, tocar, escribir y navegar | No incluido | Incluido |

<StoreBadge />

El archivo firmado de Sideload termina en `-sideload-release.apk` y se publica
en [GitHub Releases](https://github.com/Codename-11/hermes-relay/releases). No
descargues el archivo `.aab`; está destinado a Google Play.

## 2. Haz que Hermes sea accesible

Android usa normalmente el Dashboard/Gateway de Hermes en `:9119`. Proporciona
Chat, sesiones, inicio de sesión, Manage, voz estándar y archivos entrantes
autenticados. Inícialo con
`hermes dashboard` y haz que esa dirección sea accesible desde el teléfono.

El servidor de API en `:8642` es opcional: sirve como fallback automático de
Chat o para compatibilidad headless avanzada. Solo necesitas una clave de API
si configuras ese endpoint opcional. El operador del servidor crea
`API_SERVER_KEY`; el Dashboard no proporciona esa clave.

::: warning Protege el acceso de red
No expongas directamente a Internet un puerto de Dashboard, API o Relay sin
cifrar; para el acceso remoto utiliza Tailscale, una VPN o HTTPS.
:::

El inicio de sesión del dashboard usa un bearer nativo en gateways actuales o
cookies del origen exacto en gateways de compatibilidad, junto con tickets
efímeros del Gateway. La clave de API es independiente.

La ruta guardada en Android puede ser una dirección LAN, Tailscale o pública.
OIDC todavía necesita un callback HTTPS accesible en
`<origen-publico-del-dashboard>/auth/callback`. Hermes normalmente obtiene ese
origen de cabeceras de proxy confiables. Configura upstream
`dashboard.public_url` / `HERMES_DASHBOARD_PUBLIC_URL` solo si esa detección no
es fiable; Android verifica un origen de inicio de sesión distinto antes de guardarlo.

## 3. Conecta y conversa

1. Abre **Connect** en Android.
2. Busca Hermes en la LAN, introduce la URL del Dashboard/Gateway o escanea un QR; los QR API-first antiguos siguen siendo compatibles.
3. Inicia sesión en el dashboard cuando se solicite.
4. Pulsa **Connect** y comprueba **Chat · Ready**.
5. En **Finalizar configuración**, activa las notificaciones de Android si quieres alertas de chat en segundo plano. La cámara, el micrófono y las demás funciones siguen siendo opcionales y se configuran una a una; pulsa **Ahora no** para continuar directamente.
6. Añade API fallback, Relay o rutas remotas después desde **Advanced** si lo necesitas.

`hermes-relay-tailscale enable` publica `https://host.ts.net:10443` en un puerto
dedicado del tailnet y lo redirige al Dashboard local `:9119` con su ruta Relay
del mismo origen. Una ruta deliberadamente directa como
`http://100.x.y.z:9119` también funciona, pero no tiene TLS de aplicación.

La misma sesión habilita Chat, sesiones, Manage y Voice. Es normal que Relay
esté sin emparejar y que API fallback no esté disponible.

## Recomendado: completa la configuración con Relay {#relay-server-optional}

El recorrido upstream sigue funcionando sin plugin. Relay se recomienda para
Terminal/TUI, notificaciones, multimedia, herramientas de escritorio, voz
mejorada, sesiones Relay y Device Control opcional. Los comandos canónicos son
`hermes plugins install Codename-11/hermes-relay/plugin --enable`,
`hermes relay doctor`, `hermes relay start --no-ssl` y `hermes pair`.

En el Web Dashboard, abre **Relay**, usa primero **Connect mobile app** y luego
**Pair new device**, y escanea ambos QR con Android. Si no puedes usar QR,
siguen disponibles la entrada de URL/código y `hermes pair --register-code`.

Device Control necesita **las dos cosas**: la aplicación Sideload y un Relay emparejado.

[Comparar versiones →](/es/guide/release-tracks) ·
[Acceso remoto en inglés →](/guide/remote-access) ·
[Solución de problemas →](/es/guide/troubleshooting)
