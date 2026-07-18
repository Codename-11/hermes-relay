<script setup lang="ts">
import { ref } from 'vue'
import { withBase } from 'vitepress'

const props = defineProps<{
  src: string
  alt: string
  caption?: string
  maxWidth?: string
}>()

const dialog = ref<HTMLDialogElement | null>(null)

function openImage() {
  dialog.value?.showModal()
}

function closeImage() {
  dialog.value?.close()
}

function closeOnBackdrop(event: MouseEvent) {
  if (event.target === dialog.value) closeImage()
}
</script>

<template>
  <figure class="expandable-image" :style="maxWidth ? { maxWidth } : undefined">
    <button type="button" class="expandable-image__trigger" :aria-label="`Expand image: ${alt}`" @click="openImage">
      <img :src="withBase(src)" :alt="alt" />
      <span class="expandable-image__hint">Expand</span>
    </button>
    <figcaption v-if="caption">{{ caption }}</figcaption>

    <dialog ref="dialog" class="expandable-image__dialog" :aria-label="alt" @click="closeOnBackdrop">
      <div class="expandable-image__stage">
        <button type="button" class="expandable-image__close" @click="closeImage">Close</button>
        <img :src="withBase(src)" :alt="alt" />
      </div>
    </dialog>
  </figure>
</template>

<style scoped>
.expandable-image {
  width: 100%;
  margin: 0.75rem 0 1.75rem;
}

.expandable-image[style] {
  margin-right: auto;
  margin-left: auto;
}

.expandable-image__trigger {
  position: relative;
  display: block;
  width: 100%;
  padding: 0;
  overflow: hidden;
  border: 1px solid var(--hr-line);
  border-radius: 8px;
  background: var(--vp-c-bg-alt);
  cursor: zoom-in;
}

.expandable-image__trigger img {
  display: block;
  width: 100%;
  margin: 0;
  transition: transform 180ms ease;
}

.expandable-image__trigger:hover img {
  transform: scale(1.01);
}

.expandable-image__trigger:focus-visible,
.expandable-image__close:focus-visible {
  outline: 2px solid var(--vp-c-brand-1);
  outline-offset: 3px;
}

.expandable-image__hint,
.expandable-image__close {
  font-family: var(--vp-font-family-mono);
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.06em;
  text-transform: uppercase;
}

.expandable-image__hint {
  position: absolute;
  right: 10px;
  bottom: 10px;
  padding: 6px 9px;
  border: 1px solid rgba(247, 246, 240, 0.24);
  border-radius: 4px;
  background: rgba(8, 9, 13, 0.88);
  color: #f7f6f0;
}

.expandable-image figcaption {
  margin-top: 8px;
  color: var(--vp-c-text-3);
  font-size: 13px;
}

.expandable-image__dialog {
  width: min(96vw, 1500px);
  max-width: none;
  height: min(92vh, 1000px);
  max-height: none;
  padding: 0;
  border: 1px solid rgba(247, 246, 240, 0.2);
  border-radius: 8px;
  background: #08090d;
  color: #f7f6f0;
}

.expandable-image__dialog::backdrop {
  background: rgba(2, 3, 7, 0.88);
}

.expandable-image__stage {
  position: relative;
  display: grid;
  width: 100%;
  height: 100%;
  padding: 52px 20px 20px;
  place-items: center;
}

.expandable-image__stage img {
  display: block;
  width: auto;
  max-width: 100%;
  height: auto;
  max-height: 100%;
  margin: 0;
  object-fit: contain;
}

.expandable-image__close {
  position: absolute;
  top: 12px;
  right: 12px;
  padding: 7px 10px;
  border: 1px solid rgba(247, 246, 240, 0.24);
  border-radius: 4px;
  background: #191b31;
  color: #f7f6f0;
  cursor: pointer;
}

@media (prefers-reduced-motion: reduce) {
  .expandable-image__trigger img {
    transition: none;
  }
}
</style>
