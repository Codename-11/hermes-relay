---
translation_status: ai-translated
canonical_source: /guide/troubleshooting
---

# Fehlerbehebung

Beginne mit dem sichtbaren Symptom. So lässt sich schnell trennen, ob Android,
das Netzwerk oder der Hermes-Host die Ursache ist.

- [Roter Punkt oder keine Verbindung](#keine-verbindung)
- [„No reachable endpoint“](#kein-erreichbarer-endpunkt)
- [Chat streamt nicht](#chat-streamt-nicht)
- [Manage oder Voice verlangt eine Anmeldung](#manage-und-voice)
- [Sitzungen fehlen](#sitzungen-fehlen)
- [App stürzt beim Start ab](#startabsturz)

## Keine Verbindung

1. Prüfe auf dem Host, ob `hermes dashboard` läuft.
2. Öffne auf dem **Telefon** die Dashboard/Gateway-Adresse, normalerweise `http://<host>:9119`.
3. Prüfe die Firewall und melde dich erneut an, damit Android ein frisches `/api/ws`-Ticket erhält.
4. Verwende vom Telefon niemals `localhost` oder `127.0.0.1`; diese Adressen zeigen auf das Telefon selbst.

## Kein erreichbarer Endpunkt

Die App hat alle gespeicherten LAN-, Tailscale- und öffentlichen Routen geprüft,
aber keine Antwort erhalten. Eine LAN-Adresse funktioniert nur im selben WLAN.
Für Tailscale müssen Telefon und Server verbunden sein.
Eine Dashboard-Route wie `http://100.x.y.z:9119` wird ohne API-Server oder API-Schlüssel geprüft.

## Chat streamt nicht

- Prüfe Dashboard/Gateway-URL, Anmeldung und `/api/ws`.
- Tippe bei einem Fehlerbanner einmal auf **Retry**.
- Prüfe die Hermes-Serverprotokolle.
- Ein optionaler API-Fallback wird separat geprüft; sein Ausfall blockiert eine gesunde Gateway-Verbindung nicht.
- Bei langen lokalen Modellläufen kann Android die Verbindung im Hintergrund trennen; die fertige Antwort wird nach der Wiederverbindung geladen.

Wenn der bewusst konfigurierte API-Fallback nicht verfügbar ist, prüfe
`API_SERVER_ENABLED`, die Bind-Adresse, `http://<host>:8642/health`, den
vom Serverbetreiber erstellten `API_SERVER_KEY` und die Firewall. Die Dashboard-Anmeldung erzeugt diesen Schlüssel nicht.

## Manage und Voice

Manage und Standard-Voice verwenden die Dashboard-Anmeldung, nicht den
`API_SERVER_KEY`. Melde dich einmal im Manage-Bereich an und prüfe, ob das
Dashboard vom Telefon erreichbar ist.

## Sitzungen fehlen

Der Server muss beim Wechsel der Sitzung erreichbar sein. Große Sitzungen
können kurz laden; warte auf die Ladeanzeige, bevor du erneut wechselst.

## Startabsturz

Öffne Android **Einstellungen → Apps → Hermes-Relay → Speicher** und lösche die
App-Daten. Richte danach die Dashboard/Gateway-Adresse und Anmeldung erneut ein.

Für Relay-Probleme liefert `hermes relay doctor` eine schreibgeschützte Diagnose.

[Vollständige englische Fehlerbehebung →](/guide/troubleshooting) ·
[Installation →](/de/guide/getting-started)
