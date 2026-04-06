import DefaultTheme from 'vitepress/theme';
import './custom.css';
import { h } from 'vue';
import HermesFlow from './components/HermesFlow.vue';

export default {
  extends: DefaultTheme,
  enhanceApp({ app }: { app: any }) {
    app.component('HermesFlow', HermesFlow);
  },
  Layout() {
    return h(DefaultTheme.Layout, null, {
      'doc-after': () =>
        h('div', { class: 'doc-footer-cta' }, [
          h('hr'),
          h('p', {
            innerHTML:
              '<strong>[?]</strong> <a href="https://github.com/NousResearch/hermes-agent/discussions">Questions</a> · <strong>[!]</strong> <a href="https://github.com/NousResearch/hermes-agent/issues/new">Report Issue</a> · <strong>[+]</strong> <a href="/guide/getting-started">Get Started</a>',
          }),
        ]),
    });
  },
};
