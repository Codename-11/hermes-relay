<script setup lang="ts">
import { Handle, Position } from '@vue-flow/core'

const props = defineProps<{
  data: {
    label: string
    color?: string
    accent?: boolean
  }
  sourcePosition?: Position
  targetPosition?: Position
}>()
</script>

<template>
  <div class="hermes-flow-node" :class="{ 'hermes-flow-node--accent': data.accent }">
    <Handle v-if="targetPosition" type="target" :position="targetPosition ?? Position.Left" />
    <span class="hermes-flow-node__label">{{ data.label }}</span>
    <Handle v-if="sourcePosition" type="source" :position="sourcePosition ?? Position.Right" />
  </div>
</template>

<style>
.hermes-flow-node {
  background: #0A0A0A;
  border: 1px solid #2A2A2A;
  border-radius: 8px;
  padding: 8px 16px;
  font-family: 'Space Mono', monospace;
  font-size: 11px;
  letter-spacing: 0.03em;
  color: #E8E8E8;
  white-space: nowrap;
  transition: border-color 0.2s;
}

.hermes-flow-node:hover {
  border-color: #7C3AED;
}

.hermes-flow-node--accent {
  border-color: #7C3AED;
  box-shadow: 0 0 12px rgba(124, 58, 237, 0.15);
}

.hermes-flow-node__label {
  display: block;
  text-align: center;
}

/* Hide default handles — they still work for edge connections */
.hermes-flow-node .vue-flow__handle {
  width: 6px;
  height: 6px;
  background: #444;
  border: none;
  transition: background 0.2s;
}

.hermes-flow-node:hover .vue-flow__handle {
  background: #7C3AED;
}
</style>
