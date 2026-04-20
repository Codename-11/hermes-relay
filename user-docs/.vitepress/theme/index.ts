import DefaultTheme from 'vitepress/theme';
import './custom.css';
import { h } from 'vue';
import { withBase } from 'vitepress';
import HermesFlow from './components/HermesFlow.vue';
import InstallSection from './components/InstallSection.vue';
import HeroDemo from './components/HeroDemo.vue';
import FeatureMatrix from './components/FeatureMatrix.vue';
import SphereMark from './components/SphereMark.vue';

export default {
  extends: DefaultTheme,
  enhanceApp({ app }: { app: any }) {
    app.component('HermesFlow', HermesFlow);
    app.component('FeatureMatrix', FeatureMatrix);
  },
  Layout() {
    return h(DefaultTheme.Layout, null, {
      'home-hero-image': () => h(HeroDemo),
      'home-hero-after': () => [h(SphereMark), h(InstallSection)],
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
