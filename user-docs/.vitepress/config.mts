import { defineConfig } from 'vitepress'

export default defineConfig({
  title: 'Hermes Relay',
  description: 'Android app for Hermes Agent — chat, control, and connect from your phone',

  head: [
    // Favicon
    ['link', { rel: 'icon', type: 'image/svg+xml', href: '/logo.svg' }],
    ['link', { rel: 'apple-touch-icon', href: '/logo.svg' }],

    // Open Graph
    ['meta', { property: 'og:type', content: 'website' }],
    ['meta', { property: 'og:title', content: 'Hermes Relay — Android App for Hermes Agent' }],
    ['meta', { property: 'og:description', content: 'Chat, control, and connect — a native Android app for Hermes Agent. Direct API streaming, session management, tool visualization.' }],

    // Twitter Card
    ['meta', { name: 'twitter:card', content: 'summary' }],
    ['meta', { name: 'twitter:title', content: 'Hermes Relay' }],
    ['meta', { name: 'twitter:description', content: 'Native Android app for Hermes Agent' }],

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
      { text: 'GitHub', link: 'https://github.com/NousResearch/hermes-agent' },
    ],

    sidebar: {
      '/guide/': [
        {
          text: 'Getting Started',
          items: [
            { text: 'What is Hermes Relay?', link: '/guide/' },
            { text: 'Installation & Setup', link: '/guide/getting-started' },
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
            { text: 'Personalities', link: '/features/personalities' },
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
      { icon: 'github', link: 'https://github.com/NousResearch/hermes-agent' },
    ],

    footer: {
      message: 'Built for <a href="https://hermes-agent.nousresearch.com">Hermes Agent</a> by <a href="https://axiom-labs.cloud">Axiom-Labs</a>',
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
