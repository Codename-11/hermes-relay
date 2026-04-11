"""Channel handlers for the Hermes-Relay WSS server.

Each handler owns the state and message routing for one channel:
- ``chat``: proxy to Hermes WebAPI, session-scoped streaming
- ``terminal``: PTY-backed interactive shell (Unix only)
- ``bridge``: Android-side HTTP/tool bridge (phone-driven)
"""
