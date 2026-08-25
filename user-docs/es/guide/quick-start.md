---
translation_status: ai-translated
canonical_source: /guide/quick-start
---

# Inicio rápido

Instala → conecta → conversa. El recorrido estándar sigue siendo upstream; el
plugin Relay se recomienda para la experiencia completa de Hermes-Relay.

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

Para el recorrido completo recomendado, instala además:

```bash
hermes plugins install Codename-11/hermes-relay/plugin --enable
hermes relay doctor
hermes relay start --no-ssl
```

Usa `--no-ssl` solo en una LAN o VPN de confianza. Para acceder desde fuera de
casa, [se recomienda Tailscale](/guide/remote-access).

## 3. Conecta {#other-supported-paths}

Abre la aplicación y llega a **Connect**. Puedes:

1. En el Web Dashboard abre **Relay → Connect mobile app** y escanea ese QR sin
   credenciales desde Android **Connect → Scan Hermes setup QR**.
2. Después abre **Relay → Pair new device** y escanea el QR de un solo uso desde
   **Settings → Connections → Pair Hermes Relay**.
3. Sin el plugin del Dashboard, usa **Find Hermes on LAN** o introduce
   manualmente la dirección como `http://<host>:9119`.
4. Sin cámara, `hermes pair` genera el mismo QR y una invitación copiable; URL y
   código siguen disponibles como fallback manual.
5. Inicia sesión con el proveedor del dashboard cuando se solicite.

El servidor de API sigue siendo un fallback opcional. Relay no es obligatorio
para upstream, pero se recomienda para Terminal/TUI, notificaciones, medios,
herramientas de escritorio, voz mejorada y Device Control.

## 4. Comprueba el estado

- **Chat · Ready** significa que ya puedes enviar mensajes.
- **Manage** puede pedir que inicies sesión en el dashboard.
- **Voice** se habilita con esa misma sesión del dashboard.
- **API fallback** puede no estar disponible sin bloquear Chat.
- **Relay · Paired** confirma las extensiones recomendadas; un fallo de Relay no
  debe bloquear el recorrido upstream estándar.

## 5. Envía el primer mensaje

Abre Chat y envía un mensaje. El indicador verde del encabezado confirma que la
conexión activa con Hermes está disponible.

[Instalación detallada →](/es/guide/getting-started) ·
[Solución de problemas →](/es/guide/troubleshooting) ·
[Guía canónica en inglés →](/guide/quick-start)
