<script setup lang="ts">
// Official "Get it on Google Play" badge → the Play listing. Used both in the
// home hero (slotted via theme/index.ts) and inline in markdown (registered
// globally in enhanceApp, so `<StoreBadge />` works in any .md page).
import { withBase } from 'vitepress'

const PLAY_URL =
  'https://play.google.com/store/apps/details?id=com.axiomlabs.hermesrelay'
</script>

<template>
  <a
    class="store-badge"
    :href="PLAY_URL"
    target="_blank"
    rel="noopener"
    aria-label="Get Hermes-Relay on Google Play"
  >
    <img
      :src="withBase('/badges/google-play.svg')"
      alt="Get it on Google Play"
      width="200"
      height="60"
    />
  </a>
</template>

<style scoped>
.store-badge {
  display: inline-block;
  line-height: 0;
  border-radius: 9px;
  transition: opacity 0.2s, transform 0.2s;
}
.store-badge:hover {
  opacity: 0.88;
  transform: translateY(-1px);
}
.store-badge img {
  height: 56px;
  width: auto;
  display: block;
}
</style>
