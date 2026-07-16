import DefaultTheme from 'vitepress/theme';
import './custom.css';
import { h } from 'vue';
import { withBase } from 'vitepress';
import HermesFlow from './components/HermesFlow.vue';
import FeatureMatrix from './components/FeatureMatrix.vue';
import ExperimentalBadge from './components/ExperimentalBadge.vue';
import StoreBadge from './components/StoreBadge.vue';
import CombineModel from './components/CombineModel.vue';
import PetPackBuilder from './components/PetPackBuilder.vue';
import DocsHomeHub from './components/DocsHomeHub.vue';
import ExpandableImage from './components/ExpandableImage.vue';
import FirstRunPreview from './components/FirstRunPreview.vue';
import ReleaseTrackChooser from './components/ReleaseTrackChooser.vue';
import TroubleshootingNavigator from './components/TroubleshootingNavigator.vue';

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
    app.component('DocsHomeHub', DocsHomeHub);
    app.component('ExpandableImage', ExpandableImage);
    app.component('FirstRunPreview', FirstRunPreview);
    app.component('ReleaseTrackChooser', ReleaseTrackChooser);
    app.component('TroubleshootingNavigator', TroubleshootingNavigator);
  },
  Layout() {
    return h(DefaultTheme.Layout, null, {
      'doc-after': () =>
        h('div', { class: 'doc-footer-cta' }, [
          h('hr'),
          h('p', {
            innerHTML:
              `<strong>[?]</strong> <a href="${withBase('/guide/troubleshooting.html')}">Get Help</a> · <strong>[!]</strong> <a href="https://github.com/Codename-11/hermes-relay/issues/new">Found a Bug?</a> · <strong>[+]</strong> <a href="${withBase('/guide/getting-started.html')}">Get Started</a>`,
          }),
        ]),
    });
  },
};
