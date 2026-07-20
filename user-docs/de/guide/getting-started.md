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

Die Dashboard-Anmeldung verwendet Cookies und kurzlebige Gateway-Tickets. Ein
API-Schlüssel ist davon getrennt und kein Dashboard-Login.

## 3. Verbinden und chatten

1. Öffne **Connect** in der Android-App.
2. Suche Hermes im LAN, trage die Dashboard/Gateway-URL ein oder scanne einen Einrichtungs-QR; ältere API-first-QRs bleiben kompatibel.
3. Melde dich bei Aufforderung am Dashboard an.
4. Tippe auf **Connect** und prüfe **Chat · Ready**.
5. Füge API-Fallback, Relay oder weitere Remote-Routen bei Bedarf später unter **Advanced** hinzu.

Eine Tailscale-Dashboard-Adresse wie `http://100.x.y.z:9119` oder eine separat
veröffentlichte `.ts.net`-Adresse kann ohne API-Server oder API-Schlüssel als Route hinzugefügt und geprüft werden.

Dieselbe Anmeldung schaltet Chat, Sitzungen, Manage und Voice frei. Ein
ungepaartes Relay und ein nicht verfügbarer API-Fallback sind normal.

## Optional: Relay-Werkzeuge hinzufügen

Installiere das Plugin nur für Terminal, Device Control, Medien,
Benachrichtigungen oder erweiterte Remote-Werkzeuge. Die maßgeblichen Befehle
sind `hermes plugins install Codename-11/hermes-relay/plugin --enable`,
`hermes relay doctor`, `hermes relay start --no-ssl` und `hermes pair`.

Device Control benötigt **beides**: die Sideload-App und ein gepaartes Relay.

[App-Versionen vergleichen →](/de/guide/release-tracks) ·
[Fernzugriff auf Englisch →](/guide/remote-access) ·
[Fehlerbehebung →](/de/guide/troubleshooting)
