import {useMemo, useState} from 'react';
import type {ReactNode} from 'react';
import {Theme} from '@astryxdesign/core/theme';
import {Avatar} from '@astryxdesign/core/Avatar';
import {Badge} from '@astryxdesign/core/Badge';
import {Banner} from '@astryxdesign/core/Banner';
import {BottomSheet} from '@astryxdesign/core/BottomSheet';
import {Button} from '@astryxdesign/core/Button';
import {Card} from '@astryxdesign/core/Card';
import {ClickableCard} from '@astryxdesign/core/ClickableCard';
import {Divider} from '@astryxdesign/core/Divider';
import {EmptyState} from '@astryxdesign/core/EmptyState';
import {Heading} from '@astryxdesign/core/Heading';
import {HStack} from '@astryxdesign/core/HStack';
import {Icon} from '@astryxdesign/core/Icon';
import {IconButton} from '@astryxdesign/core/IconButton';
import {ProgressBar} from '@astryxdesign/core/ProgressBar';
import {SegmentedControl, SegmentedControlItem} from '@astryxdesign/core/SegmentedControl';
import {Step, Stepper} from '@astryxdesign/core/Stepper';
import {Switch} from '@astryxdesign/core/Switch';
import {Text} from '@astryxdesign/core/Text';
import {TextArea} from '@astryxdesign/core/TextArea';
import {TextInput} from '@astryxdesign/core/TextInput';
import {Token} from '@astryxdesign/core/Token';
import {VStack} from '@astryxdesign/core/VStack';
import {
  ArchiveBoxIcon, ArrowPathIcon, ChevronLeftIcon, ChevronRightIcon, Cog6ToothIcon, HomeIcon, SignalIcon, StarIcon,
} from '@heroicons/react/24/outline';
import {
  ArchiveBoxIcon as ArchiveSolid, Cog6ToothIcon as CogSolid, HomeIcon as HomeSolid, SignalIcon as SignalSolid, StarIcon as StarSolid,
} from '@heroicons/react/24/solid';
import {themes} from './astryx-themes';
import {IDEAS, TRACKED, TREND_LABEL, TREND_VARIANT, CATEGORIES, SOURCES, RUNS, TOPICS, INBOX} from './data';
import type {Idea} from './data';
import {PROMPTS} from './prompts';

/* 전체 메뉴 hi-fi 시안 — 디자인 A "피드"(인스타그램풍). 화면 번호는 docs/menu.md 기준. 데이터는 가상, 프롬프트는 실제 기본값. */

type Tab = 'today' | 'track' | 'archive' | 'settings';
type ScreenDef = {id: string; name: string; group: string; tab?: Tab; root?: boolean; l?: boolean; outside?: boolean};
const SCREENS: ScreenDef[] = [
  {id: 'SC-00', name: '첫 실행 설정', group: '시작'},
  {id: 'SC-01', name: '오늘의 카드', group: '오늘', tab: 'today', root: true},
  {id: 'SC-02', name: '카드 상세', group: '오늘', tab: 'today'},
  {id: 'SC-13', name: '추적 목록', group: '추적', tab: 'track', root: true},
  {id: 'SC-14', name: '추적 상세', group: '추적', tab: 'track'},
  {id: 'SC-03', name: '지난 카드', group: '보관함', tab: 'archive', root: true},
  {id: 'SC-04', name: '반복 주제', group: '보관함', tab: 'archive', l: true},
  {id: 'SC-06', name: '설정 홈', group: '설정', tab: 'settings', root: true},
  {id: 'SC-11', name: 'AI 공급자·모델', group: '설정', tab: 'settings'},
  {id: 'SC-15', name: '프롬프트', group: '설정', tab: 'settings'},
  {id: 'SC-16', name: '프롬프트 편집', group: '설정', tab: 'settings'},
  {id: 'SC-07', name: '출처 관리', group: '설정', tab: 'settings'},
  {id: 'SC-07a', name: '출처 상세', group: '설정', tab: 'settings'},
  {id: 'SC-08', name: '신호 프로필', group: '설정', tab: 'settings', l: true},
  {id: 'SC-05', name: '오늘 수집 항목', group: '설정', tab: 'settings', l: true},
  {id: 'SC-09', name: '내 기준·카드 구성', group: '설정', tab: 'settings'},
  {id: 'SC-10', name: '실행 기록', group: '설정', tab: 'settings'},
  {id: 'SC-10a', name: '실행 상세', group: '설정', tab: 'settings'},
  {id: 'SC-11b', name: '일정·메일·출처 키', group: '설정', tab: 'settings'},
  {id: 'SC-12', name: '정보', group: '설정', tab: 'settings'},
  {id: 'MAIL', name: '메일 리포트', group: '앱 밖', outside: true},
  {id: 'NOTI', name: '알림', group: '앱 밖', outside: true},
];
const byId = Object.fromEntries(SCREENS.map(s => [s.id, s]));
const TAB_ROOT: Record<Tab, string> = {today: 'SC-01', track: 'SC-13', archive: 'SC-03', settings: 'SC-06'};

type Override = {text: string; version: number; history: {version: number; text: string; at: string}[]};

export default function App() {
  const [mode, setMode] = useState<'light' | 'dark'>('light');
  const [stack, setStack] = useState<string[]>(['SC-01']);
  const [params, setParams] = useState<Record<string, string>>({idea: 'c1', track: 't1', prompt: 'ideas', source: 'hn'});
  const [stars, setStars] = useState<Record<string, boolean>>({c1: true, c2: true});
  const [hidden, setHidden] = useState<Record<string, string>>({});
  const [overrides, setOverrides] = useState<Record<string, Override>>({
    'profile.demand-signal.analysis': {text: PROMPTS.find(p => p.id === 'profile.demand-signal.analysis')!.text.replace('9–10 |', '9–10 | (한국 시장 사례가 있으면 우대) '), version: 2, history: [{version: 1, text: '', at: '11/12'}, {version: 2, text: '', at: '11/18'}]},
  });
  const [toast, setToast] = useState<string | null>(null);
  const screen = stack[stack.length - 1];
  const def = byId[screen];

  const go = (id: string, p: Record<string, string> = {}) => { setParams(x => ({...x, ...p})); setStack(s => [...s, id]); };
  const jump = (id: string, p: Record<string, string> = {}) => { setParams(x => ({...x, ...p})); setStack([id]); };
  const back = () => setStack(s => (s.length > 1 ? s.slice(0, -1) : s));
  const say = (m: string) => { setToast(m); window.setTimeout(() => setToast(t => (t === m ? null : t)), 2400); };
  const toggleStar = (id: string) => setStars(s => { const on = !s[id]; say(on ? '★ 추적을 시작했습니다 · 추적 탭에서 매일 업데이트' : '추적을 멈췄습니다 · 기록은 남습니다'); return {...s, [id]: on}; });

  const ctx: Ctx = {go, back, jump, say, params, stars, toggleStar, hidden, setHidden, overrides, setOverrides};
  const groups = [...new Set(SCREENS.map(s => s.group))];

  return (
    <div className="review2">
      <nav className="review-map">
        <div className="review-title">AI 아이디어 수집기 · 전체 화면 (디자인 A 피드)</div>
        <div className="review-bar" style={{justifyContent: 'flex-start'}}>
          {(['light', 'dark'] as const).map(m => <button key={m} className={mode === m ? 'on' : ''} onClick={() => setMode(m)}>{m === 'light' ? '라이트' : '다크'}</button>)}
        </div>
        {groups.map(g => (
          <div key={g}>
            <div className="review-group">{g}</div>
            {SCREENS.filter(s => s.group === g).map(s => (
              <a key={s.id} className={screen === s.id ? 'on' : ''} onClick={() => jump(s.id)}>
                <span className="sid">{s.id}</span>{s.name}{s.l && <span className="pill-l">L</span>}
              </a>
            ))}
          </div>
        ))}
        <div className="review-foot">가상 데이터 · 프롬프트는 core 리소스의 실제 기본값</div>
      </nav>
      <main className="review-stage">
        <Theme theme={themes.feed} mode={mode}>
          {def.outside ? (screen === 'MAIL' ? <MailView ctx={ctx} /> : <NotiView ctx={ctx} />) : (
            <div className="phone">
              <TopBar def={def} canBack={stack.length > 1 && !def.root} back={back} ctx={ctx} screen={screen} />
              <Divider />
              <div className="phone-body">
                <ScreenView screen={screen} ctx={ctx} />
              </div>
              {def.tab && <>
                <Divider />
                <HStack justify="around" paddingBlock={2}>
                  <TabIcon label="오늘" on={def.tab === 'today'} outline={HomeIcon} solid={HomeSolid} onClick={() => jump(TAB_ROOT.today)} />
                  <TabIcon label="추적" on={def.tab === 'track'} outline={SignalIcon} solid={SignalSolid} onClick={() => jump(TAB_ROOT.track)} />
                  <TabIcon label="보관함" on={def.tab === 'archive'} outline={ArchiveBoxIcon} solid={ArchiveSolid} onClick={() => jump(TAB_ROOT.archive)} />
                  <TabIcon label="설정" on={def.tab === 'settings'} outline={Cog6ToothIcon} solid={CogSolid} onClick={() => jump(TAB_ROOT.settings)} />
                </HStack>
              </>}
              {toast && <div className="toast2">{toast}</div>}
            </div>
          )}
        </Theme>
      </main>
    </div>
  );
}

type Ctx = {
  go: (id: string, p?: Record<string, string>) => void; back: () => void; jump: (id: string, p?: Record<string, string>) => void;
  say: (m: string) => void; params: Record<string, string>;
  stars: Record<string, boolean>; toggleStar: (id: string) => void;
  hidden: Record<string, string>; setHidden: (f: (h: Record<string, string>) => Record<string, string>) => void;
  overrides: Record<string, Override>; setOverrides: (f: (o: Record<string, Override>) => Record<string, Override>) => void;
};

function TopBar({def, canBack, back, ctx, screen}: {def: ScreenDef; canBack: boolean; back: () => void; ctx: Ctx; screen: string}) {
  const right = screen === 'SC-01'
    ? <IconButton label="지금 실행" tooltip="지금 실행" icon={<Icon icon={ArrowPathIcon} size="lg" />} variant="ghost" onClick={() => ctx.say('오늘 카드를 새 결과로 바꿉니다 · 실행 중…')} />
    : screen === 'SC-02'
      ? <IconButton label="관심" icon={<Icon icon={ctx.stars[ctx.params.idea] ? StarSolid : StarIcon} size="lg" color={ctx.stars[ctx.params.idea] ? 'accent' : 'primary'} />} variant="ghost" onClick={() => ctx.toggleStar(ctx.params.idea)} />
      : null;
  const title = screen === 'SC-01' ? '오늘의 아이디어' : screen === 'SC-13' ? '추적' : screen === 'SC-03' ? '보관함' : screen === 'SC-06' ? '설정' : def.name;
  return (
    <HStack paddingInline={canBack ? 2 : 4} paddingBlock={3} vAlign="center" justify="between" gap={1}>
      <HStack gap={1} vAlign="center">
        {canBack && <IconButton label="뒤로" icon={<Icon icon={ChevronLeftIcon} size="lg" />} variant="ghost" onClick={back} />}
        <Heading level={def.root ? 2 : 3}>{title}</Heading>
      </HStack>
      {right}
    </HStack>
  );
}

function ScreenView({screen, ctx}: {screen: string; ctx: Ctx}) {
  switch (screen) {
    case 'SC-00': return <Onboarding ctx={ctx} />;
    case 'SC-01': return <Today ctx={ctx} />;
    case 'SC-02': return <Detail ctx={ctx} />;
    case 'SC-13': return <TrackList ctx={ctx} />;
    case 'SC-14': return <TrackDetail ctx={ctx} />;
    case 'SC-03': return <Archive ctx={ctx} />;
    case 'SC-04': return <Topics />;
    case 'SC-05': return <Inbox />;
    case 'SC-06': return <SettingsHome ctx={ctx} />;
    case 'SC-07': return <Sources ctx={ctx} />;
    case 'SC-07a': return <SourceDetail ctx={ctx} />;
    case 'SC-08': return <Profiles ctx={ctx} />;
    case 'SC-09': return <Criteria ctx={ctx} />;
    case 'SC-10': return <Runs ctx={ctx} />;
    case 'SC-10a': return <RunDetail />;
    case 'SC-11': return <AiProviders ctx={ctx} />;
    case 'SC-11b': return <Schedule ctx={ctx} />;
    case 'SC-12': return <About />;
    case 'SC-15': return <Prompts ctx={ctx} />;
    case 'SC-16': return <PromptEdit ctx={ctx} />;
    default: return null;
  }
}

/* ───────── 공통 조각 ───────── */
type SvgIcon = React.ComponentType<React.SVGProps<SVGSVGElement>>;
function TabIcon({label, on, outline, solid, onClick}: {label: string; on: boolean; outline: SvgIcon; solid: SvgIcon; onClick: () => void}) {
  return <IconButton label={label} tooltip={label} icon={<Icon icon={on ? solid : outline} size="lg" color="primary" />} variant="ghost" onClick={onClick} />;
}
const Page = ({children}: {children: ReactNode}) => <VStack gap={3} padding={4}>{children}</VStack>;
const Block = ({title, children}: {title: string; children: ReactNode}) => <VStack gap={1}><Text type="label">{title}</Text>{children}</VStack>;
const Row = ({k, v}: {k: string; v: ReactNode}) => <HStack justify="between" gap={3} vAlign="start"><Text type="supporting">{k}</Text><Text>{v}</Text></HStack>;
const SectionLabel = ({children}: {children: ReactNode}) => <Text type="supporting">{children}</Text>;
function NavRow({title, sub, onClick, end}: {title: string; sub?: string; onClick: () => void; end?: ReactNode}) {
  return (
    <ClickableCard label={title} variant="transparent" padding={3} onClick={onClick}>
      <HStack justify="between" vAlign="center" gap={2}>
        <VStack gap={0.5}><Text>{title}</Text>{sub && <Text type="supporting">{sub}</Text>}</VStack>
        <HStack gap={1} vAlign="center">{end}<Icon icon={ChevronRightIcon} color="secondary" /></HStack>
      </HStack>
    </ClickableCard>
  );
}
function Group({children}: {children: ReactNode[]}) {
  const items = children.filter(Boolean);
  return <Card padding={0}><VStack gap={0}>{items.map((c, i) => <VStack key={i} gap={0}>{c}{i < items.length - 1 && <Divider />}</VStack>)}</VStack></Card>;
}
function Chips({items, selected, onToggle}: {items: string[]; selected: string[]; onToggle?: (v: string) => void}) {
  return <HStack gap={1}>{items.map(v => <Token key={v} label={v} size="sm" color={selected.includes(v) ? 'blue' : 'default'} onClick={onToggle ? () => onToggle(v) : undefined} />)}</HStack>;
}

function IdeaCard({idea, n, ctx, from}: {idea: Idea; n: number; ctx: Ctx; from?: string}) {
  const starred = !!ctx.stars[idea.id];
  return (
    <ClickableCard label={idea.title} onClick={() => ctx.go('SC-02', {idea: idea.id, from: from ?? 'today'})}>
      <VStack gap={2}>
        <HStack justify="between" vAlign="start" gap={2}>
          <Heading level={4}>{n}. {idea.title}</Heading>
          <IconButton label={starred ? '추적 중' : '관심(추적 시작)'} icon={<Icon icon={starred ? StarSolid : StarIcon} color={starred ? 'accent' : 'secondary'} />} variant="ghost" size="sm" onClick={e => { e.stopPropagation(); ctx.toggleStar(idea.id); }} />
        </HStack>
        <Text type="supporting">{idea.one}</Text>
        <HStack gap={1}>
          <Token label={idea.cat} size="sm" color="blue" />
          {idea.badges.map(b => <Badge key={b} label={b} variant="warning" />)}
          <Badge label={`AI ${idea.score}`} variant="neutral" />
          {starred && <Badge label="추적 중" variant="info" />}
          {ctx.hidden[idea.id] && <Badge label="관심 없음" variant="neutral" />}
        </HStack>
      </VStack>
    </ClickableCard>
  );
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

/* ───────── 시작 ───────── */
function Onboarding({ctx}: {ctx: Ctx}) {
  const [step, setStep] = useState(0);
  return (
    <Page>
      <Stepper activeStep={step} label="첫 실행 설정">
        <Step step={0} label="AI 키" /><Step step={1} label="메일" /><Step step={2} label="내 기준" /><Step step={3} label="첫 실행" />
      </Stepper>
      {step === 0 && <>
        <Heading level={3}>AI 공급자 키</Heading>
        <Text type="supporting">Claude 또는 OpenAI 중 하나 이상 필요합니다. 내 키로 직접 호출하고 비용은 각 공급자에 청구됩니다.</Text>
        <Card><VStack gap={2}>
          <HStack justify="between"><Text type="label">Claude (Anthropic)</Text><Badge label="연결됨 ··· a1b2" variant="success" /></HStack>
          <Text type="supporting">모델 9개 확인 · 방금</Text>
        </VStack></Card>
        <Card><VStack gap={2}>
          <HStack justify="between"><Text type="label">OpenAI</Text><Badge label="없음" variant="neutral" /></HStack>
          <Button label="키 등록" variant="secondary" onClick={() => ctx.say('키를 확인했습니다 — 모델 12개')} />
        </VStack></Card>
        <TextInput label="GitHub 토큰 (선택)" description="없으면 검색 호출 한도가 낮습니다" value="" onChange={() => {}} />
      </>}
      {step === 1 && <>
        <Heading level={3}>결과 받을 메일</Heading>
        <TextInput label="받는 주소" value="me@example.com" onChange={() => {}} />
        <TextInput label="보내는 계정 (Gmail)" value="sender@gmail.com" onChange={() => {}} />
        <TextInput label="앱 비밀번호" type="password" description="Google 계정 › 보안 › 2단계 인증 › 앱 비밀번호" value="" onChange={() => {}} />
        <Button label="테스트 발송" variant="secondary" onClick={() => ctx.say('테스트 메일을 보냈습니다')} />
      </>}
      {step === 2 && <><Heading level={3}>내 기준</Heading><Text type="supporting">카드 작성에 그대로 들어갑니다. 건너뛰면 기본값을 씁니다.</Text><CriteriaForm /></>}
      {step === 3 && <>
        <Heading level={3}>첫 실행</Heading>
        <Card variant="muted"><Text>매일 07:00, Wi-Fi일 때 자동 실행합니다. 첫 결과는 지금 만들 수 있습니다. 예상 1~3분, 약 $0.15.</Text></Card>
        <Banner status="warning" title="배터리 최적화 예외" description="허용해야 매일 실행이 안정적입니다" />
      </>}
      <HStack gap={2}>
        {step > 0 && <Button label="이전" variant="secondary" onClick={() => setStep(s => s - 1)} />}
        {step < 3 ? <Button label="다음" variant="primary" onClick={() => setStep(s => s + 1)} /> : <Button label="지금 첫 실행" variant="primary" onClick={() => ctx.jump('SC-01')} />}
      </HStack>
    </Page>
  );
}

function CriteriaForm() {
  const [scope, setScope] = useState('4주');
  const [who, setWho] = useState(['개발자', '소규모 사업자']);
  const [money, setMoney] = useState(['구독']);
  const tog = (arr: string[], set: (v: string[]) => void) => (v: string) => set(arr.includes(v) ? arr.filter(x => x !== v) : [...arr, v]);
  return (
    <VStack gap={3}>
      <Block title="개발 범위 (1인)">
        <SegmentedControl label="개발 범위" value={scope} onChange={setScope}>
          <SegmentedControlItem value="2주" label="2주" /><SegmentedControlItem value="4주" label="4주" /><SegmentedControlItem value="8주" label="8주" />
        </SegmentedControl>
      </Block>
      <Block title="대상 고객 (여러 개)"><Chips items={['개발자', '소규모 사업자', '일반 소비자', '기업', '교육']} selected={who} onToggle={tog(who, setWho)} /></Block>
      <TextInput label="관심 분야" value="업무 자동화, 개발 도구, 개인 생산성" onChange={() => {}} />
      <TextInput label="제외 분야" value="하드웨어, 의료 진단, 대규모 학습" onChange={() => {}} />
      <Block title="수익화 방식"><Chips items={['구독', '1회 구매', 'API 과금', '광고']} selected={money} onToggle={tog(money, setMoney)} /></Block>
    </VStack>
  );
}

/* ───────── 오늘 ───────── */
function TrackRow({ctx}: {ctx: Ctx}) {
  return (
    <div className="story">
      {[...TRACKED].sort((a, b) => b.news - a.news).map(t => (
        <div className="story-item" key={t.id} onClick={() => ctx.go('SC-14', {track: t.id})} style={{cursor: 'pointer'}}>
          <div className={`story-ring ${t.news ? '' : 'seen'}`}><Avatar name={t.short} size="lg" /></div>
          <Text type="supporting" maxLines={1}>{t.title}</Text>
        </div>
      ))}
    </div>
  );
}

function Today({ctx}: {ctx: Ctx}) {
  return (
    <Page>
      <TrackRow ctx={ctx} />
      <HStack justify="between" vAlign="center">
        <VStack gap={0.5}><Text type="label">11월 19일 (수)</Text><Text type="supporting">수집 412 → 후보 18 → 카드 5</Text></VStack>
        <Text type="supporting">07:03 · $0.14</Text>
      </HStack>
      <ClickableCard label="실행 상세" variant="transparent" padding={0} onClick={() => ctx.go('SC-10a')}>
        <Banner status="warning" title="Reddit 수집 실패" description="나머지 출처로 만들었습니다 · 눌러서 실행 상세" />
      </ClickableCard>
      {IDEAS.filter(i => i.date === '11월 19일').map((i, n) => <IdeaCard key={i.id} idea={i} n={n + 1} ctx={ctx} />)}
    </Page>
  );
}

function Detail({ctx}: {ctx: Ctx}) {
  const idea = IDEAS.find(i => i.id === ctx.params.idea)!;
  const [sheet, setSheet] = useState(false);
  return (
    <Page>
      <VStack gap={1}><Heading level={2}>{idea.title}</Heading><Text type="supporting">{idea.one}</Text></VStack>
      <HStack gap={1}><Token label={idea.cat} size="sm" color="blue" />{idea.badges.map(b => <Badge key={b} label={b} variant="warning" />)}{idea.tags.map(t => <Token key={t} label={`#${t}`} size="sm" />)}</HStack>
      {ctx.stars[idea.id] && <ClickableCard label="추적 보기" variant="transparent" padding={0} onClick={() => ctx.go('SC-14', {track: TRACKED.find(t => t.from === idea.id)?.id ?? 't1'})}><Banner status="info" title="추적 중" description="매일 새 소식을 조사합니다 · 눌러서 추적 상세" /></ClickableCard>}
      <Block title="오늘 고른 이유"><Text>{idea.why}</Text></Block>
      <Block title="문제"><Text>{idea.problem}</Text></Block>
      <Block title="기술 신호"><Signal text={idea.tech} /></Block>
      {idea.demand && <Block title="수요 신호"><Signal text={idea.demand} quotes={idea.quotes} /></Block>}
      {idea.comp && <Block title="경쟁 제품"><Signal text={idea.comp} /></Block>}
      {idea.hist && <Block title="과거 언급"><Text type="supporting">{idea.hist}</Text></Block>}
      <Block title="실행 판단"><Card variant="muted"><VStack gap={1.5}><Row k="기간" v={idea.effort} /><Row k="차별점" v={idea.diff} /><Row k="가장 큰 위험" v={idea.risk} /><Row k="2주 검증" v={idea.valid} /></VStack></Card></Block>
      {idea.caveat && <Banner status="info" title="확인 필요" description={idea.caveat} />}
      <HStack gap={2}>
        <Button label="관심 없음" variant="secondary" onClick={() => setSheet(true)} />
        <Button label={ctx.stars[idea.id] ? '추적 중지' : '★ 관심 · 추적 시작'} variant="primary" onClick={() => ctx.toggleStar(idea.id)} />
      </HStack>
      <BottomSheet isOpen={sheet} onOpenChange={setSheet} label="관심 없음 이유" height="hug">
        <VStack gap={3} padding={4}>
          <VStack gap={0.5}><Heading level={3}>관심 없음 — 이유를 골라 주세요</Heading><Text type="supporting">다음 날 카드 작성에 반영됩니다</Text></VStack>
          <HStack gap={1}>{['이미 있음', '너무 큼', '내 분야 아님', '근거 약함', '기타'].map(r => <Token key={r} label={r} onClick={() => { ctx.setHidden(h => ({...h, [idea.id]: r})); setSheet(false); ctx.say('관심 없음으로 표시했습니다 · 다음 날 반영'); ctx.back(); }} />)}</HStack>
        </VStack>
      </BottomSheet>
    </Page>
  );
}

/* ───────── 추적 ───────── */
function TrackList({ctx}: {ctx: Ctx}) {
  return (
    <Page>
      <HStack justify="between"><Text type="supporting">추적 3/10 · 오늘 업데이트 2건</Text><Text type="supporting">신호 강도 = 최근 7일</Text></HStack>
      {TRACKED.map(t => (
        <ClickableCard key={t.id} label={t.title} onClick={() => ctx.go('SC-14', {track: t.id})}>
          <VStack gap={2}>
            <HStack justify="between" vAlign="center"><Heading level={4}>{t.title}</Heading><Badge label={TREND_LABEL[t.trend]} variant={TREND_VARIANT[t.trend]} /></HStack>
            <HStack justify="between" vAlign="center">
              <HStack gap={1}><Token label={t.cat} size="sm" color="blue" />{t.news ? <Badge label={`오늘 새 소식 ${t.news}`} variant="warning" /> : <Badge label={t.quiet ? '조용함' : '추적 중'} variant="neutral" />}</HStack>
              <Text type="supporting">마지막 {t.last}</Text>
            </HStack>
            {t.updates[0] && <Text type="supporting" maxLines={2}>{t.updates[0].summary}</Text>}
            <Text type="supporting">{t.started}부터 추적 · 신호 강도 {t.strength}</Text>
          </VStack>
        </ClickableCard>
      ))}
      <Text type="supporting">밀어서 추적 종료 · 30일 새 소식이 없으면 "조용함"</Text>
    </Page>
  );
}

function TrackDetail({ctx}: {ctx: Ctx}) {
  const t = TRACKED.find(x => x.id === ctx.params.track)!;
  return (
    <Page>
      <VStack gap={1}>
        <Heading level={2}>{t.title}</Heading>
        <HStack gap={1}><Token label={t.cat} size="sm" color="blue" /><Badge label={t.quiet ? '조용함' : '추적 중'} variant="neutral" /><Badge label={TREND_LABEL[t.trend]} variant={TREND_VARIANT[t.trend]} /></HStack>
      </VStack>
      <Card variant="muted"><VStack gap={2}>
        <HStack justify="between"><Text type="label">신호 강도 {t.strength}</Text><Text type="supporting">{t.started}부터 추적</Text></HStack>
        <HStack gap={1}>{t.bars.map((v, i) => <ProgressBar key={i} label={`${7 - i}일 전`} isLabelHidden value={v ? Math.min(100, 30 + v * 23) : 4} />)}</HStack>
        <HStack justify="between"><Text type="supporting">7일 전</Text><Text type="supporting">오늘</Text></HStack>
      </VStack></Card>
      <Block title="달라진 점">
        {t.updates.length === 0
          ? <Banner status="info" title="조용함" description="30일 동안 새 소식이 없습니다. 조사는 계속합니다. 키워드를 바꿔 보세요." />
          : <VStack gap={2}>{t.updates.map(u => (
              <Card key={u.date}><VStack gap={1.5}>
                <HStack justify="between"><Text type="label">{u.date}</Text><Text type="supporting">근거 {u.n}건</Text></HStack>
                <Text>{u.summary}</Text>
                <HStack gap={1}>{u.changes.map(c => <Badge key={c} label={c} variant="warning" />)}</HStack>
              </VStack></Card>
            ))}</VStack>}
      </Block>
      <Block title="경쟁 제품 (추적 중 발견)">{t.comps.length ? <VStack gap={0.5}>{t.comps.map(c => <Text key={c}>· {c}</Text>)}</VStack> : <Text type="supporting">아직 없음</Text>}</Block>
      <Block title="추적 키워드">
        <HStack gap={1}>{t.keywords.map(k => <Token key={k} label={k} size="sm" onRemove={() => ctx.say(`키워드 "${k}" 삭제`)} />)}<Token label="+ 추가" size="sm" onClick={() => ctx.say('키워드 추가')} /></HStack>
        <Text type="supporting">카드 제목·태그·주제·경쟁 제품에서 자동 추출 · 출처별 전용 검색에 사용</Text>
      </Block>
      <NavRow title="추적 업데이트 프롬프트" sub="무엇을 '달라진 점'으로 볼지 직접 고치기" onClick={() => ctx.go('SC-16', {prompt: 'track'})} />
      <HStack gap={2}>
        <Button label="원래 카드 보기" variant="secondary" onClick={() => ctx.go('SC-02', {idea: t.from})} />
        <Button label="추적 종료" variant="ghost" onClick={() => ctx.say('추적을 멈췄습니다 · 기록은 남습니다')} />
      </HStack>
    </Page>
  );
}

/* ───────── 보관함 ───────── */
function Archive({ctx}: {ctx: Ctx}) {
  const [q, setQ] = useState('');
  const [onlyStar, setOnlyStar] = useState(false);
  return (
    <Page>
      <TextInput label="검색" isLabelHidden value={q} onChange={setQ} />
      <HStack gap={1} justify="between">
        <HStack gap={1}><Token label="★ 추적 중만" color={onlyStar ? 'blue' : 'default'} onClick={() => setOnlyStar(v => !v)} /><Token label="분류" /><Token label="태그" /></HStack>
        <Button label="반복 주제" variant="ghost" size="sm" onClick={() => ctx.go('SC-04')} />
      </HStack>
      {['11월 19일', '11월 18일'].map(d => {
        const list = IDEAS.filter(i => i.date === d && (!onlyStar || ctx.stars[i.id]) && (!q || i.title.includes(q) || i.one.includes(q)));
        return list.length ? <VStack gap={2} key={d}><Text type="label">{d}</Text>{list.map((i, n) => <IdeaCard key={i.id} idea={i} n={n + 1} ctx={ctx} from="archive" />)}</VStack> : null;
      })}
      {q && !IDEAS.some(i => i.title.includes(q) || i.one.includes(q)) && <EmptyState title="맞는 카드가 없습니다" description={`"${q}" 검색 결과 없음`} isCompact />}
    </Page>
  );
}

function Topics() {
  return (
    <Page>
      <Banner status="info" title="나중 단계(L)" description="최근 30일 동안 여러 번 나온 주제" />
      {TOPICS.map(t => (
        <Card key={t.key}><VStack gap={2}>
          <HStack justify="between"><Text type="label">{t.label}</Text><Badge label={`${t.n}회`} variant="neutral" /></HStack>
          <Text type="supporting">{t.key} · {t.range} · 관련 카드 2</Text>
          <HStack gap={1}>{t.bars.map((v, i) => <ProgressBar key={i} label={`${i}`} isLabelHidden value={v ? 100 : 4} />)}</HStack>
        </VStack></Card>
      ))}
    </Page>
  );
}

/* ───────── 설정 ───────── */
function SettingsHome({ctx}: {ctx: Ctx}) {
  const edited = Object.keys(ctx.overrides).length;
  return (
    <Page>
      <SectionLabel>AI</SectionLabel>
      <Group>{[
        <NavRow key="a" title="AI 공급자·모델" sub="Claude 연결됨 · OpenAI 연결됨 · 예산 $0.50/일" onClick={() => ctx.go('SC-11')} />,
        <NavRow key="b" title="프롬프트" sub={`기본값 ${PROMPTS.length - edited}개 · 수정됨 ${edited}개`} onClick={() => ctx.go('SC-15')} end={edited ? <Badge label="수정됨" variant="info" /> : undefined} />,
      ]}</Group>
      <SectionLabel>수집</SectionLabel>
      <Group>{[
        <NavRow key="a" title="출처 관리" sub="5개 중 2개 켜짐 · 오늘 125건" onClick={() => ctx.go('SC-07')} />,
        <NavRow key="b" title="신호 프로필" sub="기술 6.0 · 수요 6.0 · 경쟁 5.0" onClick={() => ctx.go('SC-08')} end={<Badge label="L" variant="neutral" />} />,
        <NavRow key="c" title="오늘 수집 항목" sub="통과 25 · 탈락 100" onClick={() => ctx.go('SC-05')} end={<Badge label="L" variant="neutral" />} />,
      ]}</Group>
      <SectionLabel>아이디어</SectionLabel>
      <Group>{[<NavRow key="a" title="내 기준·카드 구성" sub="1인 4주 · 개발자·소규모 사업자 · 하루 5개" onClick={() => ctx.go('SC-09')} />]}</Group>
      <SectionLabel>운영</SectionLabel>
      <Group>{[
        <NavRow key="a" title="실행 기록" sub="이번 달 $2.31 (추정)" onClick={() => ctx.go('SC-10')} />,
        <NavRow key="b" title="일정·메일·출처 키" sub="매일 07:00 · me@example.com" onClick={() => ctx.go('SC-11b')} />,
      ]}</Group>
      <Group>{[<NavRow key="a" title="정보" sub="버전 0.1 · 오픈소스 고지" onClick={() => ctx.go('SC-12')} />]}</Group>
    </Page>
  );
}

function AiProviders({ctx}: {ctx: Ctx}) {
  const [fallback, setFallback] = useState(true);
  const [sheet, setSheet] = useState(false);
  const prov = (name: string, last4: string, n: number) => (
    <VStack gap={1.5}>
      <HStack justify="between" vAlign="center"><Text type="label">{name}</Text><Badge label={`연결됨 ··· ${last4}`} variant="success" /></HStack>
      <Text type="supporting">모델 {n}개 · 마지막 확인 07:03</Text>
      <HStack gap={1}><Button label="교체" variant="secondary" size="sm" onClick={() => setSheet(true)} /><Button label="다시 확인" variant="secondary" size="sm" onClick={() => ctx.say('다시 확인했습니다')} /><Button label="삭제" variant="ghost" size="sm" /></HStack>
    </VStack>
  );
  return (
    <Page>
      <SectionLabel>공급자 키 — 하나 이상 필요</SectionLabel>
      <Card><VStack gap={3}>{prov('Claude (Anthropic)', 'a1b2', 9)}<Divider />{prov('OpenAI', 'c3d4', 12)}</VStack></Card>
      <SectionLabel>단계별 모델</SectionLabel>
      <Card><VStack gap={3}>
        <Row k="1차 분류·추적" v="Claude · claude-haiku-5-5" />
        <Row k="카드 작성" v="Claude · claude-opus-5-5" />
        <Divider />
        <HStack justify="between" vAlign="center">
          <VStack gap={0.5}><Text>대체 공급자 사용</Text><Text type="supporting">실패·거절 시 다른 공급자로 1회</Text></VStack>
          <Switch label="대체 공급자 사용" isLabelHidden value={fallback} onChange={v => setFallback(v)} />
        </HStack>
        <Row k="일일 예산" v="$0.50 · 이번 달 $2.31 (추정)" />
        <Row k="가격표" v="모델별 100만 토큰당 입력/출력 ›" />
      </VStack></Card>
      <NavRow title="프롬프트" sub="AI에게 주는 지시문 보기·고치기" onClick={() => ctx.go('SC-15')} />
      <BottomSheet isOpen={sheet} onOpenChange={setSheet} label="키 교체" height="hug">
        <VStack gap={3} padding={4}>
          <Heading level={3}>OpenAI 키 교체</Heading>
          <TextInput label="API 키" type="password" value="" onChange={() => {}} description="기기 보안 저장소에만 저장 · 끝 4자리만 표시 · 저장 즉시 모델 목록으로 확인" />
          <Button label="확인하고 저장" variant="primary" onClick={() => { setSheet(false); ctx.say('키 확인 완료 — 모델 12개'); }} />
        </VStack>
      </BottomSheet>
    </Page>
  );
}

function Prompts({ctx}: {ctx: Ctx}) {
  const groups = [...new Set(PROMPTS.map(p => p.group))];
  const edited = Object.keys(ctx.overrides);
  return (
    <Page>
      <Banner status="info" title="AI에게 주는 지시문" description="처음엔 기본값(AI 작성)으로 동작합니다. 고치면 다음 실행부터 내 수정본을 씁니다. 출력 형식과 인용 검증은 코드가 지키므로 앱이 깨지지 않습니다." />
      {edited.length > 0 && <HStack justify="between" vAlign="center"><Text type="supporting">수정한 프롬프트 {edited.length}개</Text><Button label="모두 기본값으로" variant="ghost" size="sm" onClick={() => { ctx.setOverrides(() => ({})); ctx.say('모든 프롬프트를 기본값으로 되돌렸습니다'); }} /></HStack>}
      {groups.map(g => (
        <VStack key={g} gap={1}>
          <SectionLabel>{g}</SectionLabel>
          <Group>{PROMPTS.filter(p => p.group === g).map(p => {
            const o = ctx.overrides[p.id];
            return <NavRow key={p.id} title={p.name} sub={`${p.stage} · ${o ? `v${o.version} · 11/18 수정` : '기본값'}`} onClick={() => ctx.go('SC-16', {prompt: p.id})}
              end={o ? <Badge label="수정됨" variant="info" /> : p.id === 'ideas' ? <Badge label="기본값 새로 바뀜" variant="warning" /> : undefined} />;
          })}</Group>
        </VStack>
      ))}
    </Page>
  );
}

function diffLines(a: string, b: string) {
  const A = a.split('\n'), B = b.split('\n');
  const setA = new Set(A), setB = new Set(B);
  return {added: B.filter(l => l.trim() && !setA.has(l)), removed: A.filter(l => l.trim() && !setB.has(l))};
}

function PromptEdit({ctx}: {ctx: Ctx}) {
  const p = PROMPTS.find(x => x.id === ctx.params.prompt)!;
  const saved = ctx.overrides[p.id];
  const [text, setText] = useState(saved?.text ?? p.text);
  const [panel, setPanel] = useState<'none' | 'diff' | 'history' | 'test'>('none');
  const [testing, setTesting] = useState(false);
  const dirty = text !== (saved?.text ?? p.text);
  const diff = useMemo(() => diffLines(p.text, text), [p.text, text]);
  const tooLong = text.length > 20000;
  const save = () => {
    if (!text.trim() || tooLong) { ctx.say('저장할 수 없습니다 — 빈 내용이거나 2만 자 초과'); return; }
    ctx.setOverrides(o => ({...o, [p.id]: {text, version: (o[p.id]?.version ?? 0) + 1, history: [...(o[p.id]?.history ?? []), {version: (o[p.id]?.version ?? 0) + 1, text, at: '11/19'}]}}));
    ctx.say('저장했습니다 · 다음 실행부터 적용');
  };
  const reset = () => { ctx.setOverrides(o => { const n = {...o}; delete n[p.id]; return n; }); setText(p.text); ctx.say('기본값으로 되돌렸습니다'); };
  const runTest = () => { setPanel('test'); setTesting(true); window.setTimeout(() => setTesting(false), 1200); };
  return (
    <Page>
      <VStack gap={1}>
        <Heading level={3}>{p.name}</Heading>
        <HStack gap={1}>
          <Badge label={p.stage} variant="neutral" />
          <Badge label={saved ? `수정됨 · v${saved.version}` : '기본값 사용 중'} variant={saved ? 'info' : 'neutral'} />
          {dirty && <Badge label="저장 안 됨" variant="warning" />}
        </HStack>
        <Text type="supporting">{p.model}</Text>
      </VStack>
      {p.id === 'ideas' && !saved && <Banner status="warning" title="기본값이 새로 바뀌었습니다" description="앱 업데이트로 기본 프롬프트가 바뀌었습니다. 수정본이 없으니 자동으로 새 기본값을 씁니다." />}
      <Banner status="info" title="출력 형식은 코드가 지킵니다" description="여기서는 판단 기준과 글쓰기 규칙을 바꿉니다. JSON 형식·인용 검증은 바뀌지 않습니다." />
      <Card variant="muted"><VStack gap={2}>
        <VStack gap={0.5}><Text type="supporting">받는 데이터</Text><Text>{p.inputs}</Text></VStack>
        <VStack gap={0.5}><Text type="supporting">만드는 것</Text><Text>{p.outputs}</Text></VStack>
      </VStack></Card>
      <div className="prompt-editor">
        <TextArea label="프롬프트" isLabelHidden value={text} onChange={v => setText(v)} />
      </div>
      <HStack justify="between"><Text type="supporting">{text.length.toLocaleString()} / 20,000자</Text><Text type="supporting">맨 위 {'<!-- -->'} 설명은 모델에 보내지 않음</Text></HStack>
      {tooLong && <Banner status="error" title="2만 자를 넘었습니다" description="줄여야 저장할 수 있습니다" />}
      <HStack gap={1}>
        <Button label="기본값과 비교" variant={panel === 'diff' ? 'primary' : 'secondary'} size="sm" onClick={() => setPanel(panel === 'diff' ? 'none' : 'diff')} />
        <Button label="버전 기록" variant={panel === 'history' ? 'primary' : 'secondary'} size="sm" onClick={() => setPanel(panel === 'history' ? 'none' : 'history')} />
        <Button label="시험 실행" variant={panel === 'test' ? 'primary' : 'secondary'} size="sm" onClick={runTest} />
      </HStack>
      {panel === 'diff' && (
        <Card><VStack gap={1}>
          <Text type="label">기본값 대비 바뀐 줄 · 추가 {diff.added.length} · 삭제 {diff.removed.length}</Text>
          {diff.added.length + diff.removed.length === 0 && <Text type="supporting">기본값과 같습니다</Text>}
          {diff.removed.slice(0, 6).map((l, i) => <div key={'r' + i} className="diff-del">− {l}</div>)}
          {diff.added.slice(0, 6).map((l, i) => <div key={'a' + i} className="diff-add">+ {l}</div>)}
        </VStack></Card>
      )}
      {panel === 'history' && (
        <Card><VStack gap={2}>
          <Text type="label">버전 기록 (최근 10개)</Text>
          <Row k="기본값" v={<Button label="이 버전으로" variant="ghost" size="sm" onClick={() => setText(p.text)} />} />
          {(saved?.history ?? []).slice().reverse().map(h => <Row key={h.version} k={`v${h.version} · ${h.at}`} v={<Button label="복원" variant="ghost" size="sm" onClick={() => ctx.say(`v${h.version}을 편집기에 불러왔습니다`)} />} />)}
          {!saved && <Text type="supporting">아직 수정한 적이 없습니다</Text>}
        </VStack></Card>
      )}
      {panel === 'test' && (
        <Card><VStack gap={2}>
          <Text type="label">시험 실행 · 최근 실행 자료 5건 · 저장 전 내용으로</Text>
          {testing ? <ProgressBar label="실행 중" value={60} /> : <>
            <Text type="supporting">결과(요약) — 5건 중 통과 3 · 무관 1 · 임계값 미달 1</Text>
            <Card variant="muted"><Text>"AI agents ask better questions…" → 수요 신호 8.1 · 이유: 감사 실무자의 구체적 불편과 동조 댓글</Text></Card>
            <Text type="supporting">토큰 6.2k/1.1k · 비용 $0.004 (일일 예산에 포함) · 실행 기록에 "시험"으로 남음</Text>
          </>}
        </VStack></Card>
      )}
      <HStack gap={2}>
        <Button label="기본값으로 되돌리기" variant="ghost" isDisabled={!saved && !dirty} onClick={reset} />
        <Button label="저장" variant="primary" isDisabled={!dirty} onClick={save} />
      </HStack>
    </Page>
  );
}

function Sources({ctx}: {ctx: Ctx}) {
  return (
    <Page>
      <SectionLabel>최근 30일 통계</SectionLabel>
      <Group>{SOURCES.map(s => (
        <NavRow key={s.id} title={s.name} sub={`${s.on ? '켜짐' : '꺼짐'} · ${s.stat}`} onClick={() => ctx.go('SC-07a', {source: s.id})} end={<Badge label={s.on ? 'ON' : 'OFF'} variant={s.on ? 'success' : 'neutral'} />} />
      ))}</Group>
      <Button label="+ RSS 피드 추가 (L)" variant="secondary" isDisabled />
    </Page>
  );
}

function SourceDetail({ctx}: {ctx: Ctx}) {
  const s = SOURCES.find(x => x.id === ctx.params.source)!;
  const [on, setOn] = useState(s.on);
  const [tested, setTested] = useState(false);
  return (
    <Page>
      <HStack justify="between" vAlign="center"><Heading level={3}>{s.name}</Heading><Switch label="켜기" isLabelHidden value={on} onChange={v => setOn(v)} /></HStack>
      <Block title="검색어"><HStack gap={1}>{s.queries.map(q => <Token key={q} label={q} size="sm" onRemove={() => {}} />)}<Token label="+ 추가" size="sm" /></HStack></Block>
      <HStack gap={2}>
        <TextInput label="최소 점수" value={String(s.min)} onChange={() => {}} />
        <TextInput label="최대 건수" value={String(s.max)} onChange={() => {}} />
      </HStack>
      <Row k="기간" v={s.window} />
      <Block title="프로필 배정"><Chips items={['기술', '수요', '경쟁']} selected={s.id === 'hn' ? ['기술', '수요', '경쟁'] : ['기술']} /></Block>
      <Button label="연결 테스트" variant="secondary" onClick={() => setTested(true)} />
      {tested && <Banner status="success" title="10건 받음 · 1.2초" description="AI agents ask better questions… / Energy-Based Reasoners… / Claude was trained on protein sequences…" />}
      <Button label="기본값으로 되돌리기" variant="ghost" />
    </Page>
  );
}

function Profiles({ctx}: {ctx: Ctx}) {
  const items = [['tech-signal', '기술 신호', '1인 개발자가 지금 재료로 쓸 수 있나?', '6.0', '8'], ['demand-signal', '수요 신호', 'AI로 풀 수 있는 반복되는 불편이 있나?', '6.0', '8'], ['market-signal', '경쟁 제품', '수요를 증명하면서 빈틈이 있나?', '5.0', '4']];
  return (
    <Page>
      {items.map(([id, n, q, t, m]) => (
        <Card key={id}><VStack gap={2}>
          <HStack justify="between"><Text type="label">{n}</Text>{ctx.overrides[`profile.${id}.analysis`] && <Badge label="평가표 수정됨" variant="info" />}</HStack>
          <Text type="supporting">{q}</Text>
          <HStack gap={2}><TextInput label="임계값" value={t} onChange={() => {}} /><TextInput label="후보 상한" value={m} onChange={() => {}} /></HStack>
          <HStack gap={1}>
            <Button label="포함 기준 편집" variant="secondary" size="sm" onClick={() => ctx.go('SC-16', {prompt: `profile.${id}.match`})} />
            <Button label="평가표 편집" variant="secondary" size="sm" onClick={() => ctx.go('SC-16', {prompt: `profile.${id}.analysis`})} />
          </HStack>
        </VStack></Card>
      ))}
    </Page>
  );
}

function Inbox() {
  const [tab, setTab] = useState('drop');
  const rows = tab === 'pass' ? INBOX.pass : INBOX.drop;
  return (
    <Page>
      <SegmentedControl label="통과·탈락" value={tab} onChange={setTab} layout="fill">
        <SegmentedControlItem value="pass" label="통과 25" /><SegmentedControlItem value="drop" label="탈락 100" />
      </SegmentedControl>
      <Group>{rows.map(([src, t, sc, r]) => (
        <HStack key={t} justify="between" vAlign="center" padding={3} gap={2}>
          <VStack gap={0.5}><Text maxLines={1}>{t}</Text><Text type="supporting">{src} · {sc}</Text></VStack>
          <Badge label={r} variant={tab === 'pass' ? 'success' : 'warning'} />
        </HStack>
      ))}</Group>
      {tab === 'drop' && <Text type="supporting">탈락 항목을 길게 눌러 "다음엔 포함" → 출처 기준 조정 참고</Text>}
    </Page>
  );
}

function Criteria({ctx}: {ctx: Ctx}) {
  return (
    <Page>
      <SectionLabel>내 기준</SectionLabel>
      <CriteriaForm />
      <NavRow title="글로 직접 편집" sub="양식 대신 내 기준 전체를 문장으로 고치기" onClick={() => ctx.go('SC-16', {prompt: 'criteria'})} />
      <SectionLabel>카드 구성</SectionLabel>
      <HStack gap={2}><TextInput label="하루 카드 수" value="5" onChange={() => {}} /><TextInput label="분류별 최대" value="2" onChange={() => {}} /></HStack>
      <HStack gap={2}><TextInput label="같은 대표 태그 최대" value="2" onChange={() => {}} /><TextInput label="반영할 피드백" value="10" onChange={() => {}} /></HStack>
      <Text type="supporting">분류 목록(고정 10개): {CATEGORIES.join(' · ')}</Text>
      <Button label="저장" variant="primary" onClick={() => ctx.say('저장했습니다 — 다음 실행부터 반영')} />
    </Page>
  );
}

function Runs({ctx}: {ctx: Ctx}) {
  return (
    <Page>
      <Card variant="muted"><VStack gap={2}><HStack justify="between"><Text type="label">이번 달 $2.31</Text><Text type="supporting">추정 · 예산 $15.00</Text></HStack><ProgressBar label="이번 달 비용" isLabelHidden value={15} /></VStack></Card>
      <Group>{RUNS.map(r => (
        <NavRow key={r.id} title={r.when} sub={`${r.result} · ${r.funnel}`} onClick={() => ctx.go('SC-10a')} end={<Text type="supporting">{r.cost}</Text>} />
      ))}</Group>
      <Text type="supporting">같은 날 다시 실행하면 이전 결과는 "교체됨"으로 남습니다</Text>
    </Page>
  );
}

function RunDetail() {
  const stages = [['수집', '—', '412', '3.1s'], ['거르기', '412', '150', '0.1s'], ['중복·본 글', '150', '80', '0.0s'], ['분류', '80', '52', '14s'], ['추리기', '52', '18', '0.0s'], ['보강', '18', '18', '6s'], ['작성', '18', '5', '41s'], ['추적', '4', '2', '3s'], ['검증', '5', '5', '0.0s']];
  return (
    <Page>
      <Banner status="warning" title="Reddit 시간 초과(45초)" description="나머지 출처로 계속" />
      <Block title="단계 (입력 → 출력 · 시간)"><Card padding={2}><VStack gap={1}>{stages.map(([s, i, o, t]) => <Row key={s} k={s} v={`${i} → ${o} · ${t}`} />)}</VStack></Card></Block>
      <Block title="출처"><Card padding={2}><VStack gap={1}><Row k="HN" v="10 · 성공" /><Row k="GitHub" v="115 · 성공 (스팸 99)" /><Row k="Reddit" v="0 · 시간 초과" /></VStack></Card></Block>
      <Block title="탈락 이유"><HStack gap={1}>{[['스팸 묶음', 99], ['무관', 22], ['임계값 미달', 30], ['주제 합침', 4], ['상한 초과', 8]].map(([k, v]) => <Badge key={k} label={`${k} ${v}`} variant="neutral" />)}</HStack></Block>
      <Block title="AI 호출 (공급자·모델·프롬프트 버전)"><Card padding={2}><VStack gap={1}>
        <Row k="분류 ×4" v="Claude haiku · classify 기본값 · $0.007" />
        <Row k="작성" v="Claude opus · ideas 기본값 · $0.156" />
        <Row k="추적 ×2" v="Claude haiku · track 기본값 · $0.004" />
      </VStack></Card></Block>
      <Text type="supporting">인용 검증: 근거 1개 제거(수집 목록에 없는 URL) · 비용은 가격표 기준 추정</Text>
    </Page>
  );
}

function Schedule({ctx}: {ctx: Ctx}) {
  const [wifi, setWifi] = useState(true);
  const [charge, setCharge] = useState(false);
  const [mail, setMail] = useState(true);
  return (
    <Page>
      <SectionLabel>실행 일정</SectionLabel>
      <Card><VStack gap={3}>
        <Row k="매일 실행 시각" v="07:00" />
        <HStack justify="between" vAlign="center"><Text>Wi-Fi에서만</Text><Switch label="Wi-Fi에서만" isLabelHidden value={wifi} onChange={v => setWifi(v)} /></HStack>
        <HStack justify="between" vAlign="center"><Text>충전 중에만</Text><Switch label="충전 중에만" isLabelHidden value={charge} onChange={v => setCharge(v)} /></HStack>
        <Text type="supporting">안드로이드 절전 때문에 몇십 분 늦어질 수 있습니다</Text>
      </VStack></Card>
      <SectionLabel>메일</SectionLabel>
      <Card><VStack gap={3}>
        <TextInput label="받는 주소" value="me@example.com" onChange={() => {}} />
        <Row k="보내는 계정" v="sender@gmail.com · 앱 비밀번호 저장됨" />
        <HStack justify="between" vAlign="center"><Button label="테스트 발송" variant="secondary" size="sm" onClick={() => ctx.say('테스트 메일을 보냈습니다')} /><HStack gap={2} vAlign="center"><Text>메일 받기</Text><Switch label="메일 받기" isLabelHidden value={mail} onChange={v => setMail(v)} /></HStack></HStack>
      </VStack></Card>
      <SectionLabel>출처 키</SectionLabel>
      <Group>{[
        <NavRow key="a" title="GitHub 토큰" sub="저장됨 ··· 9f1c" onClick={() => ctx.say('토큰 교체')} />,
        <NavRow key="b" title="Reddit" sub="없음 · 3단계" onClick={() => {}} />,
        <NavRow key="c" title="Product Hunt" sub="없음 · 3단계" onClick={() => {}} />,
      ]}</Group>
      <SectionLabel>백업 (L)</SectionLabel>
      <HStack gap={2}><Button label="내보내기" variant="secondary" size="sm" isDisabled /><Button label="가져오기" variant="secondary" size="sm" isDisabled /></HStack>
    </Page>
  );
}

function About() {
  return (
    <Page>
      <Card><VStack gap={1}><Text type="label">AI 아이디어 수집기</Text><Text type="supporting">버전 0.1.0</Text></VStack></Card>
      <Card><VStack gap={1}><Text type="label">오픈소스 고지</Text><Text type="supporting">Horizon (MIT) — 설계 참고 · OkHttp (Apache-2.0) · kotlinx.serialization (Apache-2.0) · Anthropic Java SDK · OpenAI Java SDK</Text></VStack></Card>
    </Page>
  );
}

/* ───────── 앱 밖 ───────── */
function MailView({ctx}: {ctx: Ctx}) {
  const cards = IDEAS.filter(i => i.date === '11월 19일');
  return (
    <div className="mail2">
      <Card><VStack gap={3}>
        <VStack gap={0.5}><Text type="supporting">보낸 사람 sender@gmail.com · 받는 사람 me@example.com</Text><Heading level={3}>[AI 아이디어] 11월 19일 — 카드 3개 (수집 412 → 후보 18)</Heading></VStack>
        <Banner status="warning" title="Reddit 수집 실패" description="나머지 출처로 만들었습니다" />
        <Block title="목차">{cards.map((c, i) => <HStack key={c.id} gap={1}><Text>{i + 1}. {c.title}</Text><Token label={c.cat} size="sm" color="blue" />{c.badges.map(b => <Badge key={b} label={b} variant="warning" />)}</HStack>)}</Block>
        {cards.slice(0, 2).map((c, i) => (
          <Card key={c.id} variant="muted"><VStack gap={1.5}>
            <Text type="label">{i + 1}. {c.title}</Text><Text type="supporting">{c.one}</Text>
            <Text><b>오늘 고른 이유</b> {c.why}</Text><Text><b>가장 큰 위험</b> {c.risk}</Text>
            <Button label="앱에서 보기" variant="ghost" size="sm" onClick={() => ctx.jump('SC-02', {idea: c.id})} />
          </VStack></Card>
        ))}
        <Block title="추적 업데이트">{TRACKED.filter(t => t.news).map(t => (
          <HStack key={t.id} justify="between" vAlign="center"><VStack gap={0.5}><Text>{t.title} · {TREND_LABEL[t.trend]}</Text><Text type="supporting">{t.updates[0].summary}</Text></VStack><Button label="보기" variant="ghost" size="sm" onClick={() => ctx.jump('SC-14', {track: t.id})} /></HStack>
        ))}</Block>
        <Divider /><Text type="supporting">오늘 비용 $0.14 · 이번 달 $2.31 (추정) · 실행 1분 05초 · HTML + 일반 텍스트</Text>
      </VStack></Card>
    </div>
  );
}

function NotiView({ctx}: {ctx: Ctx}) {
  const n = [['SC-01', 'AI 아이디어', '오늘의 카드 3개가 준비됐습니다 · 수집 412 → 카드 3', {}], ['SC-14', '추적 업데이트', '"로컬 LLM 회의록 앱"에 새 경쟁 제품·수요 글 3건 외 1개', {track: 't1'}], ['SC-10a', '실행 일부 실패', 'Reddit 시간 초과 · 나머지 출처로 실행', {}], ['SC-11', '키 확인 필요', 'Claude 키 인증 실패 — 키를 확인해 주세요', {}]] as const;
  return (
    <div className="noti2"><VStack gap={2}>
      <Text type="supporting">알림 (누르면 해당 화면으로)</Text>
      {n.map(([to, t, b, p]) => <ClickableCard key={t} label={t} onClick={() => ctx.jump(to, p as Record<string, string>)}><VStack gap={0.5}><Text type="label">{t}</Text><Text type="supporting">{b}</Text></VStack></ClickableCard>)}
    </VStack></div>
  );
}
