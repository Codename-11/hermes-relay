<script setup lang="ts">
import { ref, onMounted, onBeforeUnmount } from 'vue'
import { withBase } from 'vitepress'

const TRANSITION_AT_SECONDS = 12

const showLogo = ref(false)
const videoEl = ref<HTMLVideoElement | null>(null)

function handleTimeUpdate() {
  if (showLogo.value) return
  const v = videoEl.value
  if (v && v.currentTime >= TRANSITION_AT_SECONDS) {
    showLogo.value = true
  }
}

// Failsafe in case timeupdate never fires (slow connection, paused autoplay)
let fallbackTimer: ReturnType<typeof setTimeout> | null = null
onMounted(() => {
  fallbackTimer = setTimeout(() => {
    showLogo.value = true
  }, (TRANSITION_AT_SECONDS + 3) * 1000)
})
onBeforeUnmount(() => {
  if (fallbackTimer) clearTimeout(fallbackTimer)
})
</script>

<template>
  <div class="hero-demo">
    <div class="hero-demo-frame">
      <Transition name="hero-fade" mode="out-in">
        <video
          v-if="!showLogo"
          ref="videoEl"
          key="video"
          class="hero-demo-media hero-demo-video"
          :src="withBase('/chat_demo.mp4')"
          :poster="withBase('/chat_demo_poster.jpg')"
          autoplay
          muted
          playsinline
          preload="metadata"
          @timeupdate="handleTimeUpdate"
        />
        <div v-else key="logo" class="hero-demo-media hero-demo-logo">
          <img :src="withBase('/logo.svg')" alt="Hermes-Relay" />
        </div>
      </Transition>
    </div>
  </div>
</template>

<style scoped>
.hero-demo {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 100%;
  height: 100%;
}
.hero-demo-frame {
  position: relative;
  display: block;
  padding: 10px;
  border-radius: 36px;
  background: linear-gradient(160deg, #1a1a1a 0%, #0a0a0a 100%);
  box-shadow:
    0 0 0 1px var(--vp-c-divider),
    0 30px 60px -20px rgba(0, 0, 0, 0.5),
    0 0 80px -10px var(--vp-c-brand-soft);
  width: 280px;
  margin: 0 auto;
  box-sizing: border-box;
  overflow: hidden;
}
.hero-demo-frame::before {
  content: '';
  position: absolute;
  top: 16px;
  left: 50%;
  transform: translateX(-50%);
  width: 80px;
  height: 6px;
  background: #000;
  border-radius: 3px;
  z-index: 2;
  opacity: 0.6;
}
.hero-demo-media {
  display: block;
  width: 100%;
  border-radius: 28px;
  background: #000;
}
.hero-demo-video {
  height: auto;
}
.hero-demo-logo {
  aspect-ratio: 1080 / 2340;
  display: flex;
  align-items: center;
  justify-content: center;
  background: radial-gradient(
    ellipse at center,
    rgba(155, 107, 240, 0.18) 0%,
    rgba(0, 0, 0, 1) 70%
  );
}
.hero-demo-logo img {
  width: 55%;
  height: auto;
  filter: drop-shadow(0 0 24px rgba(155, 107, 240, 0.55));
}

/* Crossfade between video and logo */
.hero-fade-enter-active,
.hero-fade-leave-active {
  transition: opacity 900ms ease;
}
.hero-fade-enter-from,
.hero-fade-leave-to {
  opacity: 0;
}

@media (max-width: 960px) {
  .hero-demo-frame {
    width: 240px;
  }
}
@media (max-width: 640px) {
  .hero-demo-frame {
    width: 200px;
    padding: 8px;
    border-radius: 30px;
  }
  .hero-demo-media {
    border-radius: 22px;
  }
}
</style>
