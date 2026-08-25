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
    auditTitle: string; auditBody: string; uiTitle: string; uiBody: string; meetCli: string;
    desktopKicker: string; overviewCaption: string; settingsCaption: string; settingsAlt: string;
    windowsInstallLabel: string; posixInstallLabel: string;
    terminalConnecting: string; terminalConnected: string; terminalReady: string; terminalSession: string;
    terminalScrollback: string; terminalToolsLocal: string; terminalAttached: string;
    androidPlay: string; androidSideload: string; sideloadBody: string;
    installTitle: string; installBody: string; copyCommand: string; copiedCommand: string; sameInstall: string;
    nextStep: string; androidNext: string; cliNext: string; verifySideload: string;
  };
  device: {
    eyebrow: string; title: string; lede: string; capabilities: string; read: string; tap: string; type: string;
    gateTitle: string; gateBody: string; link: string;
    terminal: string; notifications: string; media: string; desktopTools: string; enhancedVoice: string; deviceControl: string;
  };
  setup: {
    eyebrow: string; title: string; body: string; resources: string; androidCta: string; quickStart: string; plugin: string;
    secureLinkTitle: string; secureLinkBody: string; secureLinkStatus: string; secureLinkCta: string;
    tailscaleTitle: string; tailscaleBody: string; tailscaleStatus: string; tailscaleCta: string;
    upstreamStatus: string; relayStatus: string; deviceStatus: string; files: string; commands: string; jobs: string; screenInput: string;
    androidSurface: string; desktopSurface: string; deviceSurface: string; chat: string; voice: string; manage: string;
  };
  privacy: { aria: string; title: string; body: string; learn: string; support: string };
  closing: { eyebrow: string; title: string; body: string; androidCta: string; cliCta: string };
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
      androidCta: 'Get the Android app', cliCta: 'Install CLI', openSource: 'Open source', selfHosted: 'Self-hosted',
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
      uiTitle: 'Manage it without opening a terminal.', uiBody: 'Windows includes an optional compact tray UI for hosts, access, approvals, activity, updates, and daemon settings.',
      meetCli: 'Installation guide',
      desktopKicker: 'CLI · WINDOWS UI ADD-ON', overviewCaption: 'Overview', settingsCaption: 'Settings + CUA',
      settingsAlt: 'CUA Driver, diagnostics, and updates', windowsInstallLabel: 'PowerShell · CLI + UI', posixInstallLabel: 'macOS / Linux · CLI',
      terminalConnecting: 'Connecting…', terminalConnected: 'Connected via Tailscale (secure)', terminalReady: 'remote Hermes ready',
      terminalSession: 'Attached · tmux session “default”', terminalScrollback: 're-attached with scrollback',
      terminalToolsLocal: 'Desktop tools · capability-gated and local', terminalAttached: 'attached',
      androidPlay: 'Get it on Google Play', androidSideload: 'Download the signed APK',
      sideloadBody: 'Sideloading simply means installing the signed APK yourself instead of through Google Play. Choose it when you want Device Control.',
      installTitle: 'Install once. Choose how you work.', installBody: 'Use the CLI everywhere. On Windows, the same install also includes the tray UI. Both installers verify release checksums.',
      copyCommand: 'Copy command', copiedCommand: 'Copied', sameInstall: 'Same install · two ways to work',
      nextStep: 'Next step', androidNext: 'Set up and connect', cliNext: 'Pair your first host', verifySideload: 'Manual updates · verify the APK',
    },
    device: {
      eyebrow: 'Recommended Relay extension', title: 'Complete the Hermes experience with Relay.',
      lede: 'Hermes-Relay prefers upstream for standard Chat, Manage, and Voice. The plugin fills current gaps with Terminal and TUI, notifications, media handoff, desktop tools, enhanced voice, Relay sessions, and optional Device Control. When upstream owns a compatible capability, Relay defers to it.',
      capabilities: 'Relay plugin capabilities', read: 'Read the screen', tap: 'Tap and scroll', type: 'Type for you',
      terminal: 'Terminal + TUI', notifications: 'Notifications', media: 'Media handoff', desktopTools: 'Desktop tools', enhancedVoice: 'Enhanced voice', deviceControl: 'Device Control',
      gateTitle: 'Device Control is the most privileged path.',
      gateBody: 'Requires the Sideload app and a paired Relay plugin. It is not included in the Google Play build.',
      link: 'Install and pair Relay',
    },
    setup: {
      eyebrow: 'Remote access', title: 'Start local. Add remote access when you need it.',
      body: 'Nothing here is required for LAN use. For remote access, start with Tailscale. Secure Link is an optional Relay-plugin front door for one pinned TLS origin.',
      resources: 'Setup resources', androidCta: 'Get the Android app', quickStart: 'Quick start', plugin: 'Explore the Relay plugin',
      secureLinkTitle: 'Hermes Secure Link', secureLinkBody: 'An advanced option that combines Relay, API, and Dashboard routes behind one pairing-pinned TLS origin. You do not need it for direct LAN or Tailscale routes.',
      secureLinkStatus: 'Advanced option · Relay plugin · port 9443', secureLinkCta: 'Secure Link setup',
      tailscaleTitle: 'Tailscale', tailscaleBody: 'The recommended remote-access path for self-hosted Hermes. Private WireGuard routing and managed TLS work behind CGNAT without exposing Relay to the public internet.',
      tailscaleStatus: 'Recommended · private remote access', tailscaleCta: 'Set up Tailscale',
      upstreamStatus: 'WORKS WITH HERMES AS-IS', relayStatus: 'PAIR RELAY · WINDOWS / MACOS / LINUX', deviceStatus: 'SIDELOAD + RELAY REQUIRED',
      files: 'Files', commands: 'Commands', jobs: 'Jobs', screenInput: 'Screen + input',
      androidSurface: 'Android app', desktopSurface: 'Desktop CLI', deviceSurface: 'Device Control',
      chat: 'Chat', voice: 'Voice', manage: 'Manage',
    },
    privacy: {
      aria: 'Privacy and open source', title: 'Built for privacy. Powered by open source.',
      body: 'No cloud relay. No lock-in. Run everything on your terms.', learn: 'See how it works', support: 'Support the project',
    },
    closing: {
      eyebrow: 'Choose your next step', title: 'Ready to connect Hermes?',
      body: 'Start with Android and add Relay for the complete experience, or pair the CLI to give Hermes hands on another machine.',
      androidCta: 'Set up Android + Relay', cliCta: 'Pair the Desktop CLI',
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
      androidCta: 'Android-App installieren', cliCta: 'CLI installieren', openSource: 'Open Source', selfHosted: 'Selbst gehostet',
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
      uiTitle: 'Verwalten ohne Terminal.', uiBody: 'Windows bietet eine optionale kompakte Tray-Oberfläche für Hosts, Zugriff, Freigaben, Aktivitäten, Updates und Daemon-Einstellungen.',
      meetCli: 'Installationsanleitung',
      desktopKicker: 'CLI · WINDOWS-UI-ERGÄNZUNG', overviewCaption: 'Übersicht', settingsCaption: 'Einstellungen + CUA',
      settingsAlt: 'CUA Driver, Diagnose und Updates', windowsInstallLabel: 'PowerShell · CLI + UI', posixInstallLabel: 'macOS / Linux · CLI',
      terminalConnecting: 'Verbindung wird hergestellt…', terminalConnected: 'Über Tailscale verbunden (sicher)', terminalReady: 'Remote-Hermes bereit',
      terminalSession: 'Verbunden · tmux-Sitzung „default“', terminalScrollback: 'mit Verlauf wieder verbunden',
      terminalToolsLocal: 'Desktop-Werkzeuge · lokal und nach Berechtigung', terminalAttached: 'verbunden',
      androidPlay: 'Bei Google Play herunterladen', androidSideload: 'Signierte APK herunterladen',
      sideloadBody: 'Sideloading bedeutet einfach, die signierte APK selbst statt über Google Play zu installieren. Wähle diese Variante für Device Control.',
      installTitle: 'Einmal installieren. Arbeitsweise wählen.', installBody: 'Nutze die CLI überall. Unter Windows enthält dieselbe Installation auch die Tray-Oberfläche. Beide Installer prüfen die Release-Prüfsummen.',
      copyCommand: 'Befehl kopieren', copiedCommand: 'Kopiert', sameInstall: 'Eine Installation · zwei Arbeitsweisen',
      nextStep: 'Nächster Schritt', androidNext: 'Einrichten und verbinden', cliNext: 'Ersten Host koppeln', verifySideload: 'Manuelle Updates · APK prüfen',
    },
    device: {
      eyebrow: 'Empfohlene Relay-Erweiterung', title: 'Vervollständige Hermes mit Relay.',
      lede: 'Hermes-Relay bevorzugt Upstream für Chat, Manage und Standard-Voice. Das Plugin schließt aktuelle Lücken mit Terminal und TUI, Benachrichtigungen, Medienübergabe, Desktop-Werkzeugen, erweiterter Voice, Relay-Sitzungen und optionalem Device Control. Sobald Upstream eine kompatible Funktion besitzt, hat diese Vorrang.',
      capabilities: 'Funktionen des Relay-Plugins', read: 'Bildschirm lesen', tap: 'Tippen und scrollen', type: 'Für dich schreiben',
      terminal: 'Terminal + TUI', notifications: 'Benachrichtigungen', media: 'Medienübergabe', desktopTools: 'Desktop-Werkzeuge', enhancedVoice: 'Erweiterte Voice', deviceControl: 'Device Control',
      gateTitle: 'Device Control ist der privilegierteste Pfad.',
      gateBody: 'Erfordert die Sideload-App und ein gekoppeltes Relay-Plugin. In der Google-Play-Version ist dies nicht enthalten.',
      link: 'Relay installieren und koppeln',
    },
    setup: {
      eyebrow: 'Fernzugriff', title: 'Starte lokal. Füge Fernzugriff bei Bedarf hinzu.',
      body: 'Für die Nutzung im LAN ist nichts hiervon erforderlich. Beginne für Fernzugriff mit Tailscale. Secure Link ist ein optionaler Relay-Plugin-Zugang über einen angehefteten TLS-Ursprung.',
      resources: 'Einrichtungsressourcen', androidCta: 'Android-App installieren', quickStart: 'Schnellstart', plugin: 'Relay-Plugin entdecken',
      secureLinkTitle: 'Hermes Secure Link', secureLinkBody: 'Eine erweiterte Option, die Relay-, API- und Dashboard-Routen hinter einem per Kopplung angehefteten TLS-Ursprung bündelt. Für direkte LAN- oder Tailscale-Routen ist sie nicht erforderlich.',
      secureLinkStatus: 'Erweiterte Option · Relay-Plugin · Port 9443', secureLinkCta: 'Secure Link einrichten',
      tailscaleTitle: 'Tailscale', tailscaleBody: 'Der empfohlene Fernzugriff für selbst gehostetes Hermes. Privates WireGuard-Routing und verwaltetes TLS funktionieren auch hinter CGNAT, ohne Relay öffentlich freizugeben.',
      tailscaleStatus: 'Empfohlen · privater Fernzugriff', tailscaleCta: 'Tailscale einrichten',
      upstreamStatus: 'FUNKTIONIERT MIT HERMES WIE ES IST', relayStatus: 'RELAY KOPPELN · WINDOWS / MACOS / LINUX', deviceStatus: 'SIDELOAD + RELAY ERFORDERLICH',
      files: 'Dateien', commands: 'Befehle', jobs: 'Jobs', screenInput: 'Bildschirm + Eingabe',
      androidSurface: 'Android-App', desktopSurface: 'Desktop-CLI', deviceSurface: 'Device Control',
      chat: 'Chat', voice: 'Sprache', manage: 'Verwaltung',
    },
    privacy: {
      aria: 'Datenschutz und Open Source', title: 'Für Datenschutz gebaut. Von Open Source angetrieben.',
      body: 'Kein Cloud-Relay. Kein Lock-in. Betreibe alles nach deinen Regeln.', learn: 'So funktioniert es', support: 'Projekt unterstützen',
    },
    closing: {
      eyebrow: 'Nächster Schritt', title: 'Bereit, Hermes zu verbinden?',
      body: 'Starte mit Android und ergänze Relay für die vollständige Erfahrung – oder kopple die CLI, damit Hermes auf einem anderen Rechner arbeiten kann.',
      androidCta: 'Android + Relay einrichten', cliCta: 'Desktop-CLI koppeln',
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
      androidCta: 'Obtener la app Android', cliCta: 'Instalar CLI', openSource: 'Código abierto', selfHosted: 'Autoalojado',
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
      uiTitle: 'Adminístralo sin abrir una terminal.', uiBody: 'Windows incluye una interfaz compacta y opcional en la bandeja para hosts, acceso, aprobaciones, actividad, actualizaciones y el daemon.',
      meetCli: 'Guía de instalación',
      desktopKicker: 'CLI · INTERFAZ DE WINDOWS OPCIONAL', overviewCaption: 'Resumen', settingsCaption: 'Ajustes + CUA',
      settingsAlt: 'CUA Driver, diagnósticos y actualizaciones', windowsInstallLabel: 'PowerShell · CLI + interfaz', posixInstallLabel: 'macOS / Linux · CLI',
      terminalConnecting: 'Conectando…', terminalConnected: 'Conectado mediante Tailscale (seguro)', terminalReady: 'Hermes remoto listo',
      terminalSession: 'Conectado · sesión tmux “default”', terminalScrollback: 'reconectado con historial',
      terminalToolsLocal: 'Herramientas de escritorio · locales y según permisos', terminalAttached: 'conectado',
      androidPlay: 'Descargar en Google Play', androidSideload: 'Descargar el APK firmado',
      sideloadBody: 'Sideloading significa instalar el APK firmado directamente en lugar de usar Google Play. Elige esta opción para Device Control.',
      installTitle: 'Instala una vez. Elige cómo trabajar.', installBody: 'Usa la CLI en cualquier sistema. En Windows, la misma instalación también incluye la interfaz de bandeja. Ambos instaladores verifican las sumas de la versión.',
      copyCommand: 'Copiar comando', copiedCommand: 'Copiado', sameInstall: 'Una instalación · dos formas de trabajar',
      nextStep: 'Siguiente paso', androidNext: 'Configurar y conectar', cliNext: 'Vincular tu primer host', verifySideload: 'Actualizaciones manuales · verificar APK',
    },
    device: {
      eyebrow: 'Extensión Relay recomendada', title: 'Completa la experiencia de Hermes con Relay.',
      lede: 'Hermes-Relay prioriza upstream para Chat, Manage y la voz estándar. El plugin cubre las carencias actuales con Terminal y TUI, notificaciones, transferencia multimedia, herramientas de escritorio, voz mejorada, sesiones Relay y Device Control opcional. Cuando upstream ofrece una función compatible, Relay le cede el paso.',
      capabilities: 'Funciones del plugin Relay', read: 'Leer la pantalla', tap: 'Tocar y desplazar', type: 'Escribir por ti',
      terminal: 'Terminal + TUI', notifications: 'Notificaciones', media: 'Transferencia multimedia', desktopTools: 'Herramientas de escritorio', enhancedVoice: 'Voz mejorada', deviceControl: 'Device Control',
      gateTitle: 'Device Control es la ruta con más privilegios.',
      gateBody: 'Requiere la app Sideload y un plugin Relay vinculado. No está incluido en la versión de Google Play.',
      link: 'Instalar y vincular Relay',
    },
    setup: {
      eyebrow: 'Acceso remoto', title: 'Empieza en local. Añade acceso remoto cuando lo necesites.',
      body: 'Nada de esto es necesario para usar la red local. Para acceso remoto, empieza con Tailscale. Secure Link es una entrada opcional del plugin Relay mediante un único origen TLS fijado.',
      resources: 'Recursos de configuración', androidCta: 'Obtener la app Android', quickStart: 'Inicio rápido', plugin: 'Explorar el plugin Relay',
      secureLinkTitle: 'Hermes Secure Link', secureLinkBody: 'Una opción avanzada que reúne las rutas Relay, API y Dashboard tras un único origen TLS fijado al vincular. No lo necesitas para rutas directas por LAN o Tailscale.',
      secureLinkStatus: 'Opción avanzada · plugin Relay · puerto 9443', secureLinkCta: 'Configurar Secure Link',
      tailscaleTitle: 'Tailscale', tailscaleBody: 'La ruta de acceso remoto recomendada para Hermes autoalojado. El enrutamiento WireGuard privado y TLS administrado funcionan detrás de CGNAT sin exponer Relay a Internet.',
      tailscaleStatus: 'Recomendado · acceso remoto privado', tailscaleCta: 'Configurar Tailscale',
      upstreamStatus: 'FUNCIONA CON HERMES TAL CUAL', relayStatus: 'VINCULA RELAY · WINDOWS / MACOS / LINUX', deviceStatus: 'REQUIERE SIDELOAD + RELAY',
      files: 'Archivos', commands: 'Comandos', jobs: 'Tareas', screenInput: 'Pantalla + entrada',
      androidSurface: 'App Android', desktopSurface: 'CLI de escritorio', deviceSurface: 'Device Control',
      chat: 'Chat', voice: 'Voz', manage: 'Gestión',
    },
    privacy: {
      aria: 'Privacidad y código abierto', title: 'Creado para la privacidad. Impulsado por código abierto.',
      body: 'Sin relay en la nube. Sin dependencia. Ejecuta todo bajo tus condiciones.', learn: 'Ver cómo funciona', support: 'Apoyar el proyecto',
    },
    closing: {
      eyebrow: 'Elige el siguiente paso', title: '¿Todo listo para conectar Hermes?',
      body: 'Empieza con Android y añade Relay para la experiencia completa, o empareja la CLI para que Hermes trabaje en otro equipo.',
      androidCta: 'Configurar Android + Relay', cliCta: 'Emparejar la CLI',
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
      androidCta: 'Android アプリを入手', cliCta: 'CLI をインストール', openSource: 'オープンソース', selfHosted: 'セルフホスト',
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
      uiTitle: 'ターミナルを開かずに管理。', uiBody: 'Windows には、ホスト、アクセス、承認、アクティビティ、更新、daemon 設定用のコンパクトなオプションのトレイ UI があります。',
      meetCli: 'インストールガイド',
      desktopKicker: 'CLI · WINDOWS UI アドオン', overviewCaption: '概要', settingsCaption: '設定 + CUA',
      settingsAlt: 'CUA Driver、診断、更新', windowsInstallLabel: 'PowerShell · CLI + UI', posixInstallLabel: 'macOS / Linux · CLI',
      terminalConnecting: '接続中…', terminalConnected: 'Tailscale 経由で接続済み（安全）', terminalReady: 'リモート Hermes の準備完了',
      terminalSession: '接続済み · tmux セッション「default」', terminalScrollback: '履歴付きで再接続',
      terminalToolsLocal: 'デスクトップツール · ローカルで権限に基づき利用', terminalAttached: '接続済み',
      androidPlay: 'Google Play で入手', androidSideload: '署名済み APK をダウンロード',
      sideloadBody: 'Sideload は、Google Play を使わず署名済み APK を自分でインストールするだけです。Device Control を使う場合はこちらを選んでください。',
      installTitle: '一度インストール。使い方を選択。', installBody: 'CLI は各環境で利用できます。Windows では同じインストールにトレイ UI も含まれます。どちらのインストーラーもリリースのチェックサムを検証します。',
      copyCommand: 'コマンドをコピー', copiedCommand: 'コピー済み', sameInstall: '同じインストール · 2 つの使い方',
      nextStep: '次のステップ', androidNext: '設定して接続', cliNext: '最初のホストをペアリング', verifySideload: '手動更新 · APK を検証',
    },
    device: {
      eyebrow: '推奨 Relay 拡張', title: 'Relay で Hermes の体験を完成。',
      lede: 'Hermes-Relay は標準 Chat、Manage、Voice で upstream を優先します。plugin は現在の不足を Terminal と TUI、通知、メディア転送、デスクトップツール、拡張 Voice、Relay セッション、任意の Device Control で補います。upstream に互換機能がある場合はそちらを優先します。',
      capabilities: 'Relay plugin の機能', read: '画面を読み取る', tap: 'タップとスクロール', type: '代わりに入力',
      terminal: 'Terminal + TUI', notifications: '通知', media: 'メディア転送', desktopTools: 'デスクトップツール', enhancedVoice: '拡張 Voice', deviceControl: 'Device Control',
      gateTitle: 'Device Control は最も権限の強い経路です。',
      gateBody: 'Sideload アプリとペアリング済み Relay plugin が必要です。Google Play 版には含まれません。',
      link: 'Relay をインストールしてペアリング',
    },
    setup: {
      eyebrow: 'リモートアクセス', title: 'まずローカルで開始。必要なときにリモートアクセスを追加。',
      body: 'LAN で使うだけなら、ここにある機能は不要です。リモートアクセスにはまず Tailscale を使ってください。Secure Link は、1 つの固定 TLS オリジンを使う任意の Relay plugin 入口です。',
      resources: 'セットアップ資料', androidCta: 'Android アプリを入手', quickStart: 'クイックスタート', plugin: 'Relay plugin を見る',
      secureLinkTitle: 'Hermes Secure Link', secureLinkBody: 'Relay、API、Dashboard の各ルートを、ペアリングで固定した 1 つの TLS オリジンにまとめる高度なオプションです。LAN または Tailscale の直接ルートには不要です。',
      secureLinkStatus: '高度なオプション · Relay plugin · ポート 9443', secureLinkCta: 'Secure Link の設定',
      tailscaleTitle: 'Tailscale', tailscaleBody: 'セルフホスト版 Hermes に推奨されるリモートアクセスです。プライベートな WireGuard ルーティングと管理 TLS により、Relay を公開せず CGNAT 配下でも利用できます。',
      tailscaleStatus: '推奨 · プライベートなリモートアクセス', tailscaleCta: 'Tailscale を設定',
      upstreamStatus: 'HERMES をそのまま使用', relayStatus: 'RELAY をペアリング · WINDOWS / MACOS / LINUX', deviceStatus: 'SIDELOAD + RELAY が必要',
      files: 'ファイル', commands: 'コマンド', jobs: 'ジョブ', screenInput: '画面 + 入力',
      androidSurface: 'Android アプリ', desktopSurface: 'デスクトップ CLI', deviceSurface: 'Device Control',
      chat: 'Chat', voice: 'Voice', manage: 'Manage',
    },
    privacy: {
      aria: 'プライバシーとオープンソース', title: 'プライバシーのために設計。オープンソースで提供。',
      body: 'クラウド Relay なし。ロックインなし。すべてを自分の条件で運用できます。', learn: '仕組みを見る', support: 'プロジェクトを支援',
    },
    closing: {
      eyebrow: '次のステップ', title: 'Hermes に接続する準備はできましたか？',
      body: 'Android から始め、Relay を追加して完全な体験を利用するか、CLI をペアリングして別のマシンで Hermes を動かします。',
      androidCta: 'Android + Relay を設定', cliCta: 'Desktop CLI をペアリング',
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
      androidCta: 'Baixar o app Android', cliCta: 'Instalar CLI', openSource: 'Código aberto', selfHosted: 'Auto-hospedado',
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
      uiTitle: 'Gerencie sem abrir um terminal.', uiBody: 'O Windows inclui uma interface compacta e opcional na bandeja para hosts, acesso, aprovações, atividades, atualizações e configurações do daemon.',
      meetCli: 'Guia de instalação',
      desktopKicker: 'CLI · INTERFACE DO WINDOWS OPCIONAL', overviewCaption: 'Visão geral', settingsCaption: 'Configurações + CUA',
      settingsAlt: 'CUA Driver, diagnósticos e atualizações', windowsInstallLabel: 'PowerShell · CLI + interface', posixInstallLabel: 'macOS / Linux · CLI',
      terminalConnecting: 'Conectando…', terminalConnected: 'Conectado via Tailscale (seguro)', terminalReady: 'Hermes remoto pronto',
      terminalSession: 'Conectado · sessão tmux “default”', terminalScrollback: 'reconectado com histórico',
      terminalToolsLocal: 'Ferramentas de desktop · locais e conforme permissões', terminalAttached: 'conectado',
      androidPlay: 'Baixar no Google Play', androidSideload: 'Baixar o APK assinado',
      sideloadBody: 'Sideloading significa instalar o APK assinado diretamente em vez de usar o Google Play. Escolha essa opção para usar o Device Control.',
      installTitle: 'Instale uma vez. Escolha como trabalhar.', installBody: 'Use a CLI em qualquer sistema. No Windows, a mesma instalação também inclui a interface da bandeja. Ambos os instaladores verificam os checksums da versão.',
      copyCommand: 'Copiar comando', copiedCommand: 'Copiado', sameInstall: 'Uma instalação · duas formas de trabalhar',
      nextStep: 'Próximo passo', androidNext: 'Configurar e conectar', cliNext: 'Parear seu primeiro host', verifySideload: 'Atualizações manuais · verificar APK',
    },
    device: {
      eyebrow: 'Extensão Relay recomendada', title: 'Complete a experiência do Hermes com o Relay.',
      lede: 'O Hermes-Relay prioriza upstream para Chat, Manage e voz padrão. O plugin cobre lacunas atuais com Terminal e TUI, notificações, transferência de mídia, ferramentas de desktop, voz avançada, sessões Relay e Device Control opcional. Quando upstream oferece uma função compatível, o Relay cede a ela.',
      capabilities: 'Recursos do plugin Relay', read: 'Ler a tela', tap: 'Tocar e rolar', type: 'Digitar por você',
      terminal: 'Terminal + TUI', notifications: 'Notificações', media: 'Transferência de mídia', desktopTools: 'Ferramentas de desktop', enhancedVoice: 'Voz avançada', deviceControl: 'Device Control',
      gateTitle: 'Device Control é o caminho com mais privilégios.',
      gateBody: 'Requer o app Sideload e um plugin Relay pareado. Não está incluído na versão do Google Play.',
      link: 'Instalar e parear o Relay',
    },
    setup: {
      eyebrow: 'Acesso remoto', title: 'Comece local. Adicione acesso remoto quando precisar.',
      body: 'Nada disso é necessário para uso em LAN. Para acesso remoto, comece com o Tailscale. O Secure Link é uma entrada opcional do plugin Relay por uma única origem TLS fixada.',
      resources: 'Recursos de configuração', androidCta: 'Baixar o app Android', quickStart: 'Início rápido', plugin: 'Conhecer o plugin Relay',
      secureLinkTitle: 'Hermes Secure Link', secureLinkBody: 'Uma opção avançada que reúne as rotas Relay, API e Dashboard em uma única origem TLS fixada pelo pareamento. Você não precisa dela para rotas diretas por LAN ou Tailscale.',
      secureLinkStatus: 'Opção avançada · plugin Relay · porta 9443', secureLinkCta: 'Configurar o Secure Link',
      tailscaleTitle: 'Tailscale', tailscaleBody: 'O caminho de acesso remoto recomendado para Hermes auto-hospedado. O roteamento WireGuard privado e o TLS gerenciado funcionam atrás de CGNAT sem expor o Relay à internet.',
      tailscaleStatus: 'Recomendado · acesso remoto privado', tailscaleCta: 'Configurar o Tailscale',
      upstreamStatus: 'FUNCIONA COM HERMES COMO ESTÁ', relayStatus: 'PAREIE RELAY · WINDOWS / MACOS / LINUX', deviceStatus: 'REQUER SIDELOAD + RELAY',
      files: 'Arquivos', commands: 'Comandos', jobs: 'Tarefas', screenInput: 'Tela + entrada',
      androidSurface: 'App Android', desktopSurface: 'CLI para desktop', deviceSurface: 'Device Control',
      chat: 'Chat', voice: 'Voz', manage: 'Gerenciar',
    },
    privacy: {
      aria: 'Privacidade e código aberto', title: 'Criado para privacidade. Movido por código aberto.',
      body: 'Sem Relay na nuvem. Sem dependência. Execute tudo nos seus termos.', learn: 'Veja como funciona', support: 'Apoiar o projeto',
    },
    closing: {
      eyebrow: 'Escolha o próximo passo', title: 'Pronto para conectar o Hermes?',
      body: 'Comece com Android e adicione o Relay para a experiência completa, ou pareie a CLI para o Hermes trabalhar em outra máquina.',
      androidCta: 'Configurar Android + Relay', cliCta: 'Parear a CLI',
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
      androidCta: '获取 Android 应用', cliCta: '安装 CLI', openSource: '开源', selfHosted: '自托管',
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
      uiTitle: '无需打开终端即可管理。', uiBody: 'Windows 提供可选的紧凑托盘界面，用于管理主机、访问权限、审批、活动、更新和 daemon 设置。',
      meetCli: '安装指南',
      desktopKicker: 'CLI · WINDOWS 界面附加组件', overviewCaption: '概览', settingsCaption: '设置 + CUA',
      settingsAlt: 'CUA Driver、诊断和更新', windowsInstallLabel: 'PowerShell · CLI + 界面', posixInstallLabel: 'macOS / Linux · CLI',
      terminalConnecting: '正在连接…', terminalConnected: '已通过 Tailscale 安全连接', terminalReady: '远程 Hermes 已就绪',
      terminalSession: '已连接 · tmux 会话“default”', terminalScrollback: '已恢复并保留历史记录',
      terminalToolsLocal: '桌面工具 · 本地运行并受权限控制', terminalAttached: '已连接',
      androidPlay: '通过 Google Play 获取', androidSideload: '下载签名 APK',
      sideloadBody: 'Sideload 只是指不通过 Google Play，而是自行安装已签名的 APK。需要 Device Control 时请选择此版本。',
      installTitle: '安装一次，自由选择使用方式。', installBody: 'CLI 可在各个平台使用；在 Windows 上，同一安装包还包含托盘界面。两个安装程序都会验证发布校验和。',
      copyCommand: '复制命令', copiedCommand: '已复制', sameInstall: '同一安装 · 两种使用方式',
      nextStep: '下一步', androidNext: '设置并连接', cliNext: '配对第一台主机', verifySideload: '手动更新 · 验证 APK',
    },
    device: {
      eyebrow: '推荐的 Relay 扩展', title: '使用 Relay 完善 Hermes 体验。',
      lede: 'Hermes-Relay 在标准 Chat、Manage 和 Voice 上优先采用 upstream。plugin 用 Terminal 与 TUI、通知、媒体传输、桌面工具、增强 Voice、Relay 会话和可选 Device Control 补足当前缺口。当 upstream 提供兼容功能时，Relay 会优先采用 upstream。',
      capabilities: 'Relay plugin 功能', read: '读取屏幕', tap: '点击和滚动', type: '代您输入',
      terminal: 'Terminal + TUI', notifications: '通知', media: '媒体传输', desktopTools: '桌面工具', enhancedVoice: '增强 Voice', deviceControl: 'Device Control',
      gateTitle: 'Device Control 是权限最高的路径。',
      gateBody: '需要 Sideload 应用和已配对的 Relay plugin。Google Play 版本不包含此功能。',
      link: '安装并配对 Relay',
    },
    setup: {
      eyebrow: '远程访问', title: '从本地开始，需要时再添加远程访问。',
      body: '仅在 LAN 中使用无需配置这里的任何功能。远程访问请优先使用 Tailscale。Secure Link 是可选的 Relay plugin 入口，通过一个固定的 TLS 源站连接。',
      resources: '设置资源', androidCta: '获取 Android 应用', quickStart: '快速开始', plugin: '了解 Relay plugin',
      secureLinkTitle: 'Hermes Secure Link', secureLinkBody: '这是一个高级选项，将 Relay、API 和 Dashboard 路由汇集到一个通过配对固定的 TLS 源站。直接使用 LAN 或 Tailscale 路由时并不需要它。',
      secureLinkStatus: '高级选项 · Relay plugin · 端口 9443', secureLinkCta: '设置 Secure Link',
      tailscaleTitle: 'Tailscale', tailscaleBody: '自托管 Hermes 推荐的远程访问方式。私有 WireGuard 路由和托管 TLS 可在 CGNAT 后运行，无需将 Relay 暴露到公网。',
      tailscaleStatus: '推荐 · 私有远程访问', tailscaleCta: '设置 Tailscale',
      upstreamStatus: '直接配合 HERMES 使用', relayStatus: '配对 RELAY · WINDOWS / MACOS / LINUX', deviceStatus: '需要 SIDELOAD + RELAY',
      files: '文件', commands: '命令', jobs: '任务', screenInput: '屏幕 + 输入',
      androidSurface: 'Android 应用', desktopSurface: '桌面 CLI', deviceSurface: 'Device Control',
      chat: 'Chat', voice: 'Voice', manage: '管理',
    },
    privacy: {
      aria: '隐私与开源', title: '为隐私而构建，由开源驱动。',
      body: '无云端 Relay。无锁定。按您的方式运行一切。', learn: '了解工作方式', support: '支持项目',
    },
    closing: {
      eyebrow: '选择下一步', title: '准备好连接 Hermes 了吗？',
      body: '从 Android 开始并添加 Relay 以获得完整体验，或配对 CLI，让 Hermes 在另一台机器上工作。',
      androidCta: '设置 Android + Relay', cliCta: '配对桌面 CLI',
    },
    footer: {
      tagline: '运行在您的计算机上，融入您的设备。', product: '产品', android: 'Android 应用', features: '功能',
      resources: '资源', quickStart: '快速开始', plugin: 'Relay plugin', docs: '文档', project: '项目',
      releases: '版本', contributing: '参与贡献', security: '安全', builtFor: '为此构建', by: '由', license: 'MIT 许可证',
    },
  },
};
