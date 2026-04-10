import DefaultTheme from 'vitepress/theme';
import './custom.css';
import { h } from 'vue';
import { withBase } from 'vitepress';
import HermesFlow from './components/HermesFlow.vue';
import InstallSection from './components/InstallSection.vue';
import HeroDemo from './components/HeroDemo.vue';

export default {
  extends: DefaultTheme,
  enhanceApp({ app }: { app: any }) {
    app.component('HermesFlow', HermesFlow);
  },
  Layout() {
    return h(DefaultTheme.Layout, null, {
      'home-hero-image': () => h(HeroDemo),
      'home-hero-after': () => h(InstallSection),
      'doc-after': () =>
        h('div', { class: 'doc-footer-cta' }, [
          h('hr'),
          h('p', {
            innerHTML:
              `<strong>[?]</strong> <a href="https://github.com/NousResearch/hermes-agent/discussions">Questions</a> · <strong>[!]</strong> <a href="https://github.com/NousResearch/hermes-agent/issues/new">Report Issue</a> · <strong>[+]</strong> <a href="${withBase('/guide/getting-started')}">Get Started</a>`,
          }),
        ]),
    });
  },
};
