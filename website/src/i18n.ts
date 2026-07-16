export const locales = ['en', 'de', 'es', 'ja', 'pt-BR', 'zh-CN'] as const;
export type Locale = (typeof locales)[number];

export const localizedLocales = locales.filter((locale): locale is Exclude<Locale, 'en'> => locale !== 'en');

export const localeInfo: Record<Locale, { label: string; path: string; docsPath: string; ogLocale: string }> = {
  en: { label: 'English', path: '/', docsPath: '/docs/', ogLocale: 'en_US' },
  de: { label: 'Deutsch', path: '/de/', docsPath: '/docs/de/', ogLocale: 'de_DE' },
  es: { label: 'Español', path: '/es/', docsPath: '/docs/es/', ogLocale: 'es_ES' },
  ja: { label: '日本語', path: '/ja/', docsPath: '/docs/ja/', ogLocale: 'ja_JP' },
  'pt-BR': { label: 'Português (Brasil)', path: '/pt-BR/', docsPath: '/docs/pt-BR/', ogLocale: 'pt_BR' },
  'zh-CN': { label: '简体中文', path: '/zh-CN/', docsPath: '/docs/zh-CN/', ogLocale: 'zh_CN' },
};

type Copy = {
  metadata: { title: string; description: string; imageAlt: string; features: string[] };
  header: {
    home: string; release: string; tagline: string; product: string; android: string; docs: string;
    mainNav: string; mobileNav: string; openNav: string; language: string; lightTheme: string; darkTheme: string;
  };
  hero: {
    eyebrow: string; title: string; accent: string; lede: string; androidCta: string; cliCta: string;
    openSource: string; selfHosted: string; noCloud: string; preview: string; imageAlt: string; route: string;
  };
  surfaces: {
    kicker: string; title: string; lede: string; androidTitle: string; androidLede: string;
    chatDescription: string; voiceDescription: string; manageDescription: string; additional: string;
    cliTitle: string; cliLede: string; terminal: string; consentTitle: string; consentBody: string;
    capabilities: string; sessionsTitle: string; sessionsBody: string; daemonTitle: string; daemonBody: string;
    auditTitle: string; auditBody: string; meetCli: string;
  };
  device: {
    eyebrow: string; title: string; lede: string; capabilities: string; read: string; tap: string; type: string;
    gateTitle: string; gateBody: string; link: string; preview: string;
  };
  setup: {
    eyebrow: string; title: string; body: string; resources: string; androidCta: string; quickStart: string; plugin: string;
  };
  privacy: { aria: string; title: string; body: string; learn: string; support: string };
  footer: {
    tagline: string; product: string; android: string; features: string; resources: string; quickStart: string;
    plugin: string; docs: string; project: string; releases: string; contributing: string; security: string;
    builtFor: string; by: string; license: string;
  };
};

export const translations: Record<Locale, Copy> = {
  en: {
    metadata: {
      title: 'Hermes-Relay — Your Hermes agent, wherever you are',
      description: 'Hermes-Relay puts your self-hosted Hermes agent on Android and gives it consent-gated access to nearby machines.',
      imageAlt: 'Hermes-Relay — your Hermes agent on Android and nearby machines',
      features: ['Streaming chat', 'Realtime voice', 'Hermes management', 'Desktop CLI', 'Sideload device control'],
    },
    header: {
      home: 'Hermes-Relay home', release: 'current Android release', tagline: 'Android + CLI for Hermes',
      product: 'Product', android: 'Android', docs: 'Docs', mainNav: 'Main navigation', mobileNav: 'Mobile navigation',
      openNav: 'Open navigation', language: 'Language', lightTheme: 'Switch to light theme', darkTheme: 'Switch to dark theme',
    },
    hero: {
      eyebrow: 'Connected to your Hermes', title: 'Your Hermes agent.', accent: 'Wherever you are.',
      lede: 'Chat, speak, manage, and give your agent hands — from the devices already around you.',
      androidCta: 'Get the Android app', cliCta: 'Explore the CLI', openSource: 'Open source', selfHosted: 'Self-hosted',
      noCloud: 'No cloud relay', preview: 'Hermes-Relay Android chat screen', imageAlt: 'Hermes-Relay chat running on Android',
      route: 'gateway · LAN · direct',
    },
    surfaces: {
      kicker: 'Direct by design', title: 'One agent. Every surface.',
      lede: 'Hermes-Relay extends your agent to your phone and nearby machines. Connections stay direct, local-first, and under your control.',
      androidTitle: 'Chat, voice, and power features in your pocket.',
      androidLede: 'Take conversations on the go, speak naturally, and manage the Hermes you already run.',
      chatDescription: 'Streaming conversations and live agent work.', voiceDescription: 'Speak naturally, interrupt, and keep moving.',
      manageDescription: 'Profiles, skills, models, and automations.', additional: 'Additional Android surfaces',
      cliTitle: 'Consent-gated access on your machine.', cliLede: 'Give Hermes hands on the machine — only when you allow it.',
      terminal: 'Hermes-Relay command line preview', consentTitle: 'Consent is explicit and local.',
      consentBody: 'You decide when Hermes can run commands, read files, or make changes.', capabilities: 'CLI capabilities',
      sessionsTitle: 'Pick up where you left off.', sessionsBody: 'Reconnect to named tmux sessions with recent scrollback already in place.',
      daemonTitle: 'Available in the background.', daemonBody: 'Keep approved desktop tools ready through the headless daemon.',
      auditTitle: 'See what ran on your machine.', auditBody: 'Review the local record of every desktop tool call—even while offline.',
      meetCli: 'Meet the CLI',
    },
    device: {
      eyebrow: 'Sideload + Relay plugin', title: 'Give Hermes hands on your phone.',
      lede: 'Let your agent read the screen, tap, type, scroll, and capture screenshots—while every action stays visible and under your control.',
      capabilities: 'Device Control capabilities', read: 'Read the screen', tap: 'Tap and scroll', type: 'Type for you',
      gateTitle: 'Powerful by design. Gated on purpose.',
      gateBody: 'Requires the Sideload app and a paired Relay plugin. It is not included in the Google Play build.',
      link: 'See how Device Control works', preview: 'Recreation of the Hermes-Relay Device Control screen',
    },
    setup: {
      eyebrow: 'Quick setup · Additive by design', title: 'Start simple. Add the plugin when you want more.',
      body: 'Chat, Manage, and standard voice work with unmodified Hermes. The optional Relay plugin adds terminal, enhanced voice, notifications, media handoff, desktop tools, and the path to Device Control.',
      resources: 'Setup resources', androidCta: 'Get the Android app', quickStart: 'Quick start', plugin: 'Explore the Relay plugin',
    },
    privacy: {
      aria: 'Privacy and open source', title: 'Built for privacy. Powered by open source.',
      body: 'No cloud relay. No lock-in. Run everything on your terms.', learn: 'See how it works', support: 'Support the project',
    },
    footer: {
      tagline: 'Runs on your machine. Lives on your devices.', product: 'Product', android: 'Android app', features: 'Features',
      resources: 'Resources', quickStart: 'Quick start', plugin: 'Relay plugin', docs: 'Docs', project: 'Project',
      releases: 'Releases', contributing: 'Contributing', security: 'Security', builtFor: 'Built for', by: 'by', license: 'MIT License',
    },
  },
  de: {
    metadata: {
      title: 'Hermes-Relay — Dein Hermes Agent, wo immer du bist',
      description: 'Hermes-Relay bringt deinen selbst gehosteten Hermes Agent auf Android und gibt ihm zustimmungspflichtigen Zugriff auf Geräte in deiner Nähe.',
      imageAlt: 'Hermes-Relay — dein Hermes Agent auf Android und Geräten in deiner Nähe',
      features: ['Streaming-Chat', 'Echtzeit-Sprache', 'Hermes-Verwaltung', 'Desktop-CLI', 'Device Control per Sideload'],
    },
    header: {
      home: 'Hermes-Relay Startseite', release: 'aktuelle Android-Version', tagline: 'Android + CLI für Hermes',
      product: 'Produkt', android: 'Android', docs: 'Dokumentation', mainNav: 'Hauptnavigation', mobileNav: 'Mobile Navigation',
      openNav: 'Navigation öffnen', language: 'Sprache', lightTheme: 'Zum hellen Design wechseln', darkTheme: 'Zum dunklen Design wechseln',
    },
    hero: {
      eyebrow: 'Mit deinem Hermes verbunden', title: 'Dein Hermes Agent.', accent: 'Wo immer du bist.',
      lede: 'Chatte, sprich, verwalte und gib deinem Agenten Hände — über die Geräte, die dich bereits umgeben.',
      androidCta: 'Android-App installieren', cliCta: 'CLI entdecken', openSource: 'Open Source', selfHosted: 'Selbst gehostet',
      noCloud: 'Kein Cloud-Relay', preview: 'Hermes-Relay Android-Chat', imageAlt: 'Hermes-Relay Chat auf Android',
      route: 'Gateway · LAN · direkt',
    },
    surfaces: {
      kicker: 'Von Grund auf direkt', title: 'Ein Agent. Jede Oberfläche.',
      lede: 'Hermes-Relay erweitert deinen Agenten auf dein Smartphone und Geräte in der Nähe. Verbindungen bleiben direkt, lokal und unter deiner Kontrolle.',
      androidTitle: 'Chat, Sprache und leistungsstarke Funktionen in deiner Tasche.',
      androidLede: 'Setze Gespräche unterwegs fort, sprich natürlich und verwalte den Hermes, den du bereits betreibst.',
      chatDescription: 'Streaming-Gespräche und laufende Agentenarbeit.', voiceDescription: 'Sprich natürlich, unterbrich und bleib in Bewegung.',
      manageDescription: 'Profile, Skills, Modelle und Automatisierungen.', additional: 'Weitere Android-Oberflächen',
      cliTitle: 'Zustimmungspflichtiger Zugriff auf deinen Rechner.', cliLede: 'Gib Hermes Hände auf dem Rechner — nur wenn du es erlaubst.',
      terminal: 'Hermes-Relay Kommandozeilenvorschau', consentTitle: 'Zustimmung ist ausdrücklich und lokal.',
      consentBody: 'Du entscheidest, wann Hermes Befehle ausführen, Dateien lesen oder Änderungen vornehmen darf.', capabilities: 'CLI-Funktionen',
      sessionsTitle: 'Mach dort weiter, wo du aufgehört hast.', sessionsBody: 'Verbinde dich erneut mit benannten tmux-Sitzungen; der letzte Scrollback ist bereits vorhanden.',
      daemonTitle: 'Im Hintergrund verfügbar.', daemonBody: 'Halte genehmigte Desktop-Werkzeuge über den headless Daemon bereit.',
      auditTitle: 'Sieh, was auf deinem Rechner ausgeführt wurde.', auditBody: 'Prüfe den lokalen Verlauf jedes Desktop-Tool-Aufrufs — auch offline.',
      meetCli: 'CLI kennenlernen',
    },
    device: {
      eyebrow: 'Sideload + Relay-Plugin', title: 'Gib Hermes Hände auf deinem Smartphone.',
      lede: 'Lass deinen Agenten den Bildschirm lesen, tippen, schreiben, scrollen und Screenshots aufnehmen — jede Aktion bleibt sichtbar und unter deiner Kontrolle.',
      capabilities: 'Device-Control-Funktionen', read: 'Bildschirm lesen', tap: 'Tippen und scrollen', type: 'Für dich schreiben',
      gateTitle: 'Bewusst leistungsstark. Absichtlich geschützt.',
      gateBody: 'Erfordert die Sideload-App und ein gekoppeltes Relay-Plugin. In der Google-Play-Version ist dies nicht enthalten.',
      link: 'So funktioniert Device Control', preview: 'Nachbildung des Hermes-Relay Device-Control-Bildschirms',
    },
    setup: {
      eyebrow: 'Schnelle Einrichtung · Additiv konzipiert', title: 'Starte einfach. Füge das Plugin hinzu, wenn du mehr möchtest.',
      body: 'Chat, Manage und Standard-Voice funktionieren mit unverändertem Hermes. Das optionale Relay-Plugin ergänzt Terminal, erweiterte Sprache, Benachrichtigungen, Medienübergabe, Desktop-Werkzeuge und Device Control.',
      resources: 'Einrichtungsressourcen', androidCta: 'Android-App installieren', quickStart: 'Schnellstart', plugin: 'Relay-Plugin entdecken',
    },
    privacy: {
      aria: 'Datenschutz und Open Source', title: 'Für Datenschutz gebaut. Von Open Source angetrieben.',
      body: 'Kein Cloud-Relay. Kein Lock-in. Betreibe alles nach deinen Regeln.', learn: 'So funktioniert es', support: 'Projekt unterstützen',
    },
    footer: {
      tagline: 'Läuft auf deinem Rechner. Lebt auf deinen Geräten.', product: 'Produkt', android: 'Android-App', features: 'Funktionen',
      resources: 'Ressourcen', quickStart: 'Schnellstart', plugin: 'Relay-Plugin', docs: 'Dokumentation', project: 'Projekt',
      releases: 'Versionen', contributing: 'Mitwirken', security: 'Sicherheit', builtFor: 'Entwickelt für', by: 'von', license: 'MIT-Lizenz',
    },
  },
  es: {
    metadata: {
      title: 'Hermes-Relay — Tu agente Hermes, estés donde estés',
      description: 'Hermes-Relay lleva tu agente Hermes autoalojado a Android y le da acceso con consentimiento a los equipos cercanos.',
      imageAlt: 'Hermes-Relay — tu agente Hermes en Android y equipos cercanos',
      features: ['Chat en streaming', 'Voz en tiempo real', 'Gestión de Hermes', 'CLI de escritorio', 'Device Control por sideload'],
    },
    header: {
      home: 'Inicio de Hermes-Relay', release: 'versión actual de Android', tagline: 'Android + CLI para Hermes',
      product: 'Producto', android: 'Android', docs: 'Documentación', mainNav: 'Navegación principal', mobileNav: 'Navegación móvil',
      openNav: 'Abrir navegación', language: 'Idioma', lightTheme: 'Cambiar al tema claro', darkTheme: 'Cambiar al tema oscuro',
    },
    hero: {
      eyebrow: 'Conectado a tu Hermes', title: 'Tu agente Hermes.', accent: 'Estés donde estés.',
      lede: 'Chatea, habla, administra y dale manos a tu agente desde los dispositivos que ya te rodean.',
      androidCta: 'Obtener la app Android', cliCta: 'Explorar la CLI', openSource: 'Código abierto', selfHosted: 'Autoalojado',
      noCloud: 'Sin relay en la nube', preview: 'Pantalla de chat Android de Hermes-Relay', imageAlt: 'Chat de Hermes-Relay en Android',
      route: 'Gateway · LAN · directo',
    },
    surfaces: {
      kicker: 'Directo por diseño', title: 'Un agente. Todas las superficies.',
      lede: 'Hermes-Relay extiende tu agente al teléfono y a los equipos cercanos. Las conexiones siguen siendo directas, locales y bajo tu control.',
      androidTitle: 'Chat, voz y funciones avanzadas en tu bolsillo.',
      androidLede: 'Continúa conversaciones, habla con naturalidad y administra el Hermes que ya utilizas.',
      chatDescription: 'Conversaciones en streaming y trabajo del agente en vivo.', voiceDescription: 'Habla con naturalidad, interrumpe y sigue en movimiento.',
      manageDescription: 'Perfiles, skills, modelos y automatizaciones.', additional: 'Otras superficies de Android',
      cliTitle: 'Acceso a tu equipo sujeto a consentimiento.', cliLede: 'Dale manos a Hermes en el equipo, solo cuando tú lo permitas.',
      terminal: 'Vista previa de la línea de comandos de Hermes-Relay', consentTitle: 'El consentimiento es explícito y local.',
      consentBody: 'Tú decides cuándo Hermes puede ejecutar comandos, leer archivos o realizar cambios.', capabilities: 'Funciones de la CLI',
      sessionsTitle: 'Continúa donde lo dejaste.', sessionsBody: 'Vuelve a conectarte a sesiones tmux con nombre y conserva el historial reciente.',
      daemonTitle: 'Disponible en segundo plano.', daemonBody: 'Mantén disponibles las herramientas de escritorio aprobadas mediante el daemon sin interfaz.',
      auditTitle: 'Consulta lo que se ejecutó en tu equipo.', auditBody: 'Revisa el registro local de cada llamada a herramientas de escritorio, incluso sin conexión.',
      meetCli: 'Conocer la CLI',
    },
    device: {
      eyebrow: 'Sideload + plugin Relay', title: 'Dale manos a Hermes en tu teléfono.',
      lede: 'Permite que tu agente lea la pantalla, toque, escriba, desplace y capture imágenes, mientras cada acción permanece visible y bajo tu control.',
      capabilities: 'Funciones de Device Control', read: 'Leer la pantalla', tap: 'Tocar y desplazar', type: 'Escribir por ti',
      gateTitle: 'Potente por diseño. Protegido a propósito.',
      gateBody: 'Requiere la app Sideload y un plugin Relay vinculado. No está incluido en la versión de Google Play.',
      link: 'Ver cómo funciona Device Control', preview: 'Recreación de la pantalla Device Control de Hermes-Relay',
    },
    setup: {
      eyebrow: 'Configuración rápida · Aditivo por diseño', title: 'Empieza simple. Añade el plugin cuando quieras más.',
      body: 'Chat, Manage y la voz estándar funcionan con Hermes sin modificar. El plugin Relay opcional añade terminal, voz avanzada, notificaciones, transferencia multimedia, herramientas de escritorio y Device Control.',
      resources: 'Recursos de configuración', androidCta: 'Obtener la app Android', quickStart: 'Inicio rápido', plugin: 'Explorar el plugin Relay',
    },
    privacy: {
      aria: 'Privacidad y código abierto', title: 'Creado para la privacidad. Impulsado por código abierto.',
      body: 'Sin relay en la nube. Sin dependencia. Ejecuta todo bajo tus condiciones.', learn: 'Ver cómo funciona', support: 'Apoyar el proyecto',
    },
    footer: {
      tagline: 'Se ejecuta en tu equipo. Vive en tus dispositivos.', product: 'Producto', android: 'App Android', features: 'Funciones',
      resources: 'Recursos', quickStart: 'Inicio rápido', plugin: 'Plugin Relay', docs: 'Documentación', project: 'Proyecto',
      releases: 'Versiones', contributing: 'Contribuir', security: 'Seguridad', builtFor: 'Creado para', by: 'por', license: 'Licencia MIT',
    },
  },
  ja: {
    metadata: {
      title: 'Hermes-Relay — どこにいても、あなたの Hermes Agent を',
      description: 'Hermes-Relay はセルフホストした Hermes Agent を Android へ拡張し、同意に基づいて近くのマシンを操作できるようにします。',
      imageAlt: 'Hermes-Relay — Android と近くのマシンで使える Hermes Agent',
      features: ['ストリーミング Chat', 'リアルタイム Voice', 'Hermes 管理', 'デスクトップ CLI', 'Sideload の Device Control'],
    },
    header: {
      home: 'Hermes-Relay ホーム', release: '現在の Android リリース', tagline: 'Hermes 用 Android + CLI',
      product: '製品', android: 'Android', docs: 'ドキュメント', mainNav: 'メインナビゲーション', mobileNav: 'モバイルナビゲーション',
      openNav: 'ナビゲーションを開く', language: '言語', lightTheme: 'ライトテーマに切り替える', darkTheme: 'ダークテーマに切り替える',
    },
    hero: {
      eyebrow: 'Hermes に接続済み', title: 'あなたの Hermes Agent。', accent: 'どこにいても。',
      lede: '身近なデバイスから、チャット、音声、管理、そして Agent に操作する手を。',
      androidCta: 'Android アプリを入手', cliCta: 'CLI を見る', openSource: 'オープンソース', selfHosted: 'セルフホスト',
      noCloud: 'クラウド Relay なし', preview: 'Hermes-Relay Android Chat 画面', imageAlt: 'Android で動作する Hermes-Relay Chat',
      route: 'Gateway · LAN · 直接接続',
    },
    surfaces: {
      kicker: 'ダイレクト接続を前提に設計', title: 'ひとつの Agent。あらゆる画面で。',
      lede: 'Hermes-Relay は Agent をスマートフォンや近くのマシンへ拡張します。接続は直接かつローカル優先で、常にあなたが制御します。',
      androidTitle: 'Chat、Voice、高度な機能をポケットに。',
      androidLede: '外出先でも会話を続け、自然に話し、すでに動かしている Hermes を管理できます。',
      chatDescription: '会話と Agent の作業をリアルタイムにストリーミング。', voiceDescription: '自然に話し、割り込み、そのまま行動できます。',
      manageDescription: 'プロファイル、Skills、モデル、自動化を管理。', additional: 'その他の Android 画面',
      cliTitle: '同意で保護されたマシンアクセス。', cliLede: '許可したときだけ、Hermes にマシンを操作する手を与えます。',
      terminal: 'Hermes-Relay コマンドラインのプレビュー', consentTitle: '同意は明示的かつローカルです。',
      consentBody: 'Hermes がコマンドを実行し、ファイルを読み、変更できるタイミングはあなたが決めます。', capabilities: 'CLI の機能',
      sessionsTitle: '中断したところから再開。', sessionsBody: '名前付き tmux セッションへ再接続し、直前のスクロールバックもそのまま利用できます。',
      daemonTitle: 'バックグラウンドで待機。', daemonBody: '承認したデスクトップツールをヘッドレス daemon 経由で利用可能に保ちます。',
      auditTitle: 'マシンで実行された内容を確認。', auditBody: 'オフライン時を含む、すべてのデスクトップツール呼び出しのローカル記録を確認できます。',
      meetCli: 'CLI の詳細',
    },
    device: {
      eyebrow: 'Sideload + Relay plugin', title: 'Hermes にスマートフォンを操作する手を。',
      lede: '画面の読み取り、タップ、入力、スクロール、スクリーンショットを Agent に許可しながら、すべての操作を表示して管理できます。',
      capabilities: 'Device Control の機能', read: '画面を読み取る', tap: 'タップとスクロール', type: '代わりに入力',
      gateTitle: '強力だからこそ、明確に保護。',
      gateBody: 'Sideload アプリとペアリング済み Relay plugin が必要です。Google Play 版には含まれません。',
      link: 'Device Control の仕組み', preview: 'Hermes-Relay Device Control 画面の再現',
    },
    setup: {
      eyebrow: 'かんたん設定 · 必要な機能だけ追加', title: 'まずはシンプルに。必要になったら plugin を追加。',
      body: 'Chat、Manage、標準 Voice は未改変の Hermes で動作します。任意の Relay plugin を追加すると、Terminal、高度な Voice、通知、メディア転送、デスクトップツール、Device Control が利用できます。',
      resources: 'セットアップ資料', androidCta: 'Android アプリを入手', quickStart: 'クイックスタート', plugin: 'Relay plugin を見る',
    },
    privacy: {
      aria: 'プライバシーとオープンソース', title: 'プライバシーのために設計。オープンソースで提供。',
      body: 'クラウド Relay なし。ロックインなし。すべてを自分の条件で運用できます。', learn: '仕組みを見る', support: 'プロジェクトを支援',
    },
    footer: {
      tagline: 'あなたのマシンで動き、あなたのデバイスに届きます。', product: '製品', android: 'Android アプリ', features: '機能',
      resources: 'リソース', quickStart: 'クイックスタート', plugin: 'Relay plugin', docs: 'ドキュメント', project: 'プロジェクト',
      releases: 'リリース', contributing: 'コントリビューション', security: 'セキュリティ', builtFor: '対応製品', by: '提供', license: 'MIT ライセンス',
    },
  },
  'pt-BR': {
    metadata: {
      title: 'Hermes-Relay — Seu agente Hermes, onde você estiver',
      description: 'O Hermes-Relay leva seu agente Hermes auto-hospedado ao Android e fornece acesso com consentimento às máquinas próximas.',
      imageAlt: 'Hermes-Relay — seu agente Hermes no Android e em máquinas próximas',
      features: ['Chat por streaming', 'Voz em tempo real', 'Gerenciamento do Hermes', 'CLI para desktop', 'Device Control por sideload'],
    },
    header: {
      home: 'Início do Hermes-Relay', release: 'versão atual do Android', tagline: 'Android + CLI para Hermes',
      product: 'Produto', android: 'Android', docs: 'Documentação', mainNav: 'Navegação principal', mobileNav: 'Navegação móvel',
      openNav: 'Abrir navegação', language: 'Idioma', lightTheme: 'Mudar para o tema claro', darkTheme: 'Mudar para o tema escuro',
    },
    hero: {
      eyebrow: 'Conectado ao seu Hermes', title: 'Seu agente Hermes.', accent: 'Onde você estiver.',
      lede: 'Converse, fale, gerencie e dê mãos ao seu agente usando os dispositivos que já estão ao seu redor.',
      androidCta: 'Baixar o app Android', cliCta: 'Conhecer a CLI', openSource: 'Código aberto', selfHosted: 'Auto-hospedado',
      noCloud: 'Sem Relay na nuvem', preview: 'Tela de Chat Android do Hermes-Relay', imageAlt: 'Chat do Hermes-Relay no Android',
      route: 'Gateway · LAN · direto',
    },
    surfaces: {
      kicker: 'Direto desde o projeto', title: 'Um agente. Todas as interfaces.',
      lede: 'O Hermes-Relay estende seu agente ao telefone e às máquinas próximas. As conexões continuam diretas, locais e sob seu controle.',
      androidTitle: 'Chat, voz e recursos avançados no seu bolso.',
      androidLede: 'Continue conversas em qualquer lugar, fale naturalmente e gerencie o Hermes que você já executa.',
      chatDescription: 'Conversas por streaming e trabalho do agente em tempo real.', voiceDescription: 'Fale naturalmente, interrompa e continue em movimento.',
      manageDescription: 'Perfis, skills, modelos e automações.', additional: 'Outras interfaces Android',
      cliTitle: 'Acesso à sua máquina controlado por consentimento.', cliLede: 'Dê mãos ao Hermes na máquina — somente quando você permitir.',
      terminal: 'Prévia da linha de comando do Hermes-Relay', consentTitle: 'O consentimento é explícito e local.',
      consentBody: 'Você decide quando o Hermes pode executar comandos, ler arquivos ou fazer alterações.', capabilities: 'Recursos da CLI',
      sessionsTitle: 'Continue de onde parou.', sessionsBody: 'Reconecte-se a sessões tmux nomeadas com o histórico recente já disponível.',
      daemonTitle: 'Disponível em segundo plano.', daemonBody: 'Mantenha as ferramentas de desktop aprovadas prontas por meio do daemon sem interface.',
      auditTitle: 'Veja o que foi executado na sua máquina.', auditBody: 'Revise o registro local de cada chamada de ferramenta de desktop, mesmo offline.',
      meetCli: 'Conhecer a CLI',
    },
    device: {
      eyebrow: 'Sideload + plugin Relay', title: 'Dê mãos ao Hermes no seu telefone.',
      lede: 'Permita que seu agente leia a tela, toque, digite, role e capture imagens, mantendo cada ação visível e sob seu controle.',
      capabilities: 'Recursos do Device Control', read: 'Ler a tela', tap: 'Tocar e rolar', type: 'Digitar por você',
      gateTitle: 'Poderoso por projeto. Protegido de propósito.',
      gateBody: 'Requer o app Sideload e um plugin Relay pareado. Não está incluído na versão do Google Play.',
      link: 'Veja como funciona o Device Control', preview: 'Recriação da tela Device Control do Hermes-Relay',
    },
    setup: {
      eyebrow: 'Configuração rápida · Aditivo por projeto', title: 'Comece simples. Adicione o plugin quando quiser mais.',
      body: 'Chat, Manage e voz padrão funcionam com o Hermes sem modificações. O plugin Relay opcional adiciona terminal, voz avançada, notificações, transferência de mídia, ferramentas de desktop e Device Control.',
      resources: 'Recursos de configuração', androidCta: 'Baixar o app Android', quickStart: 'Início rápido', plugin: 'Conhecer o plugin Relay',
    },
    privacy: {
      aria: 'Privacidade e código aberto', title: 'Criado para privacidade. Movido por código aberto.',
      body: 'Sem Relay na nuvem. Sem dependência. Execute tudo nos seus termos.', learn: 'Veja como funciona', support: 'Apoiar o projeto',
    },
    footer: {
      tagline: 'Roda na sua máquina. Vive nos seus dispositivos.', product: 'Produto', android: 'App Android', features: 'Recursos',
      resources: 'Recursos', quickStart: 'Início rápido', plugin: 'Plugin Relay', docs: 'Documentação', project: 'Projeto',
      releases: 'Versões', contributing: 'Contribuir', security: 'Segurança', builtFor: 'Criado para', by: 'por', license: 'Licença MIT',
    },
  },
  'zh-CN': {
    metadata: {
      title: 'Hermes-Relay — 无论身在何处，都能使用您的 Hermes Agent',
      description: 'Hermes-Relay 将您自托管的 Hermes Agent 扩展到 Android，并在获得同意后访问附近的计算机。',
      imageAlt: 'Hermes-Relay — 在 Android 和附近计算机上使用 Hermes Agent',
      features: ['流式 Chat', '实时 Voice', 'Hermes 管理', '桌面 CLI', 'Sideload Device Control'],
    },
    header: {
      home: 'Hermes-Relay 首页', release: '当前 Android 版本', tagline: 'Hermes 的 Android + CLI',
      product: '产品', android: 'Android', docs: '文档', mainNav: '主导航', mobileNav: '移动导航',
      openNav: '打开导航', language: '语言', lightTheme: '切换到浅色主题', darkTheme: '切换到深色主题',
    },
    hero: {
      eyebrow: '已连接到您的 Hermes', title: '您的 Hermes Agent。', accent: '无论身在何处。',
      lede: '通过身边已有的设备，与 Agent 聊天、交谈、管理，并赋予它执行操作的能力。',
      androidCta: '获取 Android 应用', cliCta: '了解 CLI', openSource: '开源', selfHosted: '自托管',
      noCloud: '无云端 Relay', preview: 'Hermes-Relay Android Chat 界面', imageAlt: '在 Android 上运行的 Hermes-Relay Chat',
      route: 'Gateway · LAN · 直连',
    },
    surfaces: {
      kicker: '为直连而设计', title: '一个 Agent，覆盖每个界面。',
      lede: 'Hermes-Relay 将 Agent 扩展到手机和附近的计算机。连接保持直连、本地优先，并由您掌控。',
      androidTitle: '将 Chat、Voice 和强大功能装进口袋。',
      androidLede: '随时继续对话，自然交谈，并管理您已在运行的 Hermes。',
      chatDescription: '流式对话和实时 Agent 工作。', voiceDescription: '自然交谈、随时打断，并保持移动。',
      manageDescription: '管理配置文件、Skills、模型和自动化。', additional: '其他 Android 界面',
      cliTitle: '通过同意控制计算机访问。', cliLede: '只有在您允许时，才让 Hermes 操作计算机。',
      terminal: 'Hermes-Relay 命令行预览', consentTitle: '同意是明确且本地的。',
      consentBody: '由您决定 Hermes 何时可以运行命令、读取文件或进行更改。', capabilities: 'CLI 功能',
      sessionsTitle: '从上次离开的地方继续。', sessionsBody: '重新连接到命名的 tmux 会话，并保留最近的回滚内容。',
      daemonTitle: '在后台保持可用。', daemonBody: '通过无界面的 daemon，让已批准的桌面工具保持就绪。',
      auditTitle: '查看计算机上执行过的操作。', auditBody: '即使离线，也能查看每次桌面工具调用的本地记录。',
      meetCli: '了解 CLI',
    },
    device: {
      eyebrow: 'Sideload + Relay plugin', title: '让 Hermes 能够操作您的手机。',
      lede: '让 Agent 读取屏幕、点击、输入、滚动并截取屏幕截图，同时确保每项操作都可见并由您控制。',
      capabilities: 'Device Control 功能', read: '读取屏幕', tap: '点击和滚动', type: '代您输入',
      gateTitle: '功能强大，因此有意设限。',
      gateBody: '需要 Sideload 应用和已配对的 Relay plugin。Google Play 版本不包含此功能。',
      link: '了解 Device Control 的工作方式', preview: 'Hermes-Relay Device Control 界面复现',
    },
    setup: {
      eyebrow: '快速设置 · 按需添加', title: '从简单开始，需要更多功能时再添加 plugin。',
      body: 'Chat、Manage 和标准 Voice 可直接配合未修改的 Hermes 使用。可选 Relay plugin 可添加终端、增强 Voice、通知、媒体传输、桌面工具以及 Device Control。',
      resources: '设置资源', androidCta: '获取 Android 应用', quickStart: '快速开始', plugin: '了解 Relay plugin',
    },
    privacy: {
      aria: '隐私与开源', title: '为隐私而构建，由开源驱动。',
      body: '无云端 Relay。无锁定。按您的方式运行一切。', learn: '了解工作方式', support: '支持项目',
    },
    footer: {
      tagline: '运行在您的计算机上，融入您的设备。', product: '产品', android: 'Android 应用', features: '功能',
      resources: '资源', quickStart: '快速开始', plugin: 'Relay plugin', docs: '文档', project: '项目',
      releases: '版本', contributing: '参与贡献', security: '安全', builtFor: '为此构建', by: '由', license: 'MIT 许可证',
    },
  },
};
