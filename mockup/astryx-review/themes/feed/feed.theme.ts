// The feed theme. `astryx experimental theme editor` wrote this file once and never changes it again.
// The checked settings live in feed.settings.json and the generated feed.settings.ts;
// change them with `astryx experimental theme editor set feed ...`. Add your own changes below: they win over the settings.
import {defineTheme} from '@astryxdesign/core/theme';
import {feedSettings} from './feed.settings';

export const feedTheme = defineTheme({
  extends: feedSettings,
  name: 'feed',
});
