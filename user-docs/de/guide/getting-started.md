---
translation_status: ai-translated
canonical_source: /guide/getting-started
---

# Installation & Einrichtung

Drei Schritte: App installieren, mit Hermes verbinden, erste Nachricht senden.
Wenn Hermes bereits läuft, muss auf dem Server nichts zusätzlich installiert werden.

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

Android benötigt den Hermes-API-Server, normalerweise unter `:8642`:

- `API_SERVER_ENABLED=true` aktiviert den Server.
- `API_SERVER_HOST=0.0.0.0` macht ihn im Netzwerk erreichbar.
- `API_SERVER_KEY` schützt Chat-Anfragen mit einem Bearer-Schlüssel.
- `hermes gateway` startet Hermes und den aktivierten API-Server.

::: warning Netzwerkzugriff absichern
`0.0.0.0` erlaubt anderen Geräten im Netzwerk den Zugriff. Verwende einen
starken API-Schlüssel. Stelle einen unverschlüsselten Port niemals direkt ins
Internet; verwende für den Fernzugriff Tailscale, ein VPN oder HTTPS.
:::

Das Dashboard unter `:9119` ist optional. Es wird für Manage und Standard-Voice
benötigt und hat eine eigene Anmeldung; der API-Schlüssel ist kein Dashboard-Login.

## 3. Verbinden und chatten

1. Öffne **Connect** in der Android-App.
2. Suche Hermes im LAN, scanne einen Einrichtungs-QR-Code oder trage API-URL und Schlüssel ein.
3. Tippe auf **Connect**.
4. Prüfe, ob **Chat · Ready** angezeigt wird.
5. Öffne Chat und sende die erste Nachricht.

Manage und Voice dürfen noch eine Anmeldung verlangen. Ein ungepaartes Relay
ist ebenfalls normal.

## Optional: Relay-Werkzeuge hinzufügen

Installiere das Plugin nur für Terminal, Device Control, Medien,
Benachrichtigungen oder erweiterte Remote-Werkzeuge. Die maßgeblichen Befehle
sind `hermes plugins install Codename-11/hermes-relay/plugin --enable`,
`hermes relay doctor`, `hermes relay start --no-ssl` und `hermes pair`.

Device Control benötigt **beides**: die Sideload-App und ein gepaartes Relay.

[App-Versionen vergleichen →](/de/guide/release-tracks) ·
[Fernzugriff auf Englisch →](/guide/remote-access) ·
[Fehlerbehebung →](/de/guide/troubleshooting)
