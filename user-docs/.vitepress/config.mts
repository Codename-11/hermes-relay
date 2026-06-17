import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

import { defineConfig } from 'vitepress'

// Single source of truth for the displayed version: read appVersionName from
// the Android version catalog at docs-build time so the nav badge can never
// drift from the shipped app. Returns '' (badge hidden) on any failure.
function resolveAppVersion(): string {
  try {
    const tomlPath = fileURLToPath(new URL('../../gradle/libs.versions.toml', import.meta.url))
    const match = readFileSync(tomlPath, 'utf-8').match(/^appVersionName\s*=\s*"([^"]+)"/m)
    return match ? match[1] : ''
  } catch {
    return ''
  }
}

const appVersion = resolveAppVersion()

export default defineConfig({
  base: '/hermes-relay/',
  title: 'Hermes-Relay',
  description: 'Hermes-Relay — the Android companion and remote-hands CLI for your Hermes agent. Runs on your machine; lives on your devices.',

  head: [
    // Favicon — base path is NOT auto-applied to head entries in VitePress,
    // so hard-prefix with /hermes-relay/ to match the GitHub Pages deploy.
    ['link', { rel: 'icon', type: 'image/svg+xml', href: '/hermes-relay/logo.svg' }],
    ['link', { rel: 'apple-touch-icon', href: '/hermes-relay/logo.svg' }],

    // Canonical
    ['link', { rel: 'canonical', href: 'https://codename-11.github.io/hermes-relay/' }],

    // Open Graph — crawlers (Facebook, Messenger, Slack, Discord, LinkedIn) need absolute URLs
    ['meta', { property: 'og:type', content: 'website' }],
    ['meta', { property: 'og:site_name', content: 'Hermes-Relay' }],
    ['meta', { property: 'og:title', content: 'Hermes-Relay — give your Hermes agent hands' }],
    ['meta', { property: 'og:description', content: 'Runs on your machine. Lives on your devices. A native Android companion for chat, voice, and phone control — plus a single-binary CLI the agent uses to work on any machine you pair.' }],
    ['meta', { property: 'og:url', content: 'https://codename-11.github.io/hermes-relay/' }],
    ['meta', { property: 'og:image', content: 'https://codename-11.github.io/hermes-relay/og-image.png' }],
    ['meta', { property: 'og:image:secure_url', content: 'https://codename-11.github.io/hermes-relay/og-image.png' }],
    ['meta', { property: 'og:image:type', content: 'image/png' }],
    ['meta', { property: 'og:image:width', content: '1024' }],
    ['meta', { property: 'og:image:height', content: '500' }],
    ['meta', { property: 'og:image:alt', content: 'Hermes-Relay — give your Hermes agent hands.' }],

    // Twitter / X Card
    ['meta', { name: 'twitter:card', content: 'summary_large_image' }],
    ['meta', { name: 'twitter:title', content: 'Hermes-Relay — give your Hermes agent hands' }],
    ['meta', { name: 'twitter:description', content: 'Android companion for chat, voice, and phone control + a single-binary CLI that gives your Hermes agent hands on any machine. One pair, every device.' }],
    ['meta', { name: 'twitter:image', content: 'https://codename-11.github.io/hermes-relay/og-image.png' }],
    ['meta', { name: 'twitter:image:alt', content: 'Hermes-Relay — give your Hermes agent hands.' }],

    // Theme — RelayRefresh.Background
    ['meta', { name: 'theme-color', content: '#08090D' }],

    // Fonts — Space Grotesk + Space Mono (shared DNA with ARC docs)
    ['link', { rel: 'preconnect', href: 'https://fonts.googleapis.com' }],
    ['link', { rel: 'preconnect', href: 'https://fonts.gstatic.com', crossorigin: '' }],
    ['link', { href: 'https://fonts.googleapis.com/css2?family=Space+Grotesk:wght@300;400;500;700&family=Space+Mono:wght@400;700&display=swap', rel: 'stylesheet' }],
  ],

  themeConfig: {
    logo: '/logo.svg',

    nav: [
      {
        text: 'Surfaces',
        items: [
          { text: 'Android (companion)', link: '/guide/' },
          { text: 'CLI (remote hands)', link: '/desktop/' },
        ],
      },
      { text: 'Features', link: '/features/' },
      { text: 'Architecture', link: '/architecture/' },
      { text: 'Reference', link: '/reference/api' },
      { text: 'GitHub', link: 'https://github.com/Codename-11/hermes-relay' },
      { text: 'Privacy', link: '/privacy' },
      // Release badge — derived from gradle/libs.versions.toml, links to the
      // GitHub Releases list. Hidden if the version couldn't be resolved.
      ...(appVersion
        ? [{ text: `v${appVersion}`, link: 'https://github.com/Codename-11/hermes-relay/releases' }]
        : []),
    ],

    sidebar: {
      '/guide/': [
        {
          text: 'Android (phone control)',
          items: [
            { text: 'Overview', link: '/guide/' },
            { text: 'Quick Start', link: '/guide/quick-start' },
            { text: 'Installation & Setup', link: '/guide/getting-started' },
            { text: 'Remote access', link: '/guide/remote-access' },
            { text: 'Release tracks', link: '/guide/release-tracks' },
            { text: 'Chat', link: '/guide/chat' },
            { text: 'Sessions', link: '/guide/sessions' },
            { text: 'Troubleshooting', link: '/guide/troubleshooting' },
          ],
        },
        {
          text: 'Looking for the CLI?',
          items: [
            { text: 'CLI overview', link: '/desktop/' },
          ],
        },
      ],
      '/features/': [
        {
          text: 'Features',
          items: [
            { text: 'Overview', link: '/features/' },
            { text: 'Standard Chat Transport', link: '/features/direct-api' },
            { text: 'Markdown Rendering', link: '/features/markdown' },
            { text: 'Reasoning Display', link: '/features/reasoning' },
            { text: 'Connections', link: '/features/connections' },
            { text: 'Profiles', link: '/features/profiles' },
            { text: 'Personalities', link: '/features/personalities' },
            { text: 'Voice Mode', link: '/features/voice' },
            { text: 'Voice Intents', link: '/features/voice-intents' },
            { text: 'Token Tracking', link: '/features/tokens' },
            { text: 'Tool Progress', link: '/features/tools' },
            { text: 'Phone Control Tools', link: '/features/phone-control-tools' },
            { text: 'Dashboard Plugin', link: '/features/dashboard' },
          ],
        },
      ],
      '/architecture/': [
        {
          text: 'Architecture',
          items: [
            { text: 'Overview', link: '/architecture/' },
            { text: 'Relay Architecture Spec', link: '/architecture/relay-architecture-spec' },
            { text: 'Flavor Differences', link: '/architecture/flavor-differences' },
            { text: 'Decisions', link: '/architecture/decisions' },
            { text: 'Security', link: '/architecture/security' },
            { text: 'Privacy', link: '/architecture/privacy' },
          ],
        },
      ],
      '/reference/': [
        {
          text: 'Reference',
          items: [
            { text: 'Hermes API', link: '/reference/api' },
            { text: 'Configuration', link: '/reference/configuration' },
            { text: 'Relay Server', link: '/reference/relay-server' },
            { text: 'Agent Cleanup Prompt', link: '/reference/agent-cleanup-prompt' },
          ],
        },
      ],
      '/desktop/': [
        {
          // Sidebar header carries the experimental marker so it's visible
          // on every page in the section, not just the overview.
          text: 'CLI (remote hands) · Experimental',
          items: [
            { text: 'Overview', link: '/desktop/' },
            { text: 'Installation', link: '/desktop/installation' },
            { text: 'Pairing', link: '/desktop/pairing' },
            { text: 'Subcommands', link: '/desktop/subcommands' },
            { text: 'Local tool routing', link: '/desktop/tools' },
            { text: 'Troubleshooting', link: '/desktop/troubleshooting' },
            { text: 'FAQ', link: '/desktop/faq' },
          ],
        },
        {
          text: 'Looking for the Android app?',
          items: [
            { text: 'Android overview', link: '/guide/' },
          ],
        },
      ],
    },

    socialLinks: [
      { icon: 'github', link: 'https://github.com/Codename-11/hermes-relay' },
    ],

    footer: {
      message: 'Built for <a href="https://hermes-agent.nousresearch.com">Hermes Agent</a> by <a href="https://codename-11.dev">Axiom-Labs</a>',
      copyright: 'MIT License',
    },

    search: {
      provider: 'local',
    },
  },

  vite: {
    ssr: {
      noExternal: ['@vue-flow/core'],
    },
  },
})
