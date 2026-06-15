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
  background: #121426;
  border: 1px solid rgba(247, 246, 240, 0.16);
  border-radius: 8px;
  padding: 8px 16px;
  font-family: 'Space Mono', monospace;
  font-size: 11px;
  letter-spacing: 0.03em;
  color: #F7F6F0;
  white-space: nowrap;
  transition: border-color 0.2s;
}

.hermes-flow-node:hover {
  border-color: #6E7CFF;
}

.hermes-flow-node--accent {
  border-color: #6E7CFF;
  box-shadow: 0 0 12px rgba(110, 124, 255, 0.22);
}

.hermes-flow-node__label {
  display: block;
  text-align: center;
}

/* Hide default handles — they still work for edge connections */
.hermes-flow-node .vue-flow__handle {
  width: 6px;
  height: 6px;
  background: #33365A;
  border: none;
  transition: background 0.2s;
}

.hermes-flow-node:hover .vue-flow__handle {
  background: #6E7CFF;
}
</style>
