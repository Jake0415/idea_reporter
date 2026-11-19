/* 실제 기본 프롬프트(core 리소스)를 그대로 불러온다 — 시안의 편집 화면이 진짜 내용을 보여 주도록. design.md §7-3 */
import classify from '../../../core/src/main/resources/prompts/classify.md?raw';
import ideas from '../../../core/src/main/resources/prompts/ideas.md?raw';
import track from '../../../core/src/main/resources/prompts/track.md?raw';
import criteria from '../../../core/src/main/resources/prompts/criteria.default.md?raw';
import techMatch from '../../../core/src/main/resources/profiles/tech-signal/match.md?raw';
import techAnalysis from '../../../core/src/main/resources/profiles/tech-signal/analysis.md?raw';
import demandMatch from '../../../core/src/main/resources/profiles/demand-signal/match.md?raw';
import demandAnalysis from '../../../core/src/main/resources/profiles/demand-signal/analysis.md?raw';
import marketMatch from '../../../core/src/main/resources/profiles/market-signal/match.md?raw';
import marketAnalysis from '../../../core/src/main/resources/profiles/market-signal/analysis.md?raw';

export type PromptDef = {
  id: string; group: string; name: string; stage: string; model: string;
  inputs: string; outputs: string; text: string;
};

export const PROMPTS: PromptDef[] = [
  {id: 'classify', group: '1차 분류', name: '1차 분류 지시', stage: '④ 분류', model: '분류 모델 (Claude haiku-5-5)',
    inputs: 'items(25건 묶음), recentTopics(최근 14일 주제), tracked(추적 아이디어)', outputs: 'ClassifiedItem: profile, score, reason, summary, tags, topicKey, topicLabel, trackedIdeaIds', text: classify},
  {id: 'profile.tech-signal.match', group: '신호 프로필', name: '기술 신호 — 포함 기준', stage: '④ 분류', model: '분류 모델', inputs: '(분류 지시에 붙음)', outputs: '프로필 배정', text: techMatch},
  {id: 'profile.tech-signal.analysis', group: '신호 프로필', name: '기술 신호 — 평가표', stage: '④ 분류', model: '분류 모델', inputs: '(분류 지시에 붙음)', outputs: '0~10 점수', text: techAnalysis},
  {id: 'profile.demand-signal.match', group: '신호 프로필', name: '수요 신호 — 포함 기준', stage: '④ 분류', model: '분류 모델', inputs: '(분류 지시에 붙음)', outputs: '프로필 배정', text: demandMatch},
  {id: 'profile.demand-signal.analysis', group: '신호 프로필', name: '수요 신호 — 평가표', stage: '④ 분류', model: '분류 모델', inputs: '(분류 지시에 붙음)', outputs: '0~10 점수', text: demandAnalysis},
  {id: 'profile.market-signal.match', group: '신호 프로필', name: '경쟁 제품 — 포함 기준', stage: '④ 분류', model: '분류 모델', inputs: '(분류 지시에 붙음)', outputs: '프로필 배정', text: marketMatch},
  {id: 'profile.market-signal.analysis', group: '신호 프로필', name: '경쟁 제품 — 평가표', stage: '④ 분류', model: '분류 모델', inputs: '(분류 지시에 붙음)', outputs: '0~10 점수', text: marketAnalysis},
  {id: 'ideas', group: '카드 작성', name: '아이디어 카드 작성', stage: '⑦ 작성', model: '작성 모델 (Claude opus-5-5)',
    inputs: 'today, ideaCount, maxCardsPerCategory, categories, criteria, signals, history, feedback, recentCards', outputs: 'IdeaCard 목록(인용은 수집 URL만)', text: ideas},
  {id: 'track', group: '추적', name: '추적 업데이트', stage: '⑧-1 추적', model: '분류 모델',
    inputs: 'idea(추적 아이디어), items(오늘 관련 항목), previous(최근 업데이트 3개)', outputs: 'TrackingUpdate: summary, changes(kind, note, itemKey)', text: track},
  {id: 'criteria', group: '내 기준', name: '내 기준', stage: '⑦ 작성(사용자 메시지)', model: '작성 모델',
    inputs: 'SC-09 양식에서 만들어짐', outputs: '(카드 작성 입력)', text: criteria},
];
