# Bot voice ownership

The Bot Chat composer opens a call for the canonical Bot Chat session. Its
VoiceViewModel, recorder and playback resources belong to that mounted bot route;
leaving the route, backgrounding the Activity, or losing its exact loaded session
ends the call. Microphone permission alone never starts a call without the
pending foreground Talk action.

Standard STT/TTS and GPT-Live use the bot's authenticated Dashboard client and
profile, including when that bot belongs to a different saved connection. GPT-Live
receives only this bot's loaded history and delegates through this bot's dedicated
ChatViewModel and leased Gateway. It never uses the main Chat voice runtime.
The call cannot start while history is loading or its session does not match.

Relay Realtime Agent and forced Relay audio cannot establish ownership of this
canonical Gateway session. Those selections fall back to standard Hermes voice
with a visible explanation, without changing saved preferences. A host configured
for GPT-Live can still supply its profile-scoped frontend. Existing upstream audio
and Gateway contracts are reused; no bot voice endpoint is introduced.

The call reuses the regular voice microphone controls, including hold and
continuous modes. GPT-Live uses an explicit Start/Stop microphone toggle; Stop
disables the WebRTC audio track while preserving its conversation. The spoken new-chat command uses canonical Bot Chat's `/new`
compaction path and cannot create a separate session.
