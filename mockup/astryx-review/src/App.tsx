import {useState} from 'react';
import {Theme} from '@astryxdesign/core/theme';
import {Avatar} from '@astryxdesign/core/Avatar';
import {Badge} from '@astryxdesign/core/Badge';
import {Banner} from '@astryxdesign/core/Banner';
import {BottomSheet} from '@astryxdesign/core/BottomSheet';
import {Button} from '@astryxdesign/core/Button';
import {Card} from '@astryxdesign/core/Card';
import {ClickableCard} from '@astryxdesign/core/ClickableCard';
import {Divider} from '@astryxdesign/core/Divider';
import {Heading} from '@astryxdesign/core/Heading';
import {HStack} from '@astryxdesign/core/HStack';
import {Icon} from '@astryxdesign/core/Icon';
import {IconButton} from '@astryxdesign/core/IconButton';
import {ProgressBar} from '@astryxdesign/core/ProgressBar';
import {Switch} from '@astryxdesign/core/Switch';
import {Text} from '@astryxdesign/core/Text';
import {TextInput} from '@astryxdesign/core/TextInput';
import {Token} from '@astryxdesign/core/Token';
import {VStack} from '@astryxdesign/core/VStack';
import {
  ArchiveBoxIcon, ArrowPathIcon, ChevronLeftIcon, Cog6ToothIcon, HomeIcon, StarIcon,
} from '@heroicons/react/24/outline';
import {
  ArchiveBoxIcon as ArchiveSolid, Cog6ToothIcon as CogSolid, HomeIcon as HomeSolid, StarIcon as StarSolid,
} from '@heroicons/react/24/solid';
import {themes, defaultThemeSlug} from './astryx-themes';

/* Astryx hi-fi 시안 — "feed" 테마(인스타그램풍 톤)로 SC-01 오늘 / SC-02 상세 / SC-03 보관함 / SC-11 AI 공급자.
   데이터는 가상. 화면 흐름 전체는 mockup/index.html 와이어프레임이 기준. */

type Idea = {
  id: string; title: string; one: string; cat: string; badges: string[]; score: number; date: string;
  why: string; problem: string; tech: string; demand?: string; quotes?: [string, string][]; comp?: string;
  hist?: string; effort: string; diff: string; risk: string; valid: string; caveat?: string;
};

const IDEAS: Idea[] = [
  {id: 'c1', date: '11월 19일', title: '로컬 LLM으로 회의록을 요약하는 사내 설치형 앱', one: '녹음 파일을 PC 안에서만 처리해 요약·할 일을 뽑아 주는 데스크톱 앱', cat: '업무 자동화', badges: ['여러 곳에서 화제'], score: 8.4,
    why: '경량 모델이 노트북에서도 돌기 시작했고(기술), 회의록 SaaS의 보안·가격 불만 글이 이번 주 두 곳에서 올라왔다(수요).',
    problem: '보안 규정 때문에 클라우드 회의록을 못 쓰는 20~100명 회사의 팀장이 회의 정리에 매주 2~3시간을 쓴다.',
    tech: '경량 음성 인식 + 4bit 양자화 모델 공개 · GitHub ★2,464 · MIT', demand: '"보안 때문에 회의록 툴을 못 써요" · Reddit r/SaaS · 추천 180',
    quotes: [['dev_kim', '우리 회사도 외부 업로드 금지라 결국 손으로 정리함'], ['ops_lee', '(반대) 요즘은 설치형 옵션 주는 곳도 있음']],
    comp: '클라우드 회의록 서비스 A · Product Hunt 추천 420 — 빈틈: 설치형 없음, 인당 월 과금', hist: '11월 13일 카드 "오프라인 음성 메모 정리"와 같은 문제',
    effort: '1인 3주', diff: '완전 오프라인 + 1회 구매형', risk: '노트북 사양별 처리 속도 편차', valid: '보안팀 있는 지인 회사 3곳에 2주 시범 설치, 주간 사용 횟수 측정', caveat: '처리 속도는 업체 주장(독립 측정 없음)'},
  {id: 'c2', date: '11월 19일', title: '소상공인용 고객 문의 1차 응대 봇', one: '메신저 DM 문의를 FAQ와 예약표로 먼저 답해 주는 봇', cat: '커머스·마케팅', badges: ['급상승'], score: 7.6,
    why: '에이전트 프레임워크가 공개 하루 만에 HN 300점을 넘었고, 같은 날 "DM 답장에 밤샌다"는 글이 올라왔다.',
    problem: '1~3인 매장 사장이 영업 중 DM 문의에 바로 답하지 못해 예약을 놓친다.',
    tech: '오픈소스 에이전트 프레임워크 v1 · HN 300점 · Apache-2.0', demand: '"영업 끝나고 DM 답장만 1시간" · Reddit · 추천 95',
    quotes: [['cafe_owner', '예약 문의가 제일 많고 답이 늦으면 바로 다른 곳 감']],
    effort: '1인 4주', diff: '매장 예약표 연동 + 한국어 메신저', risk: '메신저 API 정책 제한', valid: '동네 매장 5곳 수동 응대 대행으로 2주 수요 확인', caveat: '근거가 출처 하나씩뿐'},
  {id: 'c3', date: '11월 19일', title: 'AI로 옮긴 코드가 원본 수정사항을 놓치는지 알려주는 감시 도구', one: '원본의 버그·보안 수정을 못 따라간 "번역 포크"를 찾아 알려 줌', cat: '개발 도구', badges: [], score: 7.2,
    why: 'AI 에이전트로 옮긴 대형 오픈소스 클론이 3주 만에 원본 수정 200여 건을 놓쳤다는 글이 화제였다.',
    problem: 'AI로 다른 언어로 옮긴 라이브러리를 쓰는 팀이 원본의 보안 수정 반영 여부를 알 수 없다.',
    tech: '코드 대응 관계 추정 도구 · GitHub ★880 · MIT', comp: '의존성 취약점 알림 서비스 B — 빈틈: 번역 포크는 추적 못 함',
    effort: '1인 3주', diff: '번역 포크 전용 비교', risk: '번역된 코드 대응 정확도', valid: '공개 번역 포크 3개 수동 비교 리포트 → 관리자 반응 확인'},
  {id: 'c4', date: '11월 18일', title: '연구실 슬랙으로 매일 논문·모델 소식을 골라 보내는 다이제스트', one: '연구 주제 키워드로 arXiv·HF 소식을 골라 한 줄 요약해 보내 줌', cat: '데이터·검색', badges: ['연속 등장'], score: 6.9,
    why: '단백질 서열 학습 소식이 3일 중 2일 등장했고, 연구실 정보 공유가 번거롭다는 글이 있었다.',
    problem: '대학원 연구실이 새 논문·모델을 각자 찾아 공유하느라 중복이 많고 놓치는 것도 많다.',
    tech: '단백질 서열로 학습한 모델 공개 · HN 165점', demand: '"연구실 논문 공유 어떻게 하세요?" · HN 댓글', quotes: [['phd_park', '슬랙 채널에 링크만 쌓여서 아무도 안 읽음']],
    effort: '1인 2주', diff: '연구실 단위 + 주제 키워드 학습', risk: '연구실이 돈을 낼지', valid: '연구실 3곳에 2주 무료 제공, 클릭률·해지 의사 확인'},
];
const TOPICS = [
  {label: '회의록', n: 6, seen: false}, {label: 'Gemini 4', n: 5, seen: false}, {label: '코드 번역', n: 3, seen: false},
  {label: '에이전트', n: 4, seen: true}, {label: '로컬 LLM', n: 7, seen: true}, {label: '연구 요약', n: 2, seen: true},
];

type Screen = 'today' | 'detail' | 'archive' | 'settings';
type Mode = 'light' | 'dark';

export default function App() {
  const [mode, setMode] = useState<Mode>('light');
  const [screen, setScreen] = useState<Screen>('today');
  const [backTo, setBackTo] = useState<Screen>('today');
  const [ideaId, setIdeaId] = useState('c1');
  const [stars, setStars] = useState<Record<string, boolean>>({c2: true});
  const [hidden, setHidden] = useState<Record<string, string>>({});
  const [sheet, setSheet] = useState(false);
  const [running, setRunning] = useState(false);
  const [query, setQuery] = useState('');
  const [onlyStar, setOnlyStar] = useState(false);
  const [fallback, setFallback] = useState(true);

  const idea = IDEAS.find(i => i.id === ideaId)!;
  const open = (id: string, from: Screen) => { setIdeaId(id); setBackTo(from); setScreen('detail'); };
  const toggleStar = (id: string) => setStars(s => ({...s, [id]: !s[id]}));

  const review = (
    <div className="review-bar">
      {(['today', 'detail', 'archive', 'settings'] as Screen[]).map(s => (
        <button key={s} className={screen === s ? 'on' : ''} onClick={() => setScreen(s)}>
          {{today: 'SC-01 오늘', detail: 'SC-02 상세', archive: 'SC-03 보관함', settings: 'SC-11 AI 공급자'}[s]}
        </button>
      ))}
      <span style={{width: 12}} />
      {(['light', 'dark'] as Mode[]).map(m => (
        <button key={m} className={mode === m ? 'on' : ''} onClick={() => setMode(m)}>{m === 'light' ? '라이트' : '다크'}</button>
      ))}
    </div>
  );

  return (
    <div className="review">
      {review}
      <Theme theme={themes[defaultThemeSlug]} mode={mode}>
        <div className="phone">
          {/* 상단 바 */}
          <HStack paddingInline={4} paddingBlock={3} vAlign="center" justify="between" gap={2}>
            {screen === 'detail' ? (
              <HStack gap={1} vAlign="center">
                <IconButton label="뒤로" icon={<Icon icon={ChevronLeftIcon} size="lg" />} variant="ghost" onClick={() => setScreen(backTo)} />
                <Heading level={3}>카드 상세</Heading>
              </HStack>
            ) : (
              <Heading level={2}>{{today: '오늘의 아이디어', archive: '보관함', settings: 'AI 공급자·모델', detail: ''}[screen]}</Heading>
            )}
            {screen === 'today' && (
              <IconButton label="지금 실행" tooltip="지금 실행" icon={<Icon icon={ArrowPathIcon} size="lg" />} variant="ghost" isDisabled={running} onClick={() => setRunning(true)} />
            )}
            {screen === 'detail' && (
              <IconButton label={stars[idea.id] ? '관심 해제' : '관심'} icon={<Icon icon={stars[idea.id] ? StarSolid : StarIcon} size="lg" color={stars[idea.id] ? 'accent' : 'primary'} />} variant="ghost" onClick={() => toggleStar(idea.id)} />
            )}
          </HStack>
          <Divider />

          <div className="phone-body">
            {screen === 'today' && (
              <VStack gap={3} padding={4}>
                {/* 반복 주제: 인스타그램 스토리처럼 (SC-04 진입) */}
                <div className="story">
                  {TOPICS.map(t => (
                    <div className="story-item" key={t.label}>
                      <div className={`story-ring ${t.seen ? 'seen' : ''}`}><Avatar name={t.label} size="lg" /></div>
                      <Text type="supporting" maxLines={1}>{t.label}</Text>
                    </div>
                  ))}
                </div>
                <HStack justify="between" vAlign="center">
                  <VStack gap={0.5}>
                    <Text type="label">11월 19일 (수)</Text>
                    <Text type="supporting">수집 412 → 후보 18 → 카드 5</Text>
                  </VStack>
                  <Text type="supporting">07:03 · $0.14</Text>
                </HStack>
                {running && <Card variant="muted"><VStack gap={2}><Text type="label">실행 중 · 분류 중…</Text><ProgressBar label="진행" value={55} /></VStack></Card>}
                <Banner status="warning" title="Reddit 수집 실패" description="나머지 출처로 만들었습니다" />
                {IDEAS.filter(i => i.date === '11월 19일').map((i, n) => (
                  <IdeaCard key={i.id} idea={i} n={n + 1} starred={!!stars[i.id]} muted={!!hidden[i.id]} onOpen={() => open(i.id, 'today')} onStar={() => toggleStar(i.id)} />
                ))}
              </VStack>
            )}

            {screen === 'detail' && (
              <VStack gap={3} padding={4}>
                <VStack gap={1}>
                  <Heading level={2}>{idea.title}</Heading>
                  <Text type="supporting">{idea.one}</Text>
                </VStack>
                <HStack gap={1}><Token label={idea.cat} size="sm" color="blue" />{idea.badges.map(b => <Badge key={b} label={b} variant="warning" />)}<Badge label={`AI ${idea.score}`} variant="neutral" /></HStack>
                <Block title="오늘 고른 이유"><Text>{idea.why}</Text></Block>
                <Block title="문제"><Text>{idea.problem}</Text></Block>
                <Block title="기술 신호"><Signal text={idea.tech} /></Block>
                {idea.demand && <Block title="수요 신호"><Signal text={idea.demand} quotes={idea.quotes} /></Block>}
                {idea.comp && <Block title="경쟁 제품"><Signal text={idea.comp} /></Block>}
                {idea.hist && <Block title="과거 언급"><Text type="supporting">{idea.hist}</Text></Block>}
                <Block title="실행 판단">
                  <Card variant="muted"><VStack gap={1.5}>
                    <Row k="기간" v={idea.effort} /><Row k="차별점" v={idea.diff} /><Row k="가장 큰 위험" v={idea.risk} /><Row k="2주 검증" v={idea.valid} />
                  </VStack></Card>
                </Block>
                {idea.caveat && <Banner status="info" title="확인 필요" description={idea.caveat} />}
                <HStack gap={2}>
                  <Button label="관심 없음" variant="secondary" onClick={() => setSheet(true)} />
                  <Button label={stars[idea.id] ? '관심 해제' : '관심 표시'} variant="primary" onClick={() => toggleStar(idea.id)} />
                </HStack>
              </VStack>
            )}

            {screen === 'archive' && (
              <VStack gap={3} padding={4}>
                <TextInput label="검색" isLabelHidden value={query} onChange={v => setQuery(v)} />
                <HStack gap={1}>
                  <Token label="★ 관심만" color={onlyStar ? 'blue' : 'default'} onClick={() => setOnlyStar(v => !v)} />
                  <Token label="분류" /><Token label="태그" />
                </HStack>
                {['11월 19일', '11월 18일'].map(d => {
                  const list = IDEAS.filter(i => i.date === d && (!onlyStar || stars[i.id]) && (!query || i.title.includes(query) || i.one.includes(query)));
                  if (!list.length) return null;
                  return (
                    <VStack gap={2} key={d}>
                      <Text type="label">{d}</Text>
                      {list.map((i, n) => <IdeaCard key={i.id} idea={i} n={n + 1} starred={!!stars[i.id]} muted={!!hidden[i.id]} onOpen={() => open(i.id, 'archive')} onStar={() => toggleStar(i.id)} compact />)}
                    </VStack>
                  );
                })}
              </VStack>
            )}

            {screen === 'settings' && (
              <VStack gap={3} padding={4}>
                <Text type="supporting">공급자 키 — 하나 이상 필요</Text>
                <Card><VStack gap={3}>
                  <Provider name="Claude (Anthropic)" state="연결됨 ··· a1b2" ok />
                  <Divider />
                  <Provider name="OpenAI" state="연결됨 ··· c3d4" ok />
                </VStack></Card>
                <Text type="supporting">단계별 모델</Text>
                <Card><VStack gap={3}>
                  <Row k="1차 분류" v="Claude · claude-haiku-5-5" />
                  <Row k="카드 작성" v="Claude · claude-opus-5-5" />
                  <Divider />
                  <HStack justify="between" vAlign="center">
                    <VStack gap={0.5}><Text>대체 공급자 사용</Text><Text type="supporting">실패·거절 시 다른 공급자로 1회</Text></VStack>
                    <Switch label="대체 공급자 사용" isLabelHidden value={fallback} onChange={v => setFallback(v)} />
                  </HStack>
                  <Row k="일일 예산" v="$0.50 · 이번 달 $2.31 (추정)" />
                </VStack></Card>
              </VStack>
            )}
          </div>

          {/* 하단 탭 — 아이콘만, 선택된 탭은 채운 아이콘 (인스타그램풍) */}
          <Divider />
          <HStack justify="around" paddingBlock={2}>
            <TabIcon label="오늘" on={screen === 'today' || (screen === 'detail' && backTo === 'today')} outline={HomeIcon} solid={HomeSolid} onClick={() => setScreen('today')} />
            <TabIcon label="보관함" on={screen === 'archive' || (screen === 'detail' && backTo === 'archive')} outline={ArchiveBoxIcon} solid={ArchiveSolid} onClick={() => setScreen('archive')} />
            <TabIcon label="설정" on={screen === 'settings'} outline={Cog6ToothIcon} solid={CogSolid} onClick={() => setScreen('settings')} />
          </HStack>

          <BottomSheet isOpen={sheet} onOpenChange={setSheet} label="관심 없음 이유" height="hug">
            <VStack gap={3} padding={4}>
              <VStack gap={0.5}><Heading level={3}>관심 없음 — 이유를 골라 주세요</Heading><Text type="supporting">다음 날 카드 작성에 반영됩니다</Text></VStack>
              <HStack gap={1}>
                {['이미 있음', '너무 큼', '내 분야 아님', '근거 약함', '기타'].map(r => (
                  <Token key={r} label={r} onClick={() => { setHidden(h => ({...h, [idea.id]: r})); setSheet(false); setScreen(backTo); }} />
                ))}
              </HStack>
            </VStack>
          </BottomSheet>
        </div>
      </Theme>
    </div>
  );
}

type SvgIcon = React.ComponentType<React.SVGProps<SVGSVGElement>>;

function TabIcon({label, on, outline, solid, onClick}: {label: string; on: boolean; outline: SvgIcon; solid: SvgIcon; onClick: () => void}) {
  return <IconButton label={label} tooltip={label} icon={<Icon icon={on ? solid : outline} size="lg" color="primary" />} variant="ghost" onClick={onClick} />;
}

function IdeaCard({idea, n, starred, muted, onOpen, onStar, compact}: {idea: Idea; n: number; starred: boolean; muted: boolean; onOpen: () => void; onStar: () => void; compact?: boolean}) {
  return (
    <ClickableCard label={idea.title} onClick={onOpen}>
      <VStack gap={compact ? 1 : 2}>
        <HStack justify="between" vAlign="start" gap={2}>
          <Heading level={4}>{n}. {idea.title}</Heading>
          <IconButton label={starred ? '관심 해제' : '관심'} icon={<Icon icon={starred ? StarSolid : StarIcon} color={starred ? 'accent' : 'secondary'} />} variant="ghost" size="sm"
            onClick={e => { e.stopPropagation(); onStar(); }} />
        </HStack>
        {!compact && <Text type="supporting">{idea.one}</Text>}
        <HStack gap={1}>
          <Token label={idea.cat} size="sm" color="blue" />
          {idea.badges.map(b => <Badge key={b} label={b} variant="warning" />)}
          <Badge label={`AI ${idea.score}`} variant="neutral" />
          {muted && <Badge label="관심 없음" variant="neutral" />}
        </HStack>
      </VStack>
    </ClickableCard>
  );
}

function Block({title, children}: {title: string; children: React.ReactNode}) {
  return <VStack gap={1}><Text type="label">{title}</Text>{children}</VStack>;
}

function Signal({text, quotes}: {text: string; quotes?: [string, string][]}) {
  return (
    <Card variant="muted"><VStack gap={1.5}>
      <Text>{text}</Text>
      {quotes?.map(([who, what]) => <Text key={who} type="supporting"><b>{who}</b> · {what}</Text>)}
      <HStack gap={1}><Button label="원문" variant="ghost" size="sm" />{quotes && <Button label="토론" variant="ghost" size="sm" />}</HStack>
    </VStack></Card>
  );
}

function Row({k, v}: {k: string; v: string}) {
  return <HStack justify="between" gap={3} vAlign="start"><Text type="supporting">{k}</Text><Text>{v}</Text></HStack>;
}

function Provider({name, state, ok}: {name: string; state: string; ok: boolean}) {
  return (
    <VStack gap={1.5}>
      <HStack justify="between" vAlign="center"><Text type="label">{name}</Text><Badge label={state} variant={ok ? 'success' : 'error'} /></HStack>
      <HStack gap={1}><Button label="교체" variant="secondary" size="sm" /><Button label="다시 확인" variant="secondary" size="sm" /><Button label="삭제" variant="ghost" size="sm" /></HStack>
    </VStack>
  );
}
