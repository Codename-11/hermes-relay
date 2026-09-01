---
translation_status: ai-translated
canonical_source: /guide/quick-start
---

# Schnellstart

Beginne mit der standardmäßigen Upstream-Verbindung. Kopple Relay anschließend,
wenn du dessen zusätzliche Werkzeuge und Erweiterungen nutzen möchtest.

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
sein. Starte es bei Bedarf mit `hermes dashboard`. Das ist die gesamte
serverseitige Einrichtung für Standard-Chat, Sitzungen, Manage, Voice und
eingehende Dateien. Die ausführliche Einrichtung steht unter
[Installation & Einrichtung](/de/guide/getting-started).

## 3. Standardverbindung hinzufügen {#other-supported-paths}

Öffne in Android **Connect**. Nutze **Find Hermes on LAN** oder trage die
Dashboard-Adresse, normalerweise `http://<host>:9119`, manuell ein. Melde dich
bei Aufforderung an. Damit entsteht eine vollständige Standardverbindung ohne
Plugin oder Relay-URL.

## 4. Optional: Relay installieren und koppeln

Installiere Relay für die empfohlene vollständige Erfahrung erst, nachdem die
Standardverbindung funktioniert:

```bash
hermes plugins install Codename-11/hermes-relay/plugin --enable
hermes relay doctor
hermes relay start --no-ssl
```

Nutze `--no-ssl` nur in einem vertrauenswürdigen LAN oder VPN. Für den Zugriff
von unterwegs wird [Tailscale empfohlen](/guide/remote-access).

Öffne danach im Web Dashboard **Relay → Pair new device** und scanne den
einmaligen QR über **Settings → Connections → Pair Hermes Relay**.

Der API-Server bleibt ein optionaler Fallback. Relay ist für den Upstream-Weg
nicht erforderlich, wird aber für Terminal/TUI, Benachrichtigungen,
Desktop-Werkzeuge, erweiterte Voice, Relay-Sitzungen, Device Control sowie
Medienkompatibilität und -metadaten empfohlen. Gewöhnliche eingehende Dateien
nutzen die aktuellen Dashboard-Routen.

## 5. Status prüfen

- **Chat · Ready** bedeutet, dass du Nachrichten senden kannst.
- **Manage** kann noch eine Dashboard-Anmeldung verlangen.
- **Voice** wird mit derselben Dashboard-Anmeldung freigeschaltet.
- **API fallback** darf als nicht verfügbar angezeigt werden, ohne Chat zu blockieren.
- **Relay · Paired** bestätigt die empfohlenen Zusatzfunktionen; ein Relay-Ausfall
  darf den Upstream-Standardweg nicht blockieren.

## 6. Erste Nachricht senden

Öffne Chat und sende eine Nachricht. Ein grüner Verbindungspunkt im Kopfbereich
bestätigt, dass die aktive Hermes-Verbindung erreichbar ist.

[Ausführliche Installation →](/de/guide/getting-started) ·
[Fehlerbehebung →](/de/guide/troubleshooting) ·
[Vollständige englische Anleitung →](/guide/quick-start)
