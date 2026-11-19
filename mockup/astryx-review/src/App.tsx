import {useState} from 'react';
import {Badge} from '@astryxdesign/core/Badge';
import {Banner} from '@astryxdesign/core/Banner';
import {BottomSheet} from '@astryxdesign/core/BottomSheet';
import {Button} from '@astryxdesign/core/Button';
import {Card} from '@astryxdesign/core/Card';
import {ClickableCard} from '@astryxdesign/core/ClickableCard';
import {Divider} from '@astryxdesign/core/Divider';
import {Heading} from '@astryxdesign/core/Heading';
import {HStack} from '@astryxdesign/core/HStack';
import {IconButton} from '@astryxdesign/core/IconButton';
import {Text} from '@astryxdesign/core/Text';
import {Token} from '@astryxdesign/core/Token';
import {VStack} from '@astryxdesign/core/VStack';
import {Switch} from '@astryxdesign/core/Switch';
import {ProgressBar} from '@astryxdesign/core/ProgressBar';

/* Astryx 검토용 시안: 와이어프레임의 SC-01 / SC-02 / SC-06을 인스타그램풍 "feed" 테마로. 데이터는 가상. */

type Card = {
  id: string; title: string; one: string; cat: string; badges: string[]; score: number;
  why: string; problem: string; tech: string; demand?: string; quote?: [string, string]; risk: string; valid: string; effort: string;
};

const CARDS: Card[] = [
  {id: 'c1', title: '로컬 LLM으로 회의록을 요약하는 사내 설치형 앱', one: '녹음 파일을 PC 안에서만 처리해 요약·할 일을 뽑아 주는 데스크톱 앱', cat: '업무 자동화', badges: ['여러 곳에서 화제'], score: 8.4,
    why: '경량 모델이 노트북에서도 돌기 시작했고, 회의록 SaaS 보안 불만 글이 두 곳에서 올라왔다.', problem: '보안 규정 때문에 클라우드 회의록을 못 쓰는 20~100명 회사 팀장이 매주 2~3시간을 회의 정리에 쓴다.',
    tech: '경량 음성 인식 + 4bit 양자화 모델 · GitHub ★2,464 · MIT', demand: '"보안 때문에 회의록 툴을 못 써요" · Reddit 추천 180', quote: ['dev_kim', '우리 회사도 외부 업로드 금지라 결국 손으로 정리함'],
    risk: '노트북 사양별 처리 속도 편차', valid: '지인 회사 3곳에 2주 시범 설치, 주간 사용 횟수 측정', effort: '1인 3주'},
  {id: 'c2', title: '소상공인용 고객 문의 1차 응대 봇', one: '메신저 DM 문의를 FAQ와 예약표로 먼저 답해 주는 봇', cat: '커머스·마케팅', badges: ['급상승'], score: 7.6,
    why: '에이전트 프레임워크가 하루 만에 HN 300점, 같은 날 "DM 답장에 밤샌다"는 글.', problem: '1~3인 매장 사장이 영업 중 DM에 바로 답하지 못해 예약을 놓친다.',
    tech: '오픈소스 에이전트 프레임워크 v1 · HN 300점 · Apache-2.0', demand: '"영업 끝나고 DM 답장만 1시간" · Reddit 추천 95',
    risk: '메신저 API 정책 제한', valid: '동네 매장 5곳 수동 응대 대행으로 2주 수요 확인', effort: '1인 4주'},
  {id: 'c3', title: 'AI로 옮긴 코드가 원본 수정사항을 놓치는지 알려주는 감시 도구', one: '원본의 버그·보안 수정을 못 따라간 번역 포크를 찾아 알려 줌', cat: '개발 도구', badges: [], score: 7.2,
    why: 'AI로 옮긴 대형 클론이 3주 만에 원본 수정 200여 건을 놓쳤다는 글이 화제.', problem: 'AI로 옮긴 라이브러리를 쓰는 팀이 원본 보안 수정 반영 여부를 모른다.',
    tech: '코드 대응 관계 추정 도구 · GitHub ★880 · MIT', risk: '번역 코드 대응 정확도', valid: '공개 번역 포크 3개 수동 비교 리포트', effort: '1인 3주'},
];

type Screen = 'today' | 'detail' | 'settings';

export default function App() {
  const [screen, setScreen] = useState<Screen>('today');
  const [cardId, setCardId] = useState('c1');
  const [stars, setStars] = useState<Record<string, boolean>>({c2: true});
  const [sheet, setSheet] = useState(false);
  const [running, setRunning] = useState(false);
  const card = CARDS.find(c => c.id === cardId)!;
  const toggleStar = (id: string) => setStars(s => ({...s, [id]: !s[id]}));

  return (
    <div style={{display: 'flex', justifyContent: 'center', padding: 24}}>
      <div style={{width: 390, height: 800, border: '10px solid #111', borderRadius: 36, overflow: 'hidden', display: 'flex', flexDirection: 'column', background: 'var(--color-background-body)'}}>
        {/* 상단 바 */}
        <HStack paddingInline={4} paddingBlock={3} vAlign="center" justify="between">
          {screen === 'today' ? <Heading level={2}>오늘의 아이디어</Heading>
            : <HStack gap={2} vAlign="center"><Button label="뒤로" variant="ghost" onClick={() => setScreen(screen === 'detail' ? 'today' : 'today')} /><Heading level={3}>{screen === 'detail' ? '카드 상세' : '설정'}</Heading></HStack>}
          {screen === 'today' && <Button label="지금 실행" variant="secondary" size="sm" isDisabled={running} onClick={() => setRunning(true)} />}
        </HStack>
        <Divider />

        {/* 본문 */}
        <VStack gap={3} padding={4} isScrollable height="100%">
          {screen === 'today' && <>
            <Card variant="muted">
              <VStack gap={1}>
                <HStack justify="between"><Text type="label">11월 19일 (수)</Text><Text type="supporting">07:03 · $0.14</Text></HStack>
                <Text type="supporting">수집 412 → 후보 18 → 카드 5</Text>
              </VStack>
            </Card>
            {running && <Card><VStack gap={2}><Text type="label">실행 중 · 분류 중…</Text><ProgressBar label="진행" value={55} /></VStack></Card>}
            <Banner status="warning" title="Reddit 수집 실패" description="나머지 출처로 만들었습니다" />
            {CARDS.map((c, i) => (
              <ClickableCard key={c.id} label={c.title} onClick={() => { setCardId(c.id); setScreen('detail'); }}>
                <VStack gap={2}>
                  <HStack justify="between" vAlign="start" gap={2}>
                    <Heading level={4}>{i + 1}. {c.title}</Heading>
                    <IconButton label={stars[c.id] ? '관심 해제' : '관심'} icon={<span>{stars[c.id] ? '★' : '☆'}</span>} variant="ghost" size="sm" onClick={e => { e.stopPropagation(); toggleStar(c.id); }} />
                  </HStack>
                  <Text type="supporting">{c.one}</Text>
                  <HStack gap={1}>
                    <Token label={c.cat} size="sm" color="blue" />
                    {c.badges.map(b => <Badge key={b} label={b} variant="warning" />)}
                    <Badge label={`AI ${c.score}`} variant="neutral" />
                  </HStack>
                </VStack>
              </ClickableCard>
            ))}
          </>}

          {screen === 'detail' && <>
            <Heading level={2}>{card.title}</Heading>
            <Text type="supporting">{card.one}</Text>
            <HStack gap={1}><Token label={card.cat} size="sm" color="blue" />{card.badges.map(b => <Badge key={b} label={b} variant="warning" />)}</HStack>
            <Divider />
            <Section title="오늘 고른 이유"><Text>{card.why}</Text></Section>
            <Section title="문제"><Text>{card.problem}</Text></Section>
            <Section title="기술 신호"><Card variant="muted"><Text>{card.tech}</Text></Card></Section>
            {card.demand && <Section title="수요 신호"><Card variant="muted"><VStack gap={1}><Text>{card.demand}</Text>{card.quote && <Text type="supporting">{card.quote[0]}: {card.quote[1]}</Text>}</VStack></Card></Section>}
            <Section title="실행 판단">
              <VStack gap={1}>
                <Text>기간 · {card.effort}</Text><Text>가장 큰 위험 · {card.risk}</Text><Text>2주 검증 · {card.valid}</Text>
              </VStack>
            </Section>
            <HStack gap={2}>
              <Button label="관심 없음" variant="secondary" onClick={() => setSheet(true)} />
              <Button label={stars[card.id] ? '★ 관심 해제' : '★ 관심'} variant="primary" onClick={() => toggleStar(card.id)} />
            </HStack>
          </>}

          {screen === 'settings' && <>
            <Text type="label">AI 공급자</Text>
            <Card><VStack gap={2}>
              <HStack justify="between"><Text>Claude (Anthropic)</Text><Badge label="연결됨 ··· a1b2" variant="success" /></HStack>
              <HStack justify="between"><Text>OpenAI</Text><Badge label="연결됨 ··· c3d4" variant="success" /></HStack>
            </VStack></Card>
            <Text type="label">운영</Text>
            <Card><VStack gap={3}>
              <HStack justify="between"><Text>Wi-Fi에서만 실행</Text><Switch label="Wi-Fi에서만 실행" isLabelHidden value={true} onChange={() => {}} /></HStack>
              <HStack justify="between"><Text>메일 받기</Text><Switch label="메일 받기" isLabelHidden value={true} onChange={() => {}} /></HStack>
            </VStack></Card>
          </>}
        </VStack>

        {/* 하단 탭 */}
        <Divider />
        <HStack justify="around" paddingBlock={2}>
          <Button label="오늘" variant={screen === 'settings' ? 'ghost' : 'primary'} size="sm" onClick={() => setScreen('today')} />
          <Button label="보관함" variant="ghost" size="sm" />
          <Button label="설정" variant={screen === 'settings' ? 'primary' : 'ghost'} size="sm" onClick={() => setScreen('settings')} />
        </HStack>

        <BottomSheet isOpen={sheet} onOpenChange={setSheet} label="관심 없음 이유">
          <VStack gap={2} padding={4}>
            <Heading level={3}>관심 없음 — 이유를 골라 주세요</Heading>
            <HStack gap={1}>
              {['이미 있음', '너무 큼', '내 분야 아님', '근거 약함', '기타'].map(r => <Token key={r} label={r} onClick={() => setSheet(false)} />)}
            </HStack>
          </VStack>
        </BottomSheet>
      </div>
    </div>
  );
}

function Section({title, children}: {title: string; children: React.ReactNode}) {
  return <VStack gap={1}><Text type="label">{title}</Text>{children}</VStack>;
}
