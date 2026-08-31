---
translation_status: ai-translated
canonical_source: /guide/quick-start
---

# Schnellstart

Installieren → verbinden → chatten. Der Standardweg bleibt upstream-basiert;
für die vollständige Hermes-Relay-Erfahrung wird das Relay-Plugin empfohlen.

<AndroidSetupPath mode="quick" />

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

Für den empfohlenen vollständigen Weg installiere zusätzlich:

```bash
hermes plugins install Codename-11/hermes-relay/plugin --enable
hermes relay doctor
hermes relay start --no-ssl
```

Nutze `--no-ssl` nur in einem vertrauenswürdigen LAN oder VPN. Für den Zugriff
von unterwegs wird [Tailscale empfohlen](/guide/remote-access).

## 3. Verbinden {#other-supported-paths}

Öffne die App und gehe zu **Connect**. Nutze eine der folgenden Möglichkeiten:

1. Öffne im Web Dashboard **Relay → Connect mobile app** und scanne den
   tokenlosen QR über Android **Connect → Scan Hermes setup QR**.
2. Öffne danach **Relay → Pair new device** und scanne den einmaligen QR über
   **Settings → Connections → Pair Hermes Relay**.
3. Ohne Dashboard-Plugin nutze **Find Hermes on LAN** oder trage die
   Dashboard-Adresse wie `http://<host>:9119` manuell ein.
4. Ohne Kamera erzeugt `hermes pair` denselben QR und eine kopierbare Einladung;
   URL und Code bleiben als manueller Fallback verfügbar.
5. Melde dich bei Aufforderung über den konfigurierten Dashboard-Anbieter an.

Der API-Server bleibt ein optionaler Fallback. Relay ist für den Upstream-Weg
nicht erforderlich, wird aber für Terminal/TUI, Benachrichtigungen, Medien,
Desktop-Werkzeuge, erweiterte Voice und Device Control empfohlen.

## 4. Status prüfen

- **Chat · Ready** bedeutet, dass du Nachrichten senden kannst.
- **Manage** kann noch eine Dashboard-Anmeldung verlangen.
- **Voice** wird mit derselben Dashboard-Anmeldung freigeschaltet.
- **Direct API** darf als nicht verfügbar angezeigt werden, ohne Chat zu blockieren.
- **Relay · Paired** bestätigt die empfohlenen Zusatzfunktionen; ein Relay-Ausfall
  darf den Upstream-Standardweg nicht blockieren.

## 5. Erste Nachricht senden

Öffne Chat und sende eine Nachricht. Ein grüner Verbindungspunkt im Kopfbereich
bestätigt, dass die aktive Hermes-Verbindung erreichbar ist.

[Ausführliche Installation →](/de/guide/getting-started) ·
[Fehlerbehebung →](/de/guide/troubleshooting) ·
[Vollständige englische Anleitung →](/guide/quick-start)
