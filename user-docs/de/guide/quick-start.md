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

Auf dem Host muss Hermes erreichbar sein und der API-Server laufen. Starte ihn
bei Bedarf mit `hermes gateway`. Die ausführliche Einrichtung steht unter
[Installation & Einrichtung](/de/guide/getting-started).

## 3. Verbinden

Öffne die App und gehe zu **Connect**. Nutze eine der folgenden Möglichkeiten:

1. **Scan for Hermes on LAN** sucht den Server im lokalen Netz.
2. Trage die Adresse wie `http://<host>:8642` und den konfigurierten API-Schlüssel ein.
3. Scanne einen Einrichtungs-QR-Code mit URL und Schlüssel.

Wenn der Host absichtlich ohne `API_SERVER_KEY` läuft, bleibt das Schlüsselfeld leer.

## 4. Status prüfen

- **Chat · Ready** bedeutet, dass du Nachrichten senden kannst.
- **Manage** kann noch eine Dashboard-Anmeldung verlangen.
- **Voice** wird mit derselben Dashboard-Anmeldung freigeschaltet.
- **Relay** darf ungepaart bleiben und blockiert den Standardweg nicht.

## 5. Erste Nachricht senden

Öffne Chat und sende eine Nachricht. Ein grüner Verbindungspunkt im Kopfbereich
bestätigt, dass die aktive Hermes-Verbindung erreichbar ist.

[Ausführliche Installation →](/de/guide/getting-started) ·
[Fehlerbehebung →](/de/guide/troubleshooting) ·
[Vollständige englische Anleitung →](/guide/quick-start)
