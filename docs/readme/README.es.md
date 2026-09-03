# Hermes-Relay

**Se ejecuta en tu equipo. Te acompaña en tus dispositivos.**

[English](../../README.md) · [Deutsch](README.de.md) · **Español** · [日本語](README.ja.md) · [Português (Brasil)](README.pt-BR.md) · [Русский](README.ru.md) · [简体中文](README.zh-CN.md)

> El inglés es la descripción canónica y completa del proyecto. Esta traducción
> asistida por IA es una introducción breve y mantenida.

Hermes-Relay lleva tu [Hermes Agent](https://github.com/NousResearch/hermes-agent)
a Android y a equipos conectados. Hermes sigue ejecutándose en tu propia máquina;
Hermes-Relay aporta interfaces nativas y extensiones opcionales.

## Funciones principales

- **Android:** chat en streaming, sesiones, archivos entrantes, Manage, voz y Petdex.
- **Conexión estándar:** chat, Manage, sesiones, voz y archivos se conectan directamente al Dashboard/Gateway de Hermes sin modificar.
- **Extensión Relay opcional:** Terminal/TUI, notificaciones, herramientas de escritorio, voz mejorada, sesiones Relay y funciones multimedia.
- **Versión sideload:** añade Device Control con confirmación para leer la pantalla, tocar y navegar.
- **CLI / UI:** conecta equipos directamente con Relay y ofrece herramientas de archivos, terminal, búsqueda y capturas sujetas a consentimiento.

## Inicio rápido en Android

1. Instala la aplicación desde [Google Play](https://play.google.com/store/apps/details?id=com.axiomlabs.hermesrelay) o descarga la APK sideload firmada desde la versión [`android-v*` más reciente](https://github.com/Codename-11/hermes-relay/releases).
2. Inicia la superficie estándar en el equipo que ejecuta Hermes:

   ```bash
   hermes dashboard
   ```

3. En Android, abre **Connect**, busca Hermes en la red local o escribe la dirección del Dashboard e inicia sesión. La ruta estándar no necesita Relay ni una clave de API independiente.
4. Instala Relay solo si quieres las funciones adicionales:

   ```bash
   hermes plugins install Codename-11/hermes-relay/plugin --enable
   hermes relay doctor
   hermes relay start --no-ssl
   ```

   Usa `--no-ssl` únicamente en una red local o VPN de confianza. Después, vincula el dispositivo desde **Relay → Pair new device** en el Dashboard.

## Más información

[Inicio rápido en español](https://hermes-relay.dev/docs/es/guide/quick-start) ·
[Instalación](https://hermes-relay.dev/docs/es/guide/getting-started) ·
[Solución de problemas](https://hermes-relay.dev/docs/es/guide/troubleshooting) ·
[Documentación completa en inglés](https://hermes-relay.dev/docs/)

[Licencia MIT](../../LICENSE)
