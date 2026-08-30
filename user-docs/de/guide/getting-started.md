---
translation_status: ai-translated
canonical_source: /guide/getting-started
---

# Installation & Einrichtung

Diese Seite ist die ausführliche Referenz für Build-Auswahl, manuelle
Verbindung, Fernzugriff und Sicherheitsprüfungen. Wenn dein Hermes Dashboard
bereits erreichbar ist, nutze den [Schnellstart](./quick-start).

<AndroidSetupPath mode="reference" />

::: tip Übersetzungsstatus
Diese kompakte Übersetzung beschreibt den üblichen Einstieg. Erweiterte
Server-, TLS- und Betreiberoptionen stehen in der
[vollständigen englischen Anleitung](/guide/getting-started).
:::

## 1. App auswählen

| | Google Play | Sideload |
|---|---|---|
| Empfohlen für | Die meisten Nutzer | Nutzer von Device Control |
| Updates | Automatisch | APK manuell aktualisieren |
| Chat, Voice, Manage | Enthalten | Enthalten |
| Terminal, Medien, Benachrichtigungen mit Relay | Enthalten | Enthalten |
| Bildschirm lesen, tippen, schreiben, navigieren | Nicht enthalten | Enthalten |

<StoreBadge />

Die signierte Sideload-Datei endet mit `-sideload-release.apk` und liegt unter
[GitHub Releases](https://github.com/Codename-11/hermes-relay/releases). Lade
nicht die `.aab`-Datei herunter; sie ist nur für Google Play bestimmt.

## 2. Hermes erreichbar machen

Android verwendet standardmäßig das Hermes Dashboard/Gateway unter `:9119`.
Es stellt Chat, Sitzungen, Anmeldung, Manage und Standard-Voice bereit. Starte
es mit `hermes dashboard` und mache diese Adresse für das Telefon erreichbar.

Der API-Server unter `:8642` ist optional: Er dient als automatischer
Chat-Fallback oder für erweiterte headless Kompatibilität. Einen API-Schlüssel
brauchst du nur, wenn du diesen optionalen Endpunkt konfigurierst. Der
Serverbetreiber erstellt `API_SERVER_KEY` selbst; das Dashboard stellt keinen bereit.

::: warning Netzwerkzugriff absichern
Stelle einen unverschlüsselten Dashboard-, API- oder Relay-Port niemals direkt
ins Internet; verwende für den Fernzugriff Tailscale, ein VPN oder HTTPS.
:::

Die Dashboard-Anmeldung verwendet auf aktuellen Gateways ein natives
Bearer-Token, auf Kompatibilitäts-Gateways hostgebundene Cookies und jeweils
kurzlebige Gateway-Tickets. Ein API-Schlüssel ist davon getrennt.

Die in Android gespeicherte Route darf eine LAN-, Tailscale- oder öffentliche
Adresse sein. OIDC benötigt trotzdem einen erreichbaren HTTPS-Rückruf unter
`<öffentliche-dashboard-adresse>/auth/callback`. Hermes leitet diese Adresse
normalerweise aus vertrauenswürdigen Proxy-Headern ab. Setze upstream
`dashboard.public_url` / `HERMES_DASHBOARD_PUBLIC_URL` nur, wenn diese Ableitung
unzuverlässig ist; Android prüft eine abweichende Anmeldeadresse vor dem Speichern.

## 3. Verbinden und chatten

1. Öffne **Connect** in der Android-App.
2. Suche Hermes im LAN, trage die Dashboard/Gateway-URL ein oder scanne einen Einrichtungs-QR; ältere API-first-QRs bleiben kompatibel.
3. Melde dich bei Aufforderung am Dashboard an.
4. Tippe auf **Connect** und prüfe **Chat · Ready**.
5. Aktiviere unter **Einrichtung abschließen** Android-Benachrichtigungen, wenn du Chat-Hinweise im Hintergrund erhalten möchtest. Kamera, Mikrofon und weitere Funktionen bleiben optional und werden einzeln eingerichtet; mit **Jetzt nicht** kannst du direkt fortfahren.
6. Füge API-Fallback, Relay oder weitere Remote-Routen bei Bedarf später unter **Advanced** hinzu.

`hermes-relay-tailscale enable` veröffentlicht `https://host.ts.net` auf
Tailnet-Port `:443` und leitet an das lokale Dashboard `:9119` samt
gleichnamigem Relay-Pfad weiter. Eine bewusst direkt erreichbare Route wie
`http://100.x.y.z:9119` funktioniert ebenfalls, besitzt aber kein Anwendungs-TLS.

Dieselbe Anmeldung schaltet Chat, Sitzungen, Manage und Voice frei. Ein
ungepaartes Relay und ein nicht verfügbarer API-Fallback sind normal.

## Empfohlen: Mit Relay vervollständigen {#relay-server-optional}

Der Upstream-Standardweg bleibt ohne Plugin funktionsfähig. Für Terminal/TUI,
Benachrichtigungen, Medien, Desktop-Werkzeuge, erweiterte Voice, Relay-Sitzungen
und optionales Device Control wird Relay empfohlen. Die maßgeblichen Befehle sind
`hermes plugins install Codename-11/hermes-relay/plugin --enable`,
`hermes relay doctor`, `hermes relay start --no-ssl` und `hermes pair`.

Bevorzugt im Web Dashboard unter **Relay** zuerst **Connect mobile app** und
danach **Pair new device** öffnen und beide QRs mit Android scannen. Ohne QR
bleiben die URL-/Code-Eingabe und `hermes pair --register-code` verfügbar.

Device Control benötigt **beides**: die Sideload-App und ein gepaartes Relay.

[App-Versionen vergleichen →](/de/guide/release-tracks) ·
[Fernzugriff auf Englisch →](/guide/remote-access) ·
[Fehlerbehebung →](/de/guide/troubleshooting)
