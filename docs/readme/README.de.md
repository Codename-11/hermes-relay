# Hermes-Relay

**Läuft auf deinem Rechner. Ist auf deinen Geräten dabei.**

[English](../../README.md) · **Deutsch** · [Español](README.es.md) · [日本語](README.ja.md) · [Português (Brasil)](README.pt-BR.md) · [Русский](README.ru.md) · [简体中文](README.zh-CN.md)

> Englisch ist die verbindliche und vollständige Projektbeschreibung. Diese
> KI-gestützte Übersetzung ist ein gepflegter, kompakter Einstieg.

Hermes-Relay bringt deinen [Hermes Agent](https://github.com/NousResearch/hermes-agent)
auf Android und verbundene Computer. Hermes läuft weiterhin auf deinem eigenen
Rechner; Hermes-Relay stellt die nativen Oberflächen und optionalen Erweiterungen bereit.

## Kernfunktionen

- **Android:** Streaming-Chat, Sitzungen, eingehende Dateien, Manage, Voice und Petdex.
- **Standardverbindung:** Chat, Manage, Sitzungen, Voice und Dateien nutzen direkt das unveränderte Hermes Dashboard/Gateway.
- **Optionale Relay-Erweiterung:** Terminal/TUI, Benachrichtigungen, Desktop-Werkzeuge, erweiterte Voice, Relay-Sitzungen und Medienfunktionen.
- **Sideload-Version:** ergänzt zustimmungspflichtige Device-Control-Funktionen wie Bildschirmlesen, Tippen und Navigation.
- **CLI / UI:** verbindet Computer direkt mit Relay und stellt zustimmungspflichtige Datei-, Terminal-, Such- und Screenshot-Werkzeuge bereit.

## Schnellstart für Android

1. Installiere die App über [Google Play](https://play.google.com/store/apps/details?id=com.axiomlabs.hermesrelay) oder lade die signierte Sideload-APK aus den neuesten [`android-v*` Releases](https://github.com/Codename-11/hermes-relay/releases) herunter.
2. Starte auf dem Hermes-Rechner die Standardoberfläche:

   ```bash
   hermes dashboard
   ```

3. Öffne in Android **Connect**, suche Hermes im LAN oder gib die Dashboard-Adresse ein und melde dich an. Für den Standardweg sind weder Relay noch ein separater API-Schlüssel erforderlich.
4. Installiere Relay nur für die zusätzlichen Funktionen:

   ```bash
   hermes plugins install Codename-11/hermes-relay/plugin --enable
   hermes relay doctor
   hermes relay start --no-ssl
   ```

   Verwende `--no-ssl` nur in einem vertrauenswürdigen LAN oder VPN. Kopple danach über **Relay → Pair new device** im Dashboard.

## Weitere Informationen

[Deutscher Schnellstart](https://hermes-relay.dev/docs/de/guide/quick-start) ·
[Installation](https://hermes-relay.dev/docs/de/guide/getting-started) ·
[Fehlerbehebung](https://hermes-relay.dev/docs/de/guide/troubleshooting) ·
[Vollständige englische Dokumentation](https://hermes-relay.dev/docs/)

[MIT-Lizenz](../../LICENSE)
