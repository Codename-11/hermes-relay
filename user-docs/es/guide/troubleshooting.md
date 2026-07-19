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

1. Comprueba en el host que `hermes dashboard` está en ejecución.
2. Abre la dirección del Dashboard/Gateway desde el **teléfono**, normalmente `http://<host>:9119`.
3. Comprueba el firewall y vuelve a iniciar sesión para obtener un ticket `/api/ws` nuevo.
4. No uses `localhost` ni `127.0.0.1` en el teléfono; apuntan al propio teléfono.

## Ningún endpoint accesible

La aplicación probó todas las rutas guardadas —LAN, Tailscale y públicas— sin
obtener respuesta. Una dirección LAN solo funciona en la misma red Wi-Fi. Para
Tailscale, el teléfono y el servidor deben estar conectados.
Una ruta Dashboard como `http://100.x.y.z:9119` se prueba sin servidor API ni clave API.

## Chat no transmite

- Comprueba la URL del Dashboard/Gateway, la sesión y `/api/ws`.
- Si aparece un error, pulsa **Retry** una sola vez.
- Revisa los registros del servidor Hermes.
- El API fallback opcional se diagnostica por separado; su fallo no bloquea un Gateway saludable.
- Los modelos locales pueden tardar varios minutos; si Android corta la conexión en segundo plano, la respuesta terminada se recuperará al reconectar.

Si el API fallback configurado intencionadamente no está disponible, comprueba
`API_SERVER_ENABLED`, la dirección de escucha, `http://<host>:8642/health`, la
`API_SERVER_KEY` creada por el operador y el firewall. El inicio de sesión del Dashboard no crea esa clave.

## Manage y Voice

Manage y la voz estándar usan la sesión del dashboard, no `API_SERVER_KEY`.
Inicia sesión una vez desde Manage y confirma que el dashboard sea accesible
desde el teléfono.

## Faltan sesiones

El servidor debe estar disponible al cambiar de sesión. Las sesiones grandes
pueden tardar unos instantes; espera al indicador de carga.

## Fallo al iniciar

Abre **Ajustes de Android → Aplicaciones → Hermes-Relay → Almacenamiento** y
borra los datos de la aplicación. Después vuelve a configurar la dirección y el inicio de sesión del Dashboard/Gateway.

Para problemas de Relay, `hermes relay doctor` ofrece un diagnóstico de solo lectura.

[Solución de problemas completa en inglés →](/guide/troubleshooting) ·
[Instalación →](/es/guide/getting-started)
