---
translation_status: ai-translated
canonical_source: /guide/quick-start
---

# Inicio rápido

Empieza con la conexión estándar upstream. Después, empareja Relay cuando
quieras sus herramientas y mejoras adicionales.

<AndroidSetupPath mode="quick" />

::: tip Estado de la traducción
Esta página se tradujo con asistencia de IA y pasó las comprobaciones técnicas.
El inglés sigue siendo la fuente canónica del significado del producto y la seguridad.
:::

## 1. Instala la aplicación

Para la mayoría de las personas, **Google Play** es el camino más rápido:
instalación con un toque y actualizaciones automáticas.

<StoreBadge />

Si quieres que Hermes lea la pantalla, toque, escriba o navegue por el teléfono,
instala en su lugar el APK firmado de **Sideload**. Las dos versiones pueden
estar instaladas a la vez.

## 2. Inicia Hermes

El Dashboard debe ser accesible desde el teléfono y tener un proveedor de autenticación. `hermes dashboard` escucha solo en loopback de forma predeterminada. Configura primero el inicio de sesión y, para acceso directo por LAN/VPN, ejecuta `hermes dashboard --host 0.0.0.0 --port 9119 --no-open`. Un proxy inverso hacia loopback necesita la `dashboard.public_url` externa y un proveedor. Nunca copies el token interno al teléfono.

Elige **Hermes nearby** o **Remote gateway**. Se recomienda HTTPS. Otras direcciones HTTP requieren aceptar el riesgo para esta conexión, host y puerto exactos. La aplicación no detecta ni impone protección VPN; asumes la exposición de credenciales y conversaciones si la VPN falla. Cambiar el origen requiere nuevo consentimiento e inicio de sesión. Cancelar conserva la dirección anterior; el historial y los borradores se mantienen. Direct API es una alternativa explícita, nunca un reemplazo automático del chat Gateway. Un 401 por sí solo no demuestra un error en `dashboard.public_url`.

## 3. Añade la conexión estándar {#other-supported-paths}

En Android, abre **Connect**. Usa **Hermes nearby** o introduce manualmente
la dirección del Dashboard, normalmente `http://<host>:9119`. Inicia sesión
cuando se solicite. Así se crea una conexión estándar completa sin plugin ni
URL de Relay.

## 4. Opcional: instala y empareja Relay

Para la experiencia completa recomendada, instala Relay después de comprobar
que funciona la conexión estándar:

```bash
hermes plugins install Codename-11/hermes-relay/plugin --enable
hermes relay doctor
hermes relay start --no-ssl
```

Usa `--no-ssl` solo en una LAN o VPN de confianza. Para acceder desde fuera de
casa, [se recomienda Tailscale](/guide/remote-access).

Después, abre **Relay → Pair new device** en el Web Dashboard y escanea el QR de
un solo uso desde **Settings → Gateways → Pair Hermes Relay**.

El servidor de API sigue siendo un fallback opcional. Relay no es obligatorio
para upstream, pero se recomienda para Terminal/TUI, notificaciones,
herramientas de escritorio, voz mejorada, sesiones Relay, Device Control y
compatibilidad o metadatos multimedia. Los archivos entrantes normales usan
las rutas actuales del Dashboard.

## 5. Comprueba el estado

- **Chat · Ready** significa que ya puedes enviar mensajes.
- **Manage** puede pedir que inicies sesión en el dashboard.
- **Voice** se habilita con esa misma sesión del dashboard.
- **Direct API** puede no estar disponible sin bloquear Chat.
- **Relay · Paired** confirma las extensiones recomendadas; un fallo de Relay no
  debe bloquear el recorrido upstream estándar.

## 6. Envía el primer mensaje

Abre Chat y envía un mensaje. El indicador verde del encabezado confirma que la
conexión activa con Hermes está disponible.

[Instalación detallada →](/es/guide/getting-started) ·
[Solución de problemas →](/es/guide/troubleshooting) ·
[Guía canónica en inglés →](/guide/quick-start)
