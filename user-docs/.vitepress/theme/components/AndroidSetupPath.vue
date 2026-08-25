<script setup lang="ts">
import { computed } from 'vue'
import { useData, withBase } from 'vitepress'

type Mode = 'overview' | 'quick' | 'reference'

const props = defineProps<{ mode: Mode }>()
const { lang } = useData()

const copy = {
  en: {
    overview: {
      kicker: 'Start here', title: 'Choose the path that matches your setup.',
      cards: [
        ['Recommended', 'Android + Relay', 'Connect the app, then pair Relay for the complete experience.', 'Follow the Quick Start', 'quick'],
        ['Standard Hermes', 'Android only', 'Use Chat, sessions, Manage, sign-in, and standard voice without Relay.', 'Use Hermes without Relay', 'upstream'],
        ['Manual + advanced', 'Installation reference', 'Choose Play or Sideload, configure a host, or connect without QR.', 'Open Installation & Setup', 'reference'],
      ],
    },
    quick: {
      kicker: 'Recommended path', title: 'One app. Two QR codes. Three milestones.',
      cards: [
        ['01', 'Install Android', 'Use Google Play for the everyday app. Choose Sideload only for Device Control.', '', ''],
        ['02', 'Connect Hermes', 'Scan Connect mobile app. This adds the standard Dashboard/Gateway connection.', '', ''],
        ['03', 'Pair Relay', 'Scan Pair new device. This unlocks Relay capabilities with a separate grant.', '', ''],
      ],
      note: 'The QR codes are intentionally separate: connecting Hermes does not silently grant Relay access.',
    },
    reference: {
      kicker: 'Manual + advanced setup', title: 'Use this page when the Quick Start does not fit.',
      cards: [
        ['Build choice', 'Play or Sideload', 'Compare automatic updates with optional Device Control.', '', ''],
        ['Host setup', 'Make Hermes reachable', 'Install Hermes, enable the Dashboard, or configure the optional API fallback.', '', ''],
        ['Fallbacks', 'Connect without QR', 'Use LAN discovery, addresses, pairing codes, remote access, and verification.', '', ''],
      ],
      note: 'Already have a reachable Hermes Dashboard? Use the Quick Start instead.',
      action: 'Go to the recommended Quick Start',
    },
  },
  de: {
    overview: { kicker: 'Hier starten', title: 'Wähle den Weg, der zu deinem Setup passt.', cards: [['Empfohlen', 'Android + Relay', 'Verbinde die App und kopple danach Relay für den vollständigen Funktionsumfang.', 'Zum Schnellstart', 'quick'], ['Standard-Hermes', 'Nur Android', 'Nutze Chat, Sitzungen, Manage, Anmeldung und Standard-Voice ohne Relay.', 'Hermes ohne Relay verwenden', 'upstream'], ['Manuell + erweitert', 'Installationsreferenz', 'Wähle Play oder Sideload, richte einen Host ein oder verbinde dich ohne QR.', 'Installation & Setup öffnen', 'reference']] },
    quick: { kicker: 'Empfohlener Weg', title: 'Eine App. Zwei QR-Codes. Drei Etappen.', cards: [['01', 'Android installieren', 'Google Play für die normale App; Sideload nur für Device Control.', '', ''], ['02', 'Hermes verbinden', '„Connect mobile app“ scannen. Das fügt die Dashboard/Gateway-Verbindung hinzu.', '', ''], ['03', 'Relay koppeln', '„Pair new device“ scannen. Das erteilt die getrennte Relay-Freigabe.', '', '']], note: 'Die QR-Codes sind bewusst getrennt: Die Hermes-Verbindung erteilt nicht automatisch Relay-Zugriff.' },
    reference: { kicker: 'Manuelle + erweiterte Einrichtung', title: 'Nutze diese Seite, wenn der Schnellstart nicht passt.', cards: [['Build-Auswahl', 'Play oder Sideload', 'Automatische Updates mit optionaler Gerätesteuerung vergleichen.', '', ''], ['Host-Einrichtung', 'Hermes erreichbar machen', 'Hermes installieren, Dashboard aktivieren oder den optionalen API-Fallback konfigurieren.', '', ''], ['Alternativen', 'Ohne QR verbinden', 'LAN-Suche, Adressen, Kopplungscodes, Fernzugriff und Prüfung.', '', '']], note: 'Ist dein Hermes Dashboard bereits erreichbar? Nutze stattdessen den Schnellstart.', action: 'Zum empfohlenen Schnellstart' },
  },
  es: {
    overview: { kicker: 'Empieza aquí', title: 'Elige la ruta que coincide con tu configuración.', cards: [['Recomendada', 'Android + Relay', 'Conecta la app y luego empareja Relay para la experiencia completa.', 'Seguir el inicio rápido', 'quick'], ['Hermes estándar', 'Solo Android', 'Usa Chat, sesiones, Manage, inicio de sesión y voz estándar sin Relay.', 'Usar Hermes sin Relay', 'upstream'], ['Manual + avanzado', 'Referencia de instalación', 'Elige Play o Sideload, configura un host o conecta sin QR.', 'Abrir Instalación y configuración', 'reference']] },
    quick: { kicker: 'Ruta recomendada', title: 'Una app. Dos códigos QR. Tres etapas.', cards: [['01', 'Instala Android', 'Usa Google Play normalmente; elige Sideload solo para Device Control.', '', ''], ['02', 'Conecta Hermes', 'Escanea Connect mobile app para añadir Dashboard/Gateway.', '', ''], ['03', 'Empareja Relay', 'Escanea Pair new device para conceder Relay por separado.', '', '']], note: 'Los QR están separados a propósito: conectar Hermes no concede acceso a Relay.' },
    reference: { kicker: 'Configuración manual + avanzada', title: 'Usa esta página cuando el inicio rápido no encaje.', cards: [['Versión', 'Play o Sideload', 'Compara actualizaciones automáticas con Device Control opcional.', '', ''], ['Host', 'Haz accesible Hermes', 'Instala Hermes, activa Dashboard o configura el API opcional.', '', ''], ['Alternativas', 'Conecta sin QR', 'Usa LAN, direcciones, códigos, acceso remoto y verificación.', '', '']], note: '¿Ya puedes abrir Hermes Dashboard? Usa el inicio rápido.', action: 'Ir al inicio rápido recomendado' },
  },
  ja: {
    overview: { kicker: 'ここから開始', title: '環境に合うセットアップ方法を選びます。', cards: [['推奨', 'Android + Relay', 'アプリを接続し、Relay をペアリングして全機能を利用します。', 'クイックスタートへ', 'quick'], ['標準 Hermes', 'Android のみ', 'Relay なしで Chat、セッション、Manage、ログイン、標準 Voice を使います。', 'Relay なしで使う', 'upstream'], ['手動 + 詳細', 'インストール資料', 'Play / Sideload、ホスト設定、QR なしの接続を確認します。', 'インストールと設定', 'reference']] },
    quick: { kicker: '推奨ルート', title: '1つのアプリ。2つのQR。3つの段階。', cards: [['01', 'Android をインストール', '通常は Google Play。Device Control が必要な場合のみ Sideload。', '', ''], ['02', 'Hermes を接続', 'Connect mobile app をスキャンして Dashboard/Gateway を追加します。', '', ''], ['03', 'Relay をペアリング', 'Pair new device をスキャンして Relay を別途許可します。', '', '']], note: 'QR は意図的に分かれています。Hermes への接続だけで Relay アクセスは許可されません。' },
    reference: { kicker: '手動 + 詳細設定', title: 'クイックスタートが合わない場合に使うページです。', cards: [['ビルド選択', 'Play または Sideload', '自動更新とオプションの Device Control を比較します。', '', ''], ['ホスト設定', 'Hermes を到達可能にする', 'Hermes、Dashboard、任意の API フォールバックを設定します。', '', ''], ['代替手段', 'QR なしで接続', 'LAN、アドレス、コード、リモートアクセス、検証を使います。', '', '']], note: 'Hermes Dashboard に到達できる場合は、クイックスタートを使ってください。', action: '推奨クイックスタートへ' },
  },
  'pt-BR': {
    overview: { kicker: 'Comece aqui', title: 'Escolha o caminho que combina com sua configuração.', cards: [['Recomendado', 'Android + Relay', 'Conecte o app e depois pareie o Relay para a experiência completa.', 'Seguir o Início rápido', 'quick'], ['Hermes padrão', 'Somente Android', 'Use Chat, sessões, Manage, login e voz padrão sem Relay.', 'Usar Hermes sem Relay', 'upstream'], ['Manual + avançado', 'Referência de instalação', 'Escolha Play ou Sideload, configure um host ou conecte sem QR.', 'Abrir Instalação e configuração', 'reference']] },
    quick: { kicker: 'Caminho recomendado', title: 'Um app. Dois códigos QR. Três etapas.', cards: [['01', 'Instale o Android', 'Use o Google Play normalmente; escolha Sideload só para Device Control.', '', ''], ['02', 'Conecte o Hermes', 'Escaneie Connect mobile app para adicionar Dashboard/Gateway.', '', ''], ['03', 'Pareie o Relay', 'Escaneie Pair new device para conceder acesso ao Relay separadamente.', '', '']], note: 'Os QR são separados de propósito: conectar o Hermes não concede acesso ao Relay.' },
    reference: { kicker: 'Configuração manual + avançada', title: 'Use esta página quando o Início rápido não servir.', cards: [['Versão', 'Play ou Sideload', 'Compare atualizações automáticas com Device Control opcional.', '', ''], ['Host', 'Torne o Hermes acessível', 'Instale o Hermes, ative o Dashboard ou configure a API opcional.', '', ''], ['Alternativas', 'Conecte sem QR', 'Use LAN, endereços, códigos, acesso remoto e verificação.', '', '']], note: 'O Hermes Dashboard já está acessível? Use o Início rápido.', action: 'Ir para o Início rápido recomendado' },
  },
  'zh-CN': {
    overview: { kicker: '从这里开始', title: '选择适合当前环境的设置路径。', cards: [['推荐', 'Android + Relay', '先连接应用，再配对 Relay，获得完整体验。', '按照快速开始操作', 'quick'], ['标准 Hermes', '仅 Android', '无需 Relay 即可使用 Chat、会话、Manage、登录和标准 Voice。', '不使用 Relay', 'upstream'], ['手动 + 高级', '安装参考', '选择 Play 或 Sideload、配置主机，或在没有 QR 时连接。', '打开安装与设置', 'reference']] },
    quick: { kicker: '推荐路径', title: '一个应用，两个 QR，三个阶段。', cards: [['01', '安装 Android', '日常使用请选择 Google Play；仅在需要 Device Control 时选择 Sideload。', '', ''], ['02', '连接 Hermes', '扫描 Connect mobile app，添加 Dashboard/Gateway。', '', ''], ['03', '配对 Relay', '扫描 Pair new device，单独授予 Relay 访问权。', '', '']], note: '两个 QR 有意分开：连接 Hermes 不会自动授予 Relay 访问权。' },
    reference: { kicker: '手动 + 高级设置', title: '快速开始不适用时，请使用此页面。', cards: [['版本选择', 'Play 或 Sideload', '比较自动更新与可选的 Device Control。', '', ''], ['主机设置', '让 Hermes 可访问', '安装 Hermes、启用 Dashboard，或配置可选 API。', '', ''], ['备用方式', '无 QR 连接', '使用 LAN、地址、配对码、远程访问和验证。', '', '']], note: 'Hermes Dashboard 已可访问？请使用快速开始。', action: '前往推荐的快速开始' },
  },
} as const

const current = computed(() => (copy[lang.value as keyof typeof copy] ?? copy.en)[props.mode])
const localePrefix = computed(() => lang.value === 'en' ? '' : `/${lang.value}`)
const quickUrl = computed(() => withBase(`${localePrefix.value}/guide/quick-start.html`))
const installUrl = computed(() => withBase(`${localePrefix.value}/guide/getting-started.html`))

function target(kind: string) {
  if (kind === 'quick') return quickUrl.value
  if (kind === 'upstream') return `${quickUrl.value}#other-supported-paths`
  return installUrl.value
}
</script>

<template>
  <section class="android-setup-path" :class="`android-setup-path--${mode}`" :aria-label="current.title">
    <header>
      <span>{{ current.kicker }}</span>
      <h2>{{ current.title }}</h2>
    </header>
    <div class="android-setup-path__grid">
      <article v-for="card in current.cards" :key="card[1]">
        <small>{{ card[0] }}</small>
        <h3>{{ card[1] }}</h3>
        <p>{{ card[2] }}</p>
        <a v-if="card[3]" :href="target(card[4])">{{ card[3] }} <span aria-hidden="true">→</span></a>
      </article>
    </div>
    <footer v-if="'note' in current">
      <span>{{ current.note }}</span>
      <a v-if="'action' in current && current.action" :href="quickUrl">{{ current.action }} <span aria-hidden="true">→</span></a>
    </footer>
  </section>
</template>

<style scoped>
.android-setup-path { margin: 1.75rem 0 2.25rem; border: 1px solid var(--hr-line-strong); border-radius: 10px; overflow: hidden; background: var(--vp-c-bg-alt); }
.android-setup-path > header { padding: 20px 22px; border-bottom: 1px solid var(--hr-line); background: color-mix(in srgb, var(--vp-c-brand-soft) 34%, var(--vp-c-bg-alt)); }
.android-setup-path > header span, article small { font-family: var(--vp-font-family-mono); font-size: .66rem; letter-spacing: .08em; text-transform: uppercase; color: var(--vp-c-brand-1); }
.android-setup-path h2 { margin: 6px 0 0; border: 0; padding: 0; font-size: 1.35rem; line-height: 1.25; }
.android-setup-path__grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); }
.android-setup-path article { min-width: 0; padding: 20px 22px; border-right: 1px solid var(--hr-line); }
.android-setup-path article:last-child { border-right: 0; }
.android-setup-path article h3 { margin: 7px 0 8px; border: 0; padding: 0; font-size: 1rem; }
.android-setup-path article p { margin: 0; color: var(--vp-c-text-2); font-size: .9rem; line-height: 1.55; }
.android-setup-path article a { display: inline-block; margin-top: 14px; font-family: var(--vp-font-family-mono); font-size: .7rem; text-transform: uppercase; }
.android-setup-path footer { display: flex; align-items: center; justify-content: space-between; gap: 20px; padding: 13px 22px; border-top: 1px solid var(--hr-line); color: var(--vp-c-text-2); font-size: .85rem; line-height: 1.5; }
.android-setup-path footer a { flex: none; font-family: var(--vp-font-family-mono); font-size: .7rem; text-transform: uppercase; }
@media (max-width: 720px) { .android-setup-path__grid { grid-template-columns: 1fr; } .android-setup-path article { border-right: 0; border-bottom: 1px solid var(--hr-line); } .android-setup-path article:last-child { border-bottom: 0; } .android-setup-path footer { align-items: flex-start; flex-direction: column; } }
</style>
