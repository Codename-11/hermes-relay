<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { VueFlow, Position } from '@vue-flow/core'
import HermesFlowNode from './HermesFlowNode.vue'

const props = defineProps<{
  diagram: string
  height?: string
}>()

const flowHeight = computed(() => props.height || '320px')

// ── Diagram definitions ─────────────────────────────────────────

interface DiagramDef {
  nodes: Array<{
    id: string
    type?: string
    position: { x: number; y: number }
    data: { label: string; color?: string; accent?: boolean }
    sourcePosition?: Position
    targetPosition?: Position
  }>
  edges: Array<{
    id: string
    source: string
    target: string
    animated?: boolean
    label?: string
    style?: Record<string, string>
    type?: string
  }>
}

const edgeStyle = { stroke: '#7C3AED', strokeWidth: 1.5 }
const edgeDim = { stroke: '#333', strokeWidth: 1 }
const edgeAnimated = true

const diagrams: Record<string, DiagramDef> = {
  // ── Architecture: dual-path connection model ──────────────────
  'architecture': {
    nodes: [
      { id: 'app', type: 'hermes', position: { x: 0, y: 80 }, data: { label: 'Android App', accent: true }, sourcePosition: Position.Right },
      { id: 'api-client', type: 'hermes', position: { x: 220, y: 20 }, data: { label: 'HermesApiClient' }, sourcePosition: Position.Right, targetPosition: Position.Left },
      { id: 'conn-mgr', type: 'hermes', position: { x: 220, y: 140 }, data: { label: 'ConnectionManager' }, sourcePosition: Position.Right, targetPosition: Position.Left },
      { id: 'api-server', type: 'hermes', position: { x: 480, y: 20 }, data: { label: 'API Server :8642', accent: true }, sourcePosition: Position.Right, targetPosition: Position.Left },
      { id: 'relay', type: 'hermes', position: { x: 480, y: 140 }, data: { label: 'Relay :8767' }, sourcePosition: Position.Right, targetPosition: Position.Left },
      { id: 'chat', type: 'hermes', position: { x: 700, y: 20 }, data: { label: 'Chat' }, targetPosition: Position.Left },
      { id: 'terminal', type: 'hermes', position: { x: 700, y: 100 }, data: { label: 'Terminal' }, targetPosition: Position.Left },
      { id: 'bridge', type: 'hermes', position: { x: 700, y: 180 }, data: { label: 'Bridge' }, targetPosition: Position.Left },
    ],
    edges: [
      { id: 'e1', source: 'app', target: 'api-client', animated: edgeAnimated, style: edgeStyle, label: 'HTTP/SSE' },
      { id: 'e2', source: 'app', target: 'conn-mgr', animated: edgeAnimated, style: edgeDim, label: 'WSS' },
      { id: 'e3', source: 'api-client', target: 'api-server', animated: edgeAnimated, style: edgeStyle },
      { id: 'e4', source: 'conn-mgr', target: 'relay', animated: edgeAnimated, style: edgeDim },
      { id: 'e6', source: 'api-server', target: 'chat', style: edgeStyle },
      { id: 'e7', source: 'relay', target: 'terminal', style: edgeDim },
      { id: 'e8', source: 'relay', target: 'bridge', style: edgeDim },
    ],
  },

  // ── Chat Flow: message lifecycle through the app ──────────────
  'chat-flow': {
    nodes: [
      { id: '1', type: 'hermes', position: { x: 0, y: 50 }, data: { label: 'User Message' }, sourcePosition: Position.Right },
      { id: '2', type: 'hermes', position: { x: 190, y: 50 }, data: { label: 'ChatViewModel' }, sourcePosition: Position.Right, targetPosition: Position.Left },
      { id: '3', type: 'hermes', position: { x: 190, y: 140 }, data: { label: 'Create Session' }, sourcePosition: Position.Right, targetPosition: Position.Top },
      { id: '4', type: 'hermes', position: { x: 420, y: 50 }, data: { label: 'ApiClient', accent: true }, sourcePosition: Position.Right, targetPosition: Position.Left },
      { id: '5', type: 'hermes', position: { x: 620, y: 50 }, data: { label: 'SSE Stream' }, sourcePosition: Position.Bottom, targetPosition: Position.Left },
      { id: '6', type: 'hermes', position: { x: 620, y: 140 }, data: { label: 'ChatHandler' }, sourcePosition: Position.Left, targetPosition: Position.Top },
      { id: '7', type: 'hermes', position: { x: 420, y: 140 }, data: { label: 'StateFlow' }, sourcePosition: Position.Left, targetPosition: Position.Right },
      { id: '8', type: 'hermes', position: { x: 190, y: 230 }, data: { label: 'Compose UI', accent: true }, targetPosition: Position.Top },
    ],
    edges: [
      { id: 'e1', source: '1', target: '2', animated: edgeAnimated, style: edgeStyle },
      { id: 'e2', source: '2', target: '3', style: edgeDim, label: 'if new' },
      { id: 'e3', source: '2', target: '4', animated: edgeAnimated, style: edgeStyle },
      { id: 'e4', source: '3', target: '4', animated: edgeAnimated, style: edgeDim },
      { id: 'e5', source: '4', target: '5', animated: edgeAnimated, style: edgeStyle, label: '/chat/stream' },
      { id: 'e6', source: '5', target: '6', animated: edgeAnimated, style: edgeStyle },
      { id: 'e7', source: '6', target: '7', animated: edgeAnimated, style: edgeStyle },
      { id: 'e8', source: '7', target: '8', animated: edgeAnimated, style: edgeStyle },
    ],
  },

  // ── SSE Events: event processing pipeline ─────────────────────
  'sse-events': {
    nodes: [
      // Source + dispatch
      { id: 'source', type: 'hermes', position: { x: 0, y: 130 }, data: { label: 'SSE Source' }, sourcePosition: Position.Right },
      { id: 'parser', type: 'hermes', position: { x: 170, y: 130 }, data: { label: 'Event Parser', accent: true }, sourcePosition: Position.Right, targetPosition: Position.Left },
      // Lifecycle (top row — happen first, in order)
      { id: 'session', type: 'hermes', position: { x: 380, y: 0 }, data: { label: 'session.created' }, sourcePosition: Position.Right, targetPosition: Position.Left },
      { id: 'run-start', type: 'hermes', position: { x: 560, y: 0 }, data: { label: 'run.started' }, sourcePosition: Position.Right, targetPosition: Position.Left },
      { id: 'msg-start', type: 'hermes', position: { x: 720, y: 0 }, data: { label: 'message.started' }, targetPosition: Position.Left },
      // Content events (middle rows)
      { id: 'delta', type: 'hermes', position: { x: 380, y: 70 }, data: { label: 'assistant.delta' }, sourcePosition: Position.Right, targetPosition: Position.Left },
      { id: 'tool-pending', type: 'hermes', position: { x: 380, y: 130 }, data: { label: 'tool.pending' }, sourcePosition: Position.Right, targetPosition: Position.Left },
      { id: 'tool-started', type: 'hermes', position: { x: 380, y: 190 }, data: { label: 'tool.started' }, sourcePosition: Position.Right, targetPosition: Position.Left },
      { id: 'tool-progress', type: 'hermes', position: { x: 380, y: 250 }, data: { label: 'tool.progress' }, targetPosition: Position.Left },
      // Completion events (right column)
      { id: 'complete', type: 'hermes', position: { x: 620, y: 70 }, data: { label: 'assistant.completed' }, targetPosition: Position.Left },
      { id: 'tool-completed', type: 'hermes', position: { x: 620, y: 130 }, data: { label: 'tool.completed' }, targetPosition: Position.Left },
      { id: 'tool-failed', type: 'hermes', position: { x: 620, y: 190 }, data: { label: 'tool.failed' }, targetPosition: Position.Left },
      { id: 'run', type: 'hermes', position: { x: 620, y: 250 }, data: { label: 'run.completed', accent: true }, targetPosition: Position.Left },
    ],
    edges: [
      { id: 'e1', source: 'source', target: 'parser', animated: edgeAnimated, style: edgeStyle },
      // Lifecycle chain
      { id: 'e2', source: 'parser', target: 'session', style: edgeStyle },
      { id: 'e3', source: 'session', target: 'run-start', animated: edgeAnimated, style: edgeStyle },
      { id: 'e4', source: 'run-start', target: 'msg-start', animated: edgeAnimated, style: edgeStyle },
      // Content dispatch
      { id: 'e5', source: 'parser', target: 'delta', style: edgeStyle },
      { id: 'e6', source: 'parser', target: 'tool-pending', style: edgeDim },
      { id: 'e7', source: 'parser', target: 'tool-started', style: edgeDim },
      { id: 'e8', source: 'parser', target: 'tool-progress', style: edgeDim },
      // Completion
      { id: 'e9', source: 'delta', target: 'complete', style: edgeStyle },
      { id: 'e10', source: 'tool-pending', target: 'tool-completed', style: edgeDim, label: 'success' },
      { id: 'e11', source: 'tool-started', target: 'tool-failed', style: { stroke: '#EF4444', strokeWidth: 1 }, label: 'error' },
      { id: 'e12', source: 'complete', target: 'run', animated: edgeAnimated, style: edgeStyle },
      { id: 'e13', source: 'tool-completed', target: 'run', style: edgeDim },
    ],
  },

  // ── Auth Flow: pairing and token lifecycle ────────────────────
  'auth-flow': {
    nodes: [
      { id: '1', type: 'hermes', position: { x: 0, y: 50 }, data: { label: 'App Start' }, sourcePosition: Position.Right },
      { id: '2', type: 'hermes', position: { x: 160, y: 50 }, data: { label: 'Check Token' }, sourcePosition: Position.Right, targetPosition: Position.Left },
      { id: '3', type: 'hermes', position: { x: 340, y: 0 }, data: { label: 'Connect (WSS)', accent: true }, sourcePosition: Position.Right, targetPosition: Position.Left },
      { id: '4', type: 'hermes', position: { x: 340, y: 100 }, data: { label: 'Show Pairing Code' }, sourcePosition: Position.Right, targetPosition: Position.Left },
      { id: '5', type: 'hermes', position: { x: 540, y: 100 }, data: { label: 'Validate Code' }, sourcePosition: Position.Top, targetPosition: Position.Left },
      { id: '6', type: 'hermes', position: { x: 540, y: 0 }, data: { label: 'Store Token' }, sourcePosition: Position.Left, targetPosition: Position.Bottom },
      { id: '7', type: 'hermes', position: { x: 700, y: 0 }, data: { label: 'Connected' }, targetPosition: Position.Left },
    ],
    edges: [
      { id: 'e1', source: '1', target: '2', animated: edgeAnimated, style: edgeStyle },
      { id: 'e2', source: '2', target: '3', animated: edgeAnimated, style: edgeStyle, label: 'has token' },
      { id: 'e3', source: '2', target: '4', style: edgeDim, label: 'no token' },
      { id: 'e4', source: '4', target: '5', animated: edgeAnimated, style: edgeDim },
      { id: 'e5', source: '5', target: '6', animated: edgeAnimated, style: edgeStyle },
      { id: 'e6', source: '6', target: '3', animated: edgeAnimated, style: edgeStyle },
      { id: 'e7', source: '3', target: '7', animated: edgeAnimated, style: edgeStyle },
    ],
  },
}

const diagramData = computed(() => diagrams[props.diagram])
const isClient = ref(false)
const vueFlowRef = ref<InstanceType<typeof VueFlow> | null>(null)

onMounted(() => { isClient.value = true })

function handleFitView() {
  const instance = vueFlowRef.value
  if (instance) {
    (instance as any).fitView({ padding: 0.15 })
  }
}
</script>

<template>
  <div v-if="isClient && diagramData" class="hermes-flow-wrapper" :style="{ height: flowHeight }">
    <VueFlow
      ref="vueFlowRef"
      :nodes="diagramData.nodes"
      :edges="diagramData.edges"
      :nodes-draggable="false"
      :nodes-connectable="false"
      :elements-selectable="false"
      :zoom-on-scroll="true"
      :pan-on-scroll="true"
      :pan-on-drag="true"
      :zoom-on-double-click="false"
      :prevent-scrolling="false"
      :min-zoom="0.3"
      :max-zoom="2"
      :fit-view-on-init="true"
      :fit-view-on-init-padding="0.2"
    >
      <template #node-hermes="nodeProps">
        <HermesFlowNode v-bind="nodeProps" />
      </template>
    </VueFlow>

    <div class="hermes-flow-controls">
      <button class="hermes-flow-btn" title="Fit to view" @click="handleFitView">
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <path d="M15 3h6v6M9 21H3v-6M21 3l-7 7M3 21l7-7" />
        </svg>
      </button>
    </div>
  </div>
  <div v-else-if="!diagramData" class="hermes-flow-fallback">
    Unknown diagram: {{ diagram }}
  </div>
</template>

<style>
@import '@vue-flow/core/dist/style.css';

.hermes-flow-wrapper {
  position: relative;
  border: 1px solid #222;
  border-radius: 8px;
  background: #0A0A0A;
  margin: 16px 0;
  overflow: hidden;
}

/* Override Vue Flow background */
.hermes-flow-wrapper .vue-flow {
  background: #0A0A0A !important;
}

/* Edge labels */
.hermes-flow-wrapper .vue-flow__edge-textbg {
  fill: #0A0A0A;
}

.hermes-flow-wrapper .vue-flow__edge-text {
  fill: #999;
  font-family: 'Space Mono', monospace;
  font-size: 10px;
}

/* Animated edge dash */
.hermes-flow-wrapper .vue-flow__edge.animated path {
  stroke-dasharray: 5;
  animation: hermes-flow-dash 1s linear infinite;
}

@keyframes hermes-flow-dash {
  to { stroke-dashoffset: -10; }
}

/* Hide default attribution */
.hermes-flow-wrapper .vue-flow__attribution {
  display: none !important;
}

/* Custom controls */
.hermes-flow-controls {
  position: absolute;
  bottom: 8px;
  right: 8px;
  display: flex;
  gap: 4px;
  z-index: 10;
}

.hermes-flow-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  background: #1A1A1A;
  border: 1px solid #333;
  border-radius: 4px;
  color: #999;
  cursor: pointer;
  transition: border-color 0.2s, color 0.2s;
}

.hermes-flow-btn:hover {
  border-color: #7C3AED;
  color: #E8E8E8;
}

/* Pan cursor */
.hermes-flow-wrapper .vue-flow__pane {
  cursor: grab;
}

.hermes-flow-wrapper .vue-flow__pane:active {
  cursor: grabbing;
}

.hermes-flow-fallback {
  color: #666;
  font-family: 'Space Mono', monospace;
  font-size: 12px;
  padding: 24px;
  text-align: center;
}
</style>
