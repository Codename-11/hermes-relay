---
translation_status: ai-translated
canonical_source: /guide/release-tracks
---

# App-Versionen: Google Play oder Sideload

Beginne mit Google Play, außer du brauchst ausdrücklich Device Control. Beide
Apps stammen aus demselben Quellcode und können gleichzeitig installiert werden.

## Entscheidungshilfe

| Frage | Google Play | Sideload |
|---|---|---|
| Einfache Installation und automatische Updates? | Ja | Nein |
| Chat, Profile, Manage und Voice? | Ja | Ja |
| Terminal, Medien und Benachrichtigungen mit Relay? | Ja | Ja |
| Bildschirm lesen oder aufnehmen? | Nein | Ja |
| Tippen, schreiben, wischen und Apps bedienen? | Nein | Ja |

## App-Version und Relay sind getrennte Entscheidungen

Die **App-Version** bestimmt, ob Android Device Control enthält. Das optionale
**Relay-Plugin** verbindet Terminal, Medien, Benachrichtigungen und
Gerätekanäle mit dem Hermes-Host.

Device Control funktioniert nur mit **Sideload + gepaartem Relay**. Chat,
Manage und Standard-Voice benötigen keines von beiden.

## Später wechseln

Google Play und Sideload verwenden unterschiedliche Anwendungs-IDs. Du kannst
beide testen und eine Variante später entfernen. Einstellungen und Paarungen
werden pro App gespeichert.

[Google Play öffnen](https://play.google.com/store/apps/details?id=com.axiomlabs.hermesrelay) ·
[Sideload-APK herunterladen](https://github.com/Codename-11/hermes-relay/releases) ·
[Vollständiger englischer Vergleich →](/guide/release-tracks)
