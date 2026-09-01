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

El Dashboard/Gateway de Hermes debe estar activo y accesible desde el teléfono.
Si es necesario, inícialo con `hermes dashboard`. Consulta
[Instalación y configuración](/es/guide/getting-started) para preparar el servidor.
Esta es toda la configuración del servidor necesaria para Chat, sesiones,
Manage, voz y archivos entrantes estándar.

## 3. Añade la conexión estándar {#other-supported-paths}

En Android, abre **Connect**. Usa **Find Hermes on LAN** o introduce manualmente
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
un solo uso desde **Settings → Connections → Pair Hermes Relay**.

El servidor de API sigue siendo un fallback opcional. Relay no es obligatorio
para upstream, pero se recomienda para Terminal/TUI, notificaciones,
herramientas de escritorio, voz mejorada, sesiones Relay, Device Control y
compatibilidad o metadatos multimedia. Los archivos entrantes normales usan
las rutas actuales del Dashboard.

## 5. Comprueba el estado

- **Chat · Ready** significa que ya puedes enviar mensajes.
- **Manage** puede pedir que inicies sesión en el dashboard.
- **Voice** se habilita con esa misma sesión del dashboard.
- **API fallback** puede no estar disponible sin bloquear Chat.
- **Relay · Paired** confirma las extensiones recomendadas; un fallo de Relay no
  debe bloquear el recorrido upstream estándar.

## 6. Envía el primer mensaje

Abre Chat y envía un mensaje. El indicador verde del encabezado confirma que la
conexión activa con Hermes está disponible.

[Instalación detallada →](/es/guide/getting-started) ·
[Solución de problemas →](/es/guide/troubleshooting) ·
[Guía canónica en inglés →](/guide/quick-start)
