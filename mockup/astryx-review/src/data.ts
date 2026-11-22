/* 시안용 가상 데이터. 실제 동작은 core 파이프라인이 만든다. */

export type Idea = {
  id: string; title: string; one: string; cat: string; badges: string[]; score: number; date: string;
  why: string; problem: string; tech: string; demand?: string; quotes?: [string, string][]; comp?: string;
  hist?: string; effort: string; diff: string; risk: string; valid: string; caveat?: string; tags: string[];
};

export const IDEAS: Idea[] = [
  {id: 'c1', date: '11월 19일', title: '로컬 LLM으로 회의록을 요약하는 사내 설치형 앱', one: '녹음 파일을 PC 안에서만 처리해 요약·할 일을 뽑아 주는 데스크톱 앱', cat: '업무 자동화', badges: ['여러 곳에서 화제'], score: 8.4, tags: ['meeting-notes', 'local-llm', 'privacy'],
    why: '경량 모델이 노트북에서도 돌기 시작했고(기술), 회의록 SaaS의 보안·가격 불만 글이 이번 주 두 곳에서 올라왔다(수요).',
    problem: '보안 규정 때문에 클라우드 회의록을 못 쓰는 20~100명 회사의 팀장이 회의 정리에 매주 2~3시간을 쓴다.',
    tech: '경량 음성 인식 + 4bit 양자화 모델 공개 · GitHub ★2,464 · MIT', demand: '"보안 때문에 회의록 툴을 못 써요" · Reddit r/SaaS · 추천 180',
    quotes: [['dev_kim', '우리 회사도 외부 업로드 금지라 결국 손으로 정리함'], ['ops_lee', '(반대) 요즘은 설치형 옵션 주는 곳도 있음']],
    comp: '클라우드 회의록 서비스 A · Product Hunt 추천 420 — 빈틈: 설치형 없음, 인당 월 과금', hist: '11월 13일 카드 "오프라인 음성 메모 정리"와 같은 문제',
    effort: '1인 3주', diff: '완전 오프라인 + 1회 구매형', risk: '노트북 사양별 처리 속도 편차', valid: '보안팀 있는 지인 회사 3곳에 2주 시범 설치, 주간 사용 횟수 측정', caveat: '처리 속도는 업체 주장(독립 측정 없음)'},
  {id: 'c2', date: '11월 19일', title: '소상공인용 고객 문의 1차 응대 봇', one: '메신저 DM 문의를 FAQ와 예약표로 먼저 답해 주는 봇', cat: '커머스·마케팅', badges: ['급상승'], score: 7.6, tags: ['customer-support', 'smb', 'ai-agent'],
    why: '에이전트 프레임워크가 공개 하루 만에 HN 300점을 넘었고, 같은 날 "DM 답장에 밤샌다"는 글이 올라왔다.',
    problem: '1~3인 매장 사장이 영업 중 DM 문의에 바로 답하지 못해 예약을 놓친다.',
    tech: '오픈소스 에이전트 프레임워크 v1 · HN 300점 · Apache-2.0', demand: '"영업 끝나고 DM 답장만 1시간" · Reddit · 추천 95',
    quotes: [['cafe_owner', '예약 문의가 제일 많고 답이 늦으면 바로 다른 곳 감']],
    effort: '1인 4주', diff: '매장 예약표 연동 + 한국어 메신저', risk: '메신저 API 정책 제한', valid: '동네 매장 5곳 수동 응대 대행으로 2주 수요 확인', caveat: '근거가 출처 하나씩뿐'},
  {id: 'c3', date: '11월 19일', title: 'AI로 옮긴 코드가 원본 수정사항을 놓치는지 알려주는 감시 도구', one: '원본의 버그·보안 수정을 못 따라간 "번역 포크"를 찾아 알려 줌', cat: '개발 도구', badges: [], score: 7.2, tags: ['code-migration', 'security'],
    why: 'AI 에이전트로 옮긴 대형 오픈소스 클론이 3주 만에 원본 수정 200여 건을 놓쳤다는 글이 화제였다.',
    problem: 'AI로 다른 언어로 옮긴 라이브러리를 쓰는 팀이 원본의 보안 수정 반영 여부를 알 수 없다.',
    tech: '코드 대응 관계 추정 도구 · GitHub ★880 · MIT', comp: '의존성 취약점 알림 서비스 B — 빈틈: 번역 포크는 추적 못 함',
    effort: '1인 3주', diff: '번역 포크 전용 비교', risk: '번역된 코드 대응 정확도', valid: '공개 번역 포크 3개 수동 비교 리포트 → 관리자 반응 확인'},
  {id: 'c4', date: '11월 18일', title: '연구실 슬랙으로 매일 논문·모델 소식을 골라 보내는 다이제스트', one: '연구 주제 키워드로 arXiv·HF 소식을 골라 한 줄 요약해 보내 줌', cat: '데이터·검색', badges: ['연속 등장'], score: 6.9, tags: ['research-digest', 'arxiv'],
    why: '단백질 서열 학습 소식이 3일 중 2일 등장했고, 연구실 정보 공유가 번거롭다는 글이 있었다.',
    problem: '대학원 연구실이 새 논문·모델을 각자 찾아 공유하느라 중복이 많고 놓치는 것도 많다.',
    tech: '단백질 서열로 학습한 모델 공개 · HN 165점', demand: '"연구실 논문 공유 어떻게 하세요?" · HN 댓글', quotes: [['phd_park', '슬랙 채널에 링크만 쌓여서 아무도 안 읽음']],
    effort: '1인 2주', diff: '연구실 단위 + 주제 키워드 학습', risk: '연구실이 돈을 낼지', valid: '연구실 3곳에 2주 무료 제공, 클릭률·해지 의사 확인'},
];

export type Trend = 'UP' | 'FLAT' | 'DOWN';
export type Tracked = {
  id: string; from: string; title: string; short: string; cat: string; started: string; last: string; news: number;
  trend: Trend; strength: number; quiet?: boolean; keywords: string[]; comps: string[]; bars: number[];
  updates: {date: string; summary: string; changes: string[]; n: number}[];
};
export const TRACKED: Tracked[] = [
  {id: 't1', from: 'c1', title: '로컬 LLM 회의록 앱', short: '회의록', cat: '업무 자동화', started: '11/13', last: '오늘', news: 3, trend: 'UP', strength: 7.8,
    keywords: ['meeting-notes', 'local-llm', 'whisper', '설치형 회의록'], comps: ['클라우드 회의록 서비스 A', '오픈소스 B (신규)'], bars: [1, 0, 2, 0, 1, 0, 3],
    updates: [
      {date: '11월 19일', summary: '설치형 회의록 오픈소스가 새로 공개됐고(경쟁), 보안 규정 불만 글이 2건 더 올라왔다(수요).', changes: ['새 경쟁 제품', '수요 증가'], n: 3},
      {date: '11월 17일', summary: '양자화 음성 모델 v2가 나와 노트북 처리 속도가 1.6배 빨라졌다(업체 주장).', changes: ['기술 새 버전'], n: 1},
      {date: '11월 14일', summary: '경쟁 서비스 A가 가격을 20% 올렸다는 글.', changes: ['위험 변화'], n: 1},
    ]},
  {id: 't2', from: 'c2', title: '소상공인 문의 응대 봇', short: '응대 봇', cat: '커머스·마케팅', started: '11/18', last: '오늘', news: 1, trend: 'FLAT', strength: 5.1,
    keywords: ['customer-support', 'smb', 'ai-agent'], comps: [], bars: [0, 0, 0, 0, 0, 1, 1],
    updates: [{date: '11월 19일', summary: '메신저 API 정책 변경 예고 — 자동 응답 제한 가능성.', changes: ['위험 변화'], n: 1}]},
  {id: 't3', from: 'c4', title: '연구실 논문 다이제스트', short: '논문', cat: '데이터·검색', started: '10/20', last: '31일 전', news: 0, trend: 'DOWN', strength: 1.2, quiet: true,
    keywords: ['research-digest', 'arxiv'], comps: ['연구 요약 서비스 C'], bars: [0, 0, 0, 0, 0, 0, 0], updates: []},
];
export const TREND_LABEL: Record<Trend, string> = {UP: '▲ 상승', FLAT: '■ 유지', DOWN: '▼ 하락'};
export const TREND_VARIANT: Record<Trend, 'success' | 'neutral' | 'error'> = {UP: 'success', FLAT: 'neutral', DOWN: 'error'};

export const CATEGORIES = ['개발 도구', '업무 자동화', '콘텐츠 제작', '음성·대화', '데이터·검색', '교육·학습', '커머스·마케팅', '개인 생산성', '건강·생활', '기타'];

export const SOURCES = [
  {id: 'hn', name: 'Hacker News', on: true, stat: '하루 10 · 통과율 60% · 평균 6.4', queries: ['LLM', 'AI agent', 'Show HN AI', 'GPT', 'Claude', 'open source model'], min: 20, max: 60, window: '24시간'},
  {id: 'gh', name: 'GitHub', on: true, stat: '하루 115 · 통과율 13% · 스팸 많음', queries: ['topic:llm', 'topic:ai-agent', 'topic:generative-ai'], min: 200, max: 50, window: '14일'},
  {id: 'hf', name: 'Hugging Face', on: false, stat: '3단계에서 추가', queries: [], min: 0, max: 30, window: '-'},
  {id: 'rd', name: 'Reddit', on: false, stat: '키 필요 · 3단계', queries: [], min: 100, max: 60, window: '하루'},
  {id: 'ph', name: 'Product Hunt', on: false, stat: '토큰 필요 · 3단계', queries: [], min: 50, max: 20, window: '오늘'},
];

export const RUNS = [
  {id: 'r1', when: '11/19 07:03', result: '성공', funnel: '412 → 18 → 5', cost: '$0.14'},
  {id: 'r2', when: '11/18 09:41', result: '일부 실패', funnel: '388 → 15 → 5', cost: '$0.13'},
  {id: 'r3', when: '11/18 07:02', result: '교체됨', funnel: '380 → 16 → 5', cost: '$0.15'},
  {id: 'r4', when: '11/17 07:05', result: '성공', funnel: '401 → 14 → 4', cost: '$0.12'},
];

export const TOPICS = [
  {label: '로컬 회의록 정리', key: 'meeting-notes-pain', n: 6, range: '11/02 ~ 11/19', bars: [1, 0, 1, 1, 0, 1, 1, 1]},
  {label: 'Gemini 4 시리즈', key: 'gemini-4', n: 5, range: '10/29 ~ 11/18', bars: [1, 0, 0, 1, 0, 1, 0, 1]},
  {label: 'AI 코드 번역 포크', key: 'ai-code-port', n: 3, range: '11/12 ~ 11/19', bars: [0, 0, 0, 0, 1, 0, 1, 1]},
];

export const INBOX = {
  pass: [['hn', 'AI agents ask better questions than KPMG accountants…', '356', '수요 8.1'], ['github', 'DIYgod/pinky', '★2464', '기술 7.4'], ['hn', 'Energy-Based Reasoners: 385x more efficient…', '300', '기술 7.9']],
  drop: [['github', '…/Gemini-Config-Checker- 외 20개', '★473', '스팸 묶음'], ['hn', 'GTA 6 Review: It Is a Masterpiece', '65', '무관'], ['hn', 'Attackers Used Optimizely to A/B Test…', '54', '기준 점수 미달 4.2'], ['github', '…/ai--manus2.0 외 8개', '★310', '스팸 묶음']],
};
