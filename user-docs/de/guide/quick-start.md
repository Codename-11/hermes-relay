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

Das Dashboard muss vom Telefon erreichbar sein und einen Authentifizierungsanbieter verwenden. `hermes dashboard` bindet standardmäßig nur an Loopback. Konfiguriere zuerst die Anmeldung und starte für direkten LAN-/VPN-Zugriff mit `hermes dashboard --host 0.0.0.0 --port 9119 --no-open`. Bei einem Reverse Proxy auf Loopback müssen die externe `dashboard.public_url` und ein Anbieter eingerichtet sein. Kopiere niemals den internen Sitzungstoken auf das Telefon.

Wähle **Hermes nearby** oder **Remote gateway**. HTTPS wird empfohlen. Andere HTTP-Adressen erfordern eine ausdrückliche Risikoannahme für diese Verbindung und den genauen Host und Port. Die App erkennt oder erzwingt keinen VPN-Schutz; bei VPN-Ausfall trägst du das Risiko unverschlüsselter Zugangsdaten und Gespräche. Eine neue Adresse benötigt neue Zustimmung und gegebenenfalls eine neue Anmeldung. Abbrechen erhält die alte Adresse; Verlauf und Entwürfe bleiben erhalten. Direct API ist eine ausdrücklich gewählte Alternative, kein automatischer Ersatz für Gateway-Chats. Ein 401 allein beweist keinen Fehler in `dashboard.public_url`.

## 3. Standardverbindung hinzufügen {#other-supported-paths}

Öffne in Android **Connect**. Nutze **Hermes nearby** oder trage die
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
einmaligen QR über **Settings → Gateways → Pair Hermes Relay**.

Der API-Server bleibt ein optionaler Fallback. Relay ist für den Upstream-Weg
nicht erforderlich, wird aber für Terminal/TUI, Benachrichtigungen,
Desktop-Werkzeuge, erweiterte Voice, Relay-Sitzungen, Device Control sowie
Medienkompatibilität und -metadaten empfohlen. Gewöhnliche eingehende Dateien
nutzen die aktuellen Dashboard-Routen.

## 5. Status prüfen

- **Chat · Ready** bedeutet, dass du Nachrichten senden kannst.
- **Manage** kann noch eine Dashboard-Anmeldung verlangen.
- **Voice** wird mit derselben Dashboard-Anmeldung freigeschaltet.
- **Direct API** darf als nicht verfügbar angezeigt werden, ohne Chat zu blockieren.
- **Relay · Paired** bestätigt die empfohlenen Zusatzfunktionen; ein Relay-Ausfall
  darf den Upstream-Standardweg nicht blockieren.

## 6. Erste Nachricht senden

Öffne Chat und sende eine Nachricht. Ein grüner Verbindungspunkt im Kopfbereich
bestätigt, dass die aktive Hermes-Verbindung erreichbar ist.

[Ausführliche Installation →](/de/guide/getting-started) ·
[Fehlerbehebung →](/de/guide/troubleshooting) ·
[Vollständige englische Anleitung →](/guide/quick-start)
