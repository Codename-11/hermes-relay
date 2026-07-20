---
translation_status: ai-translated
canonical_source: /guide/quick-start
---

# Schnellstart

Installieren → verbinden → chatten, in ungefähr zwei Minuten. Für diesen
Standardweg reicht ein normaler Hermes Agent; das Relay-Plugin ist nicht nötig.

::: tip Übersetzungsstatus
Diese Seite wurde KI-gestützt übersetzt und technisch geprüft. Englisch bleibt
die verbindliche Quelle für Produkt- und Sicherheitsbedeutung.
:::

## 1. App installieren

Für die meisten Nutzer ist **Google Play** der schnellste Weg: Installation mit
einem Tipp und automatische Updates.

<StoreBadge />

Wenn Hermes den Bildschirm lesen, tippen, Text eingeben oder Apps bedienen soll,
installiere stattdessen die signierte **Sideload-APK**. Beide Varianten können
gleichzeitig auf demselben Gerät installiert sein.

## 2. Hermes starten

Auf dem Host muss das Hermes Dashboard/Gateway laufen und vom Telefon erreichbar
sein. Starte es bei Bedarf mit `hermes dashboard`. Die ausführliche Einrichtung steht unter
[Installation & Einrichtung](/de/guide/getting-started).

## 3. Verbinden

Öffne die App und gehe zu **Connect**. Nutze eine der folgenden Möglichkeiten:

1. **Scan for Hermes on LAN** sucht den Server im lokalen Netz.
2. Trage die Dashboard/Gateway-Adresse wie `http://<host>:9119` ein.
3. Nutze **Scan setup QR**; ältere API-first-QRs bleiben für erweiterte Kompatibilität gültig.
4. Melde dich bei Aufforderung über den konfigurierten Dashboard-Anbieter an.

Der API-Server ist ein optionaler automatischer Fallback oder eine erweiterte
headless Kompatibilitätsoption; Relay bleibt für Zusatzfunktionen optional.

## 4. Status prüfen

- **Chat · Ready** bedeutet, dass du Nachrichten senden kannst.
- **Manage** kann noch eine Dashboard-Anmeldung verlangen.
- **Voice** wird mit derselben Dashboard-Anmeldung freigeschaltet.
- **API fallback** darf als nicht verfügbar angezeigt werden, ohne Chat zu blockieren.
- **Relay** darf ungepaart bleiben und blockiert den Standardweg nicht.

## 5. Erste Nachricht senden

Öffne Chat und sende eine Nachricht. Ein grüner Verbindungspunkt im Kopfbereich
bestätigt, dass die aktive Hermes-Verbindung erreichbar ist.

[Ausführliche Installation →](/de/guide/getting-started) ·
[Fehlerbehebung →](/de/guide/troubleshooting) ·
[Vollständige englische Anleitung →](/guide/quick-start)
