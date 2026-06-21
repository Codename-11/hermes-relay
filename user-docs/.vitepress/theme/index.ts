import DefaultTheme from 'vitepress/theme';
import './custom.css';
import { h } from 'vue';
import { withBase } from 'vitepress';
import HermesFlow from './components/HermesFlow.vue';
import InstallSection from './components/InstallSection.vue';
import HeroDemo from './components/HeroDemo.vue';
import FeatureMatrix from './components/FeatureMatrix.vue';
import SphereMark from './components/SphereMark.vue';
import ExperimentalBadge from './components/ExperimentalBadge.vue';
import HowItWorks from './components/HowItWorks.vue';
import SurfaceCards from './components/SurfaceCards.vue';
import StoreBadge from './components/StoreBadge.vue';
import CombineModel from './components/CombineModel.vue';
import PetPackBuilder from './components/PetPackBuilder.vue';

export default {
  extends: DefaultTheme,
  enhanceApp({ app }: { app: any }) {
    app.component('HermesFlow', HermesFlow);
    app.component('FeatureMatrix', FeatureMatrix);
    app.component('ExperimentalBadge', ExperimentalBadge);
    // Global so `<StoreBadge />` works inside any markdown page.
    app.component('StoreBadge', StoreBadge);
    app.component('CombineModel', CombineModel);
    app.component('PetPackBuilder', PetPackBuilder);
  },
  Layout() {
    return h(DefaultTheme.Layout, null, {
      'home-hero-image': () => h(HeroDemo),
      'home-hero-actions-after': () =>
        h('div', { class: 'hero-store-row' }, [
          h(StoreBadge),
          h('p', { class: 'hero-platform-note' }, 'CLI: Windows today · macOS / Linux coming soon'),
        ]),
      'home-hero-after': () => [h(SphereMark), h(HowItWorks), h(SurfaceCards), h(InstallSection)],
      'doc-after': () =>
        h('div', { class: 'doc-footer-cta' }, [
          h('hr'),
          h('p', {
            innerHTML:
              `<strong>[?]</strong> <a href="https://github.com/Codename-11/hermes-relay/discussions">Ask a Question</a> · <strong>[!]</strong> <a href="https://github.com/Codename-11/hermes-relay/issues/new">Found a Bug?</a> · <strong>[+]</strong> <a href="${withBase('/guide/getting-started')}">Get Started</a>`,
          }),
        ]),
    });
  },
};
