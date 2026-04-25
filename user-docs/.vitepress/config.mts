import { defineConfig } from 'vitepress'

export default defineConfig({
  base: '/hermes-relay/',
  title: 'Hermes-Relay',
  description: 'Hermes-Relay — Android phone control + desktop terminal client for the Hermes agent platform',

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
    ['meta', { property: 'og:title', content: 'Hermes-Relay — Phone control + desktop terminal for Hermes Agent' }],
    ['meta', { property: 'og:description', content: 'Two surfaces for one Hermes agent: a native Android app for phone control + a desktop CLI that lets you use a server-deployed Hermes from your laptop as if it were local.' }],
    ['meta', { property: 'og:url', content: 'https://codename-11.github.io/hermes-relay/' }],
    ['meta', { property: 'og:image', content: 'https://codename-11.github.io/hermes-relay/og-image.png' }],
    ['meta', { property: 'og:image:secure_url', content: 'https://codename-11.github.io/hermes-relay/og-image.png' }],
    ['meta', { property: 'og:image:type', content: 'image/png' }],
    ['meta', { property: 'og:image:width', content: '1024' }],
    ['meta', { property: 'og:image:height', content: '500' }],
    ['meta', { property: 'og:image:alt', content: 'Hermes-Relay — Phone control + desktop terminal for Hermes Agent.' }],

    // Twitter / X Card
    ['meta', { name: 'twitter:card', content: 'summary_large_image' }],
    ['meta', { name: 'twitter:title', content: 'Hermes-Relay — Phone control + desktop terminal for Hermes Agent' }],
    ['meta', { name: 'twitter:description', content: 'Android remote-control app + desktop CLI for the Hermes agent platform — one pair, two surfaces.' }],
    ['meta', { name: 'twitter:image', content: 'https://codename-11.github.io/hermes-relay/og-image.png' }],
    ['meta', { name: 'twitter:image:alt', content: 'Hermes-Relay — Phone control + desktop terminal for Hermes Agent.' }],

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
      {
        text: 'Surfaces',
        items: [
          { text: 'Android (phone control)', link: '/guide/' },
          { text: 'Desktop CLI (terminal)', link: '/desktop/' },
        ],
      },
      { text: 'Features', link: '/features/' },
      { text: 'Architecture', link: '/architecture/' },
      { text: 'Reference', link: '/reference/api' },
      { text: 'GitHub', link: 'https://github.com/Codename-11/hermes-relay' },
      { text: 'Privacy', link: '/privacy' },
    ],

    sidebar: {
      '/guide/': [
        {
          text: 'Android (phone control)',
          items: [
            { text: 'Overview', link: '/guide/' },
            { text: 'Installation & Setup', link: '/guide/getting-started' },
            { text: 'Release tracks', link: '/guide/release-tracks' },
            { text: 'Chat', link: '/guide/chat' },
            { text: 'Sessions', link: '/guide/sessions' },
            { text: 'Troubleshooting', link: '/guide/troubleshooting' },
          ],
        },
        {
          text: 'Looking for the desktop CLI?',
          items: [
            { text: 'Desktop CLI overview', link: '/desktop/' },
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
            { text: 'Connections', link: '/features/connections' },
            { text: 'Profiles', link: '/features/profiles' },
            { text: 'Personalities', link: '/features/personalities' },
            { text: 'Token Tracking', link: '/features/tokens' },
            { text: 'Tool Progress', link: '/features/tools' },
            { text: 'Dashboard Plugin', link: '/features/dashboard' },
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
      '/desktop/': [
        {
          // Sidebar header carries the experimental marker so it's visible
          // on every page in the section, not just the overview.
          text: 'Desktop CLI (terminal) · Experimental',
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
