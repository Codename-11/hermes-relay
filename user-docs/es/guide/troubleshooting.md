---
translation_status: ai-translated
canonical_source: /guide/troubleshooting
---

# Solución de problemas

Empieza por el síntoma que puedes ver. Así podrás separar rápidamente un
problema de Android, de red o del host de Hermes.

- [Punto rojo o sin conexión](#sin-conexión)
- [“No reachable endpoint”](#ningún-endpoint-accesible)
- [Chat no transmite la respuesta](#chat-no-transmite)
- [Manage o Voice pide iniciar sesión](#manage-y-voice)
- [Faltan sesiones](#faltan-sesiones)
- [La aplicación falla al iniciarse](#fallo-al-iniciar)

## Sin conexión

1. Comprueba en el host que `hermes gateway` está en ejecución.
2. Abre `http://<host>:8642/health` desde el **teléfono**.
3. Comprueba `API_SERVER_ENABLED=true` y las reglas del firewall.
4. No uses `localhost` ni `127.0.0.1` en el teléfono; apuntan al propio teléfono.

## Ningún endpoint accesible

La aplicación probó todas las rutas guardadas —LAN, Tailscale y públicas— sin
obtener respuesta. Una dirección LAN solo funciona en la misma red Wi-Fi. Para
Tailscale, el teléfono y el servidor deben estar conectados.

## Chat no transmite

- Comprueba la URL y la clave de API.
- Si aparece un error, pulsa **Retry** una sola vez.
- Revisa los registros del servidor Hermes.
- Los modelos locales pueden tardar varios minutos; si Android corta la conexión en segundo plano, la respuesta terminada se recuperará al reconectar.

## Manage y Voice

Manage y la voz estándar usan la sesión del dashboard, no `API_SERVER_KEY`.
Inicia sesión una vez desde Manage y confirma que el dashboard sea accesible
desde el teléfono.

## Faltan sesiones

El servidor debe estar disponible al cambiar de sesión. Las sesiones grandes
pueden tardar unos instantes; espera al indicador de carga.

## Fallo al iniciar

Abre **Ajustes de Android → Aplicaciones → Hermes-Relay → Almacenamiento** y
borra los datos de la aplicación. Después vuelve a configurar la URL y la clave.

Para problemas de Relay, `hermes relay doctor` ofrece un diagnóstico de solo lectura.

[Solución de problemas completa en inglés →](/guide/troubleshooting) ·
[Instalación →](/es/guide/getting-started)
