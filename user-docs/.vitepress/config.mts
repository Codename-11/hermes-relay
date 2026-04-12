import { defineConfig } from 'vitepress'

export default defineConfig({
  base: '/hermes-relay/',
  title: 'Hermes-Relay',
  description: 'Android app for Hermes Agent — chat, control, and connect from your phone',

  head: [
    // Favicon
    ['link', { rel: 'icon', type: 'image/svg+xml', href: '/logo.svg' }],
    ['link', { rel: 'apple-touch-icon', href: '/logo.svg' }],

    // Canonical
    ['link', { rel: 'canonical', href: 'https://codename-11.github.io/hermes-relay/' }],

    // Open Graph — crawlers (Facebook, Messenger, Slack, Discord, LinkedIn) need absolute URLs
    ['meta', { property: 'og:type', content: 'website' }],
    ['meta', { property: 'og:site_name', content: 'Hermes-Relay' }],
    ['meta', { property: 'og:title', content: 'Hermes-Relay — Android App for Hermes Agent' }],
    ['meta', { property: 'og:description', content: 'Chat, control, and connect — a native Android app for Hermes Agent. Direct API streaming, session management, tool visualization.' }],
    ['meta', { property: 'og:url', content: 'https://codename-11.github.io/hermes-relay/' }],
    ['meta', { property: 'og:image', content: 'https://codename-11.github.io/hermes-relay/og-image.png' }],
    ['meta', { property: 'og:image:secure_url', content: 'https://codename-11.github.io/hermes-relay/og-image.png' }],
    ['meta', { property: 'og:image:type', content: 'image/png' }],
    ['meta', { property: 'og:image:width', content: '1024' }],
    ['meta', { property: 'og:image:height', content: '500' }],
    ['meta', { property: 'og:image:alt', content: 'Hermes-Relay — Your AI agent, in your pocket. Chat, Terminal, Bridge.' }],

    // Twitter / X Card
    ['meta', { name: 'twitter:card', content: 'summary_large_image' }],
    ['meta', { name: 'twitter:title', content: 'Hermes-Relay — Android App for Hermes Agent' }],
    ['meta', { name: 'twitter:description', content: 'Chat, control, and connect — a native Android app for Hermes Agent.' }],
    ['meta', { name: 'twitter:image', content: 'https://codename-11.github.io/hermes-relay/og-image.png' }],
    ['meta', { name: 'twitter:image:alt', content: 'Hermes-Relay — Your AI agent, in your pocket.' }],

    // Theme
    ['meta', { name: 'theme-color', content: '#000000' }],

    // Fonts — Space Grotesk + Space Mono (shared DNA with ARC docs)
    ['link', { rel: 'preconnect', href: 'https://fonts.googleapis.com' }],
    ['link', { rel: 'preconnect', href: 'https://fonts.gstatic.com', crossorigin: '' }],
    ['link', { href: 'https://fonts.googleapis.com/css2?family=Space+Grotesk:wght@300;400;500;700&family=Space+Mono:wght@400;700&display=swap', rel: 'stylesheet' }],
  ],

  themeConfig: {
    logo: '/logo.svg',

    nav: [
      { text: 'Guide', link: '/guide/' },
      { text: 'Features', link: '/features/' },
      { text: 'Architecture', link: '/architecture/' },
      { text: 'Reference', link: '/reference/api' },
      { text: 'GitHub', link: 'https://github.com/Codename-11/hermes-relay' },
      { text: 'Privacy', link: '/privacy' },
    ],

    sidebar: {
      '/guide/': [
        {
          text: 'Getting Started',
          items: [
            { text: 'What is Hermes-Relay?', link: '/guide/' },
            { text: 'Installation & Setup', link: '/guide/getting-started' },
            { text: 'Release tracks', link: '/guide/release-tracks' },
            { text: 'Chat', link: '/guide/chat' },
            { text: 'Sessions', link: '/guide/sessions' },
            { text: 'Troubleshooting', link: '/guide/troubleshooting' },
          ],
        },
      ],
      '/features/': [
        {
          text: 'Features',
          items: [
            { text: 'Overview', link: '/features/' },
            { text: 'Direct API Connection', link: '/features/direct-api' },
            { text: 'Markdown Rendering', link: '/features/markdown' },
            { text: 'Reasoning Display', link: '/features/reasoning' },
            { text: 'Profiles & Personalities', link: '/features/personalities' },
            { text: 'Token Tracking', link: '/features/tokens' },
            { text: 'Tool Progress', link: '/features/tools' },
          ],
        },
      ],
      '/architecture/': [
        {
          text: 'Architecture',
          items: [
            { text: 'Overview', link: '/architecture/' },
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
