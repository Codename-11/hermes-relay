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

type LocalizedThemeCopy = {
  label: string
  selectText: string
  quickStart: string
  installation: string
  releaseTracks: string
  troubleshooting: string
  englishReference: string
  overview: string
  coreGuides: string
  canonicalReference: string
  fullGuide: string
  remoteAccess: string
  apiReference: string
  outlineTitle: string
  previous: string
  next: string
  returnToTop: string
  menu: string
  appearance: string
  lastUpdated: string
}

function localizedTheme(prefix: string, copy: LocalizedThemeCopy) {
  return {
    label: copy.label,
    selectText: copy.selectText,
    nav: [
      { text: copy.quickStart, link: `/${prefix}/guide/quick-start` },
      { text: copy.installation, link: `/${prefix}/guide/getting-started` },
      { text: copy.troubleshooting, link: `/${prefix}/guide/troubleshooting` },
      { text: copy.englishReference, link: '/reference/api' },
      { text: 'GitHub', link: 'https://github.com/Codename-11/hermes-relay' },
    ],
    sidebar: {
      [`/${prefix}/`]: [
        {
          text: copy.coreGuides,
          items: [
            { text: copy.overview, link: `/${prefix}/` },
            { text: copy.quickStart, link: `/${prefix}/guide/quick-start` },
            { text: copy.installation, link: `/${prefix}/guide/getting-started` },
            { text: copy.releaseTracks, link: `/${prefix}/guide/release-tracks` },
            { text: copy.troubleshooting, link: `/${prefix}/guide/troubleshooting` },
          ],
        },
        {
          text: copy.canonicalReference,
          items: [
            { text: copy.fullGuide, link: '/guide/' },
            { text: copy.remoteAccess, link: '/guide/remote-access' },
            { text: copy.apiReference, link: '/reference/api' },
          ],
        },
      ],
    },
    outlineTitle: copy.outlineTitle,
    docFooter: { prev: copy.previous, next: copy.next },
    returnToTopLabel: copy.returnToTop,
    sidebarMenuLabel: copy.menu,
    darkModeSwitchLabel: copy.appearance,
    lastUpdatedText: copy.lastUpdated,
  }
}

const localizedThemes = {
  de: localizedTheme('de', {
    label: 'Deutsch', selectText: 'Sprachen', quickStart: 'Schnellstart',
    installation: 'Installation', releaseTracks: 'App-Versionen', troubleshooting: 'Fehlerbehebung',
    englishReference: 'Englische Referenz', overview: 'Übersicht', coreGuides: 'Erste Schritte',
    canonicalReference: 'Englische Referenz', fullGuide: 'Vollständige Anleitung', remoteAccess: 'Fernzugriff',
    apiReference: 'API-Referenz', outlineTitle: 'Auf dieser Seite', previous: 'Zurück', next: 'Weiter',
    returnToTop: 'Nach oben', menu: 'Menü', appearance: 'Darstellung', lastUpdated: 'Zuletzt aktualisiert',
  }),
  es: localizedTheme('es', {
    label: 'Español', selectText: 'Idiomas', quickStart: 'Inicio rápido',
    installation: 'Instalación', releaseTracks: 'Versiones de la app', troubleshooting: 'Solución de problemas',
    englishReference: 'Referencia en inglés', overview: 'Descripción general', coreGuides: 'Primeros pasos',
    canonicalReference: 'Referencia en inglés', fullGuide: 'Guía completa', remoteAccess: 'Acceso remoto',
    apiReference: 'Referencia de API', outlineTitle: 'En esta página', previous: 'Anterior', next: 'Siguiente',
    returnToTop: 'Volver arriba', menu: 'Menú', appearance: 'Apariencia', lastUpdated: 'Última actualización',
  }),
  ja: localizedTheme('ja', {
    label: '日本語', selectText: '言語', quickStart: 'クイックスタート',
    installation: 'インストール', releaseTracks: 'アプリの種類', troubleshooting: 'トラブルシューティング',
    englishReference: '英語リファレンス', overview: '概要', coreGuides: 'はじめに',
    canonicalReference: '英語リファレンス', fullGuide: '完全なガイド', remoteAccess: 'リモートアクセス',
    apiReference: 'API リファレンス', outlineTitle: 'このページの内容', previous: '前へ', next: '次へ',
    returnToTop: 'ページ上部へ', menu: 'メニュー', appearance: '外観', lastUpdated: '最終更新',
  }),
  'pt-BR': localizedTheme('pt-BR', {
    label: 'Português (Brasil)', selectText: 'Idiomas', quickStart: 'Início rápido',
    installation: 'Instalação', releaseTracks: 'Versões do app', troubleshooting: 'Solução de problemas',
    englishReference: 'Referência em inglês', overview: 'Visão geral', coreGuides: 'Primeiros passos',
    canonicalReference: 'Referência em inglês', fullGuide: 'Guia completo', remoteAccess: 'Acesso remoto',
    apiReference: 'Referência da API', outlineTitle: 'Nesta página', previous: 'Anterior', next: 'Próxima',
    returnToTop: 'Voltar ao topo', menu: 'Menu', appearance: 'Aparência', lastUpdated: 'Última atualização',
  }),
  'zh-CN': localizedTheme('zh-CN', {
    label: '简体中文', selectText: '语言', quickStart: '快速开始',
    installation: '安装与设置', releaseTracks: '应用版本', troubleshooting: '故障排除',
    englishReference: '英文参考', overview: '概览', coreGuides: '入门指南',
    canonicalReference: '英文参考', fullGuide: '完整用户指南', remoteAccess: '远程访问',
    apiReference: 'API 参考', outlineTitle: '本页内容', previous: '上一页', next: '下一页',
    returnToTop: '返回顶部', menu: '菜单', appearance: '外观', lastUpdated: '最后更新',
  }),
}

export default defineConfig({
  base: '/docs/',
  title: 'Hermes-Relay',
  description: 'Hermes-Relay — the Android companion and remote-hands CLI for your Hermes agent. Runs on your machine; lives on your devices.',

  locales: {
    root: { label: 'English', lang: 'en' },
    de: { label: 'Deutsch', lang: 'de', link: '/de/', themeConfig: localizedThemes.de },
    es: { label: 'Español', lang: 'es', link: '/es/', themeConfig: localizedThemes.es },
    ja: { label: '日本語', lang: 'ja', link: '/ja/', themeConfig: localizedThemes.ja },
    'pt-BR': { label: 'Português (Brasil)', lang: 'pt-BR', link: '/pt-BR/', themeConfig: localizedThemes['pt-BR'] },
    'zh-CN': { label: '简体中文', lang: 'zh-CN', link: '/zh-CN/', themeConfig: localizedThemes['zh-CN'] },
  },

  head: [
    // Favicon — base path is NOT auto-applied to head entries in VitePress,
    // so hard-prefix with /docs/ to match the production deployment.
    ['link', { rel: 'icon', type: 'image/svg+xml', href: '/docs/logo.svg' }],
    ['link', { rel: 'apple-touch-icon', href: '/docs/logo.svg' }],

    // Canonical
    ['link', { rel: 'canonical', href: 'https://hermes-relay.dev/docs/' }],

    // Open Graph — crawlers (Facebook, Messenger, Slack, Discord, LinkedIn) need absolute URLs
    ['meta', { property: 'og:type', content: 'website' }],
    ['meta', { property: 'og:site_name', content: 'Hermes-Relay' }],
    ['meta', { property: 'og:title', content: 'Hermes-Relay — give your Hermes agent hands' }],
    ['meta', { property: 'og:description', content: 'Runs on your machine. Lives on your devices. A native Android companion for chat, voice, and phone control — plus a single-binary CLI the agent uses to work on any machine you pair.' }],
    ['meta', { property: 'og:url', content: 'https://hermes-relay.dev/docs/' }],
    ['meta', { property: 'og:image', content: 'https://hermes-relay.dev/docs/og-image.png' }],
    ['meta', { property: 'og:image:secure_url', content: 'https://hermes-relay.dev/docs/og-image.png' }],
    ['meta', { property: 'og:image:type', content: 'image/png' }],
    ['meta', { property: 'og:image:width', content: '1024' }],
    ['meta', { property: 'og:image:height', content: '500' }],
    ['meta', { property: 'og:image:alt', content: 'Hermes-Relay — give your Hermes agent hands.' }],

    // Twitter / X Card
    ['meta', { name: 'twitter:card', content: 'summary_large_image' }],
    ['meta', { name: 'twitter:title', content: 'Hermes-Relay — give your Hermes agent hands' }],
    ['meta', { name: 'twitter:description', content: 'Android companion for chat, voice, and phone control + a single-binary CLI that gives your Hermes agent hands on any machine. One pair, every device.' }],
    ['meta', { name: 'twitter:image', content: 'https://hermes-relay.dev/docs/og-image.png' }],
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

    locales: {
      root: {
        label: 'English',
        selectText: 'Languages',
      },
      ...localizedThemes,
    },

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
            { text: 'Vanilla Hermes Chat Transport', link: '/features/direct-api' },
            { text: 'Markdown Rendering', link: '/features/markdown' },
            { text: 'Reasoning Display', link: '/features/reasoning' },
            { text: 'Connections', link: '/features/connections' },
            { text: 'Profiles', link: '/features/profiles' },
            { text: 'Personalities', link: '/features/personalities' },
            { text: 'App Themes', link: '/features/themes' },
            { text: 'Custom Avatars', link: '/features/custom-avatars' },
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
            { text: 'Is my connection secure?', link: '/architecture/connection-security' },
            { text: 'Privacy', link: '/architecture/privacy' },
          ],
        },
      ],
      '/reference/': [
        {
          text: 'Reference',
          items: [
            { text: 'API & Route Contract', link: '/reference/api' },
            { text: 'Upstream Hermes', link: '/reference/upstream-hermes' },
            { text: 'Relay API', link: '/reference/relay-api' },
            { text: 'Compatibility', link: '/reference/compatibility' },
            { text: 'Configuration', link: '/reference/configuration' },
            { text: 'Relay Server Operations', link: '/reference/relay-server' },
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
