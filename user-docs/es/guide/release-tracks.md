---
translation_status: ai-translated
canonical_source: /guide/release-tracks
---

# Versiones de la aplicación: Google Play o Sideload

Empieza con Google Play salvo que necesites específicamente Device Control. Las
dos versiones proceden del mismo código y pueden convivir en el teléfono.

## Ayuda para elegir

| Pregunta | Google Play | Sideload |
|---|---|---|
| ¿Instalación sencilla y actualizaciones automáticas? | Sí | No |
| ¿Chat, perfiles, Manage y Voice? | Sí | Sí |
| ¿Terminal, multimedia y notificaciones con Relay? | Sí | Sí |
| ¿Leer o capturar la pantalla? | No | Sí |
| ¿Tocar, escribir, deslizar y manejar aplicaciones? | No | Sí |

## La versión y Relay son decisiones independientes

La **versión de la aplicación** decide si Android incluye Device Control. El
**complemento Relay** opcional conecta el terminal, el contenido multimedia,
las notificaciones y los canales de dispositivos con el host de Hermes.

Device Control solo funciona con **Sideload + Relay emparejado**. Chat, Manage y
la voz estándar no necesitan ninguno de los dos.

## Cambiar más adelante

Google Play y Sideload utilizan identificadores de aplicación distintos. Puedes
probar ambas y eliminar una después. Cada aplicación conserva sus propios ajustes
y emparejamientos.

[Abrir Google Play](https://play.google.com/store/apps/details?id=com.axiomlabs.hermesrelay) ·
[Descargar el APK Sideload](https://github.com/Codename-11/hermes-relay/releases) ·
[Comparación completa en inglés →](/guide/release-tracks)
