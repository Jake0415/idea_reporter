// The soft theme. `astryx experimental theme editor` wrote this file once and never changes it again.
// The checked settings live in soft.settings.json and the generated soft.settings.ts;
// change them with `astryx experimental theme editor set soft ...`. Add your own changes below: they win over the settings.
import {defineTheme} from '@astryxdesign/core/theme';
import {softSettings} from './soft.settings';

export const softTheme = defineTheme({
  extends: softSettings,
  name: 'soft',
});
