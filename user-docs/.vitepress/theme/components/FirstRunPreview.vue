<script setup lang="ts">
import { withBase } from 'vitepress'
</script>

<template>
  <section class="first-run" aria-labelledby="first-run-title">
    <header class="first-run__header">
      <div>
        <span>What you’ll see</span>
        <h3 id="first-run-title">A successful connection, then your first chat.</h3>
      </div>
      <ol aria-label="First-run progress">
        <li><strong>01</strong> Save Hermes</li>
        <li><strong>02</strong> Check capabilities</li>
        <li><strong>03</strong> Send a message</li>
      </ol>
    </header>

    <div class="first-run__frames">
      <figure>
        <img
          :src="withBase('/connections-demo.png')"
          alt="Hermes-Relay Connections screen with API, Dashboard, Voice, and Relay readiness rows."
          loading="lazy"
        />
        <figcaption>
          <strong>Connection ready.</strong>
          Green rows show the surfaces your phone can reach. Relay may remain optional.
        </figcaption>
      </figure>

      <figure>
        <img
          :src="withBase('/chat-demo.png')"
          alt="Hermes-Relay Chat screen showing a streamed response and message composer."
          loading="lazy"
        />
        <figcaption>
          <strong>Chat ready.</strong>
          The status strip names the active route, model, and profile.
        </figcaption>
      </figure>
    </div>
  </section>
</template>

<style scoped>
.first-run {
  margin: 1.5rem 0 2rem;
  overflow: hidden;
  border: 1px solid var(--vp-c-divider);
  border-radius: 10px;
  background: var(--vp-c-bg-alt);
}

.first-run__header {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(280px, 0.9fr);
  gap: 24px;
  align-items: end;
  padding: 20px 22px;
  border-bottom: 1px solid var(--vp-c-divider);
}

.first-run__header > div > span {
  font-family: var(--vp-font-family-mono);
  font-size: 0.66rem;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  color: var(--vp-c-brand-1);
}

.first-run__header h3 {
  margin: 6px 0 0;
  border: 0;
  font-size: 1.15rem;
}

.first-run__header ol {
  display: grid;
  gap: 7px;
  margin: 0;
  padding: 0;
  list-style: none;
  font-size: 0.8rem;
  color: var(--vp-c-text-2);
}

.first-run__header li {
  display: grid;
  grid-template-columns: 28px 1fr;
  gap: 8px;
  line-height: 1.4;
}

.first-run__header li strong {
  font-family: var(--vp-font-family-mono);
  font-size: 0.66rem;
  color: var(--vp-c-brand-1);
}

.first-run__frames {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 1px;
  background: var(--vp-c-divider);
}

.first-run figure {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(140px, 0.8fr);
  gap: 18px;
  align-items: center;
  margin: 0;
  padding: 18px;
  background: var(--vp-c-bg-alt);
}

.first-run img {
  display: block;
  width: 100%;
  max-height: 330px;
  object-fit: contain;
}

.first-run figcaption {
  color: var(--vp-c-text-2);
  font-size: 0.82rem;
  line-height: 1.55;
}

.first-run figcaption strong {
  display: block;
  margin-bottom: 5px;
  color: var(--vp-c-text-1);
}

@media (max-width: 900px) {
  .first-run__frames,
  .first-run__header {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 520px) {
  .first-run figure {
    grid-template-columns: minmax(0, 145px) 1fr;
    padding: 14px;
  }

  .first-run img {
    max-height: 260px;
  }
}
</style>
