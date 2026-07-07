---
layout: home

hero:
  name: Hermes-Relay
  text: Runs on your machine. Lives on your devices.
  tagline: Pair your Hermes agent with the devices around you — a native Android companion for chat, voice, and full phone control, plus a single-binary CLI that gives the agent hands on any machine you put it on.
  actions:
    - theme: brand
      text: Get the Android app
      link: /guide/getting-started
    - theme: alt
      text: Give it hands — CLI
      link: /desktop/

features:
  - icon:
      src: /icons/chat.svg
      width: 40
      height: 40
    title: Chat that streams, not spins
    details: Rides your dashboard's live gateway WebSocket when signed in — live thinking included — and falls back to direct HTTP/SSE against the API server. Markdown, syntax-highlighted code, reasoning blocks, tool-progress cards. No cloud relay in the path.
  - icon:
      src: /icons/personalities.svg
      width: 40
      height: 40
    title: Hands-free voice
    details: Talk to your agent using your server's own TTS/STT — or opt into the provider-native Realtime Agent for low-latency, barge-in conversation.
  - icon:
      src: /icons/sessions.svg
      width: 40
      height: 40
    title: Manage from your pocket
    details: Skills, cron jobs, profiles, models, and keys — your server's dashboard controls, rebuilt native. One sign-in unlocks Manage and voice.
  - icon:
      src: /icons/tools.svg
      width: 40
      height: 40
    title: Your phone, on the agent's toolbelt
    details: With the Relay plugin paired and the Sideload build, the agent can read the screen, tap, type, and navigate apps — fenced by a per-app blocklist, destructive-verb confirmation, and auto-disable.
    link: /guide/release-tracks#how-the-pieces-combine
    linkText: How the pieces combine
  - icon:
      src: /icons/tokens.svg
      width: 40
      height: 40
    title: Hands on any machine
    details: Install the single-binary CLI — desktop, laptop, or headless box — and the agent can read, write, search, run commands, and capture screens there. Consent-gated per device.
  - icon:
      src: /icons/markdown.svg
      width: 40
      height: 40
    title: Works at home and away
    details: Connections carry LAN, Tailscale, and public routes, and the app picks the best one on every connect and network change — home, train, or VPN, it just works.
  - icon:
      src: /icons/reasoning.svg
      width: 40
      height: 40
    title: Notifications and media in the loop
    details: Forward phone notifications to your agent, and let it hand files, images, and rich cards back into chat — shareable through native Android flows.
  - icon:
      src: /icons/security.svg
      width: 40
      height: 40
    title: Private by architecture
    details: No cloud in the path — QR pairing, Keystore-held tokens, TOFU cert pinning, per-channel grants with TTLs you choose, revocable from any client.
---

<!-- Home body intentionally empty — the sphere, How-it-works strip, surface
     cards, and Get-started section are slotted via .vitepress/theme/index.ts
     (markdown body always renders below the VPFeatures grid, which is the
     wrong place for all of them). -->
