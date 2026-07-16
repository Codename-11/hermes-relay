---
translation_status: ai-translated
canonical_source: /guide/quick-start
---

# Inicio rápido

Instala → conecta → conversa, en unos dos minutos. Este recorrido funciona con
un Hermes Agent normal; no requiere el complemento Relay.

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

El servidor de API de Hermes debe estar activo y accesible desde el teléfono.
Si es necesario, inicia el host con `hermes gateway`. Consulta
[Instalación y configuración](/es/guide/getting-started) para preparar el servidor.

## 3. Conecta

Abre la aplicación y llega a **Connect**. Puedes:

1. Usar **Scan for Hermes on LAN** para buscar el servidor en tu red local.
2. Introducir una dirección como `http://<host>:8642` y la clave de API configurada.
3. Escanear un código QR de configuración que contenga la URL y la clave.

Si el host se ejecuta intencionadamente sin `API_SERVER_KEY`, deja la clave vacía.

## 4. Comprueba el estado

- **Chat · Ready** significa que ya puedes enviar mensajes.
- **Manage** puede pedir que inicies sesión en el dashboard.
- **Voice** se habilita con esa misma sesión del dashboard.
- **Relay** puede seguir sin emparejar y no bloquea el funcionamiento estándar.

## 5. Envía el primer mensaje

Abre Chat y envía un mensaje. El indicador verde del encabezado confirma que la
conexión activa con Hermes está disponible.

[Instalación detallada →](/es/guide/getting-started) ·
[Solución de problemas →](/es/guide/troubleshooting) ·
[Guía canónica en inglés →](/guide/quick-start)
