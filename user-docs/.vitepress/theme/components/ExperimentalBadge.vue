<template>
  <span class="experimental-badge" role="img" :aria-label="ariaLabel">
    <span class="dot" aria-hidden="true"></span>
    <span class="text">{{ label }}</span>
  </span>
</template>

<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{
  label?: string
}>()

const label = computed(() => props.label ?? 'Experimental')
const ariaLabel = computed(() => `${label.value} feature`)
</script>

<style scoped>
.experimental-badge {
  display: inline-flex;
  align-items: center;
  gap: 0.4rem;
  padding: 0.15rem 0.55rem;
  border-radius: 999px;
  font-size: 0.72rem;
  font-weight: 600;
  letter-spacing: 0.04em;
  text-transform: uppercase;
  background: rgba(180, 83, 9, 0.12);
  color: #b45309;
  border: 1px solid rgba(180, 83, 9, 0.35);
  vertical-align: middle;
  line-height: 1;
}

.experimental-badge .dot {
  width: 0.4rem;
  height: 0.4rem;
  border-radius: 50%;
  background: #b45309;
  box-shadow: 0 0 0 2px rgba(180, 83, 9, 0.18);
}

/* Dark theme — VitePress sets `.dark` on <html>. Use a slightly brighter
 * amber so the badge stays legible against the dark surface. */
.dark .experimental-badge {
  background: rgba(251, 191, 36, 0.10);
  color: #fbbf24;
  border-color: rgba(251, 191, 36, 0.30);
}
.dark .experimental-badge .dot {
  background: #fbbf24;
  box-shadow: 0 0 0 2px rgba(251, 191, 36, 0.18);
}
</style>
