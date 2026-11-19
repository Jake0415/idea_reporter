// The sunset theme. `astryx experimental theme editor` wrote this file once and never changes it again.
// The checked settings live in sunset.settings.json and the generated sunset.settings.ts;
// change them with `astryx experimental theme editor set sunset ...`. Add your own changes below: they win over the settings.
import {defineTheme} from '@astryxdesign/core/theme';
import {sunsetSettings} from './sunset.settings';

export const sunsetTheme = defineTheme({
  extends: sunsetSettings,
  name: 'sunset',
});
