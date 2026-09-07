import sharedPrompt from "../../contracts/system-prompt.txt?raw";
export const VERSION = "0.2.0";
export type Status =
  | "GENERAL_KNOWLEDGE"
  | "VERIFIED"
  | "EXPERIENCE"
  | "HYPOTHESIS"
  | "UNSUPPORTED ASSERTION"
  | "CONTRADICTED"
  | "UNVERIFIABLE"
  | "PENDING"
  | "QUESTION"
  | "VALUE JUDGMENT"
  | "PROPOSAL";
export type Basis = "CALCULATION" | "MODEL_KNOWLEDGE" | "DOCUMENT" | "WEB";
export type ListeningState = "IDLE" | "LISTENING" | "PAUSED" | "ENDED";
export interface Attempt {
  route: "CALCULATION" | "LOCAL" | "OPENROUTER";
  reason: string;
  startedAt: number;
  durationMs: number;
  outcome: string;
  inputTokenUpperBound?: number;
  retrievalMs?: number;
  queueMs?: number;
}
export interface AudioSpan {
  uri: string;
  file: string;
  startMs: number;
  endMs: number;
}
export interface SpeakerEdit {
  at: number;
  kind: string;
  id: string;
  from: string;
  to: string;
}
export interface Speaker {
  id: string;
  name: string;
  embedding?: number[];
  baseline?: Policy;
}
export interface Evidence {
  id: string;
  text: string;
  title: string;
  source: string;
  version: string;
  page?: number;
  documentHash?: string;
  basis?: Basis;
  checkedAt: number;
}
export interface Revision {
  basis?: Basis;
  evidenceIds?: string[];
  status: Status;
  reason: string;
  at: number;
}
export interface Claim {
  id: string;
  utteranceId: string;
  text: string;
  speakerId: string | null;
  speakerCertain: boolean;
  status: Status;
  reason: string;
  evidenceIds: string[];
  endedAt: number;
  checkedAt: number;
  revisions: Revision[];
  spoken: boolean;
  basis?: Basis;
  attempts?: Attempt[];
  audioSpans?: AudioSpan[];
}
export interface Policy {
  mode: "NORMAL" | "EVIDENCE REQUIRED";
  unsupported: number;
  calibrated: number;
}
export interface Meeting {
  schemaVersion: 2;
  listeningState?: ListeningState;
  speakerHistory?: SpeakerEdit[];
  appVersion: string;
  speakers: Speaker[];
  claims: Claim[];
  evidence: Evidence[];
}
export const emptyMeeting = (): Meeting => ({
  schemaVersion: 2,
  listeningState: "IDLE",
  speakerHistory: [],
  appVersion: VERSION,
  speakers: [],
  claims: [],
  evidence: [],
});
export const normal = (): Policy => ({
  mode: "NORMAL",
  unsupported: 0,
  calibrated: 0,
});
export function transition(state: Policy, status: Status): Policy {
  if (status === "UNSUPPORTED ASSERTION") {
    const unsupported = state.unsupported + 1;
    return {
      mode: unsupported >= 3 ? "EVIDENCE REQUIRED" : state.mode,
      unsupported,
      calibrated: 0,
    };
  }
  if (["VERIFIED", "EXPERIENCE", "HYPOTHESIS"].includes(status)) {
    const calibrated = state.calibrated + 1;
    return {
      mode: calibrated >= 3 ? "NORMAL" : state.mode,
      unsupported: 0,
      calibrated,
    };
  }
  if (status === "CONTRADICTED")
    return { ...state, unsupported: 0, calibrated: 0 };
  return { ...state };
}
export function policy(meeting: Meeting, speakerId: string): Policy {
  const seen = new Set<string>();
  let state = {
    ...(meeting.speakers.find((s) => s.id === speakerId)?.baseline ?? normal()),
  };
  for (const c of [...meeting.claims].sort((a, b) => a.endedAt - b.endedAt)) {
    if (c.speakerId !== speakerId || !c.speakerCertain || seen.has(c.id))
      continue;
    seen.add(c.id);
    if (c.basis !== "MODEL_KNOWLEDGE") state = transition(state, c.status);
  }
  return state;
}
export function revise(
  c: Claim,
  status: Status,
  reason: string,
  evidenceIds: string[] = c.evidenceIds,
): Claim {
  return {
    ...c,
    status,
    basis: ["EXPERIENCE", "HYPOTHESIS"].includes(status) ? undefined : c.basis,
    reason,
    evidenceIds,
    checkedAt: Date.now(),
    revisions: [
      ...c.revisions,
      {
        status: c.status,
        reason: c.reason,
        at: c.checkedAt,
        basis: c.basis,
        evidenceIds: c.evidenceIds,
      },
    ],
  };
}
export function intervention(c: Claim, now = Date.now()): string | null {
  if (c.spoken || now - c.endedAt > 30000 || !c.speakerCertain) return null;
  if (c.status === "UNSUPPORTED ASSERTION")
    return "잠시 사실관계를 확인하겠습니다. 현재 자료에서 근거를 확인하지 못했습니다. 출처나 경험, 추정 여부를 알려주세요.";
  if (c.status === "CONTRADICTED" && c.basis === "CALCULATION")
    return "잠시 사실관계를 확인하겠습니다. 계산 결과가 다릅니다. 화면의 계산식을 확인해 주세요.";
  if (c.status === "CONTRADICTED")
    return "잠시 사실관계를 확인하겠습니다. 현재 자료와 충돌하는 내용입니다. 화면의 출처와 적용 버전을 확인해 주세요.";
  return null;
}
export function paragraphs(
  text: string,
  title: string,
  source = title,
  version = "사용자 제공",
  page?: number,
): Evidence[] {
  return text
    .split(/\n+|(?<=[.!?。])\s+/)
    .flatMap((t) => t.match(/[\s\S]{1,300}/g) ?? [])
    .map((t) => t.trim())
    .filter(Boolean)
    .map((text) => ({
      id: crypto.randomUUID(),
      text,
      title,
      source,
      version,
      page,
      basis: "DOCUMENT" as const,
      checkedAt: Date.now(),
    }));
}
const searchIndex = new Map<string, { text: string; lower: string }>();
export function clearEvidenceIndex() {
  searchIndex.clear();
}
export function indexEvidence(docs: Evidence[]) {
  for (const d of docs) {
    if (searchIndex.get(d.id)?.text !== d.text)
      searchIndex.set(d.id, { text: d.text, lower: d.text.toLowerCase() });
  }
  if (searchIndex.size > 20000) searchIndex.clear();
}
export function retrieve(text: string, docs: Evidence[]): Evidence[] {
  indexEvidence(docs);
  const tokens = (text.toLowerCase().match(/[가-힣a-z0-9]{2,}/g) ?? []).map(
    (t) =>
      t.length > 2
        ? t.replace(/(은|는|이|가|을|를|에서|으로)$/, " ").trim()
        : t,
  );
  return docs
    .map((d) => ({
      d,
      score: tokens.reduce(
        (s, t) =>
          s +
          ((searchIndex.get(d.id)?.lower ?? d.text.toLowerCase()).includes(t)
            ? 1
            : 0),
        0,
      ),
    }))
    .filter((x) => x.score > 0)
    .sort((a, b) => b.score - a.score)
    .slice(0, 5)
    .map((x) => x.d);
}
export const systemPrompt = sharedPrompt.trim();

const statuses: Status[] = [
  "GENERAL_KNOWLEDGE",
  "VERIFIED",
  "EXPERIENCE",
  "HYPOTHESIS",
  "UNSUPPORTED ASSERTION",
  "CONTRADICTED",
  "UNVERIFIABLE",
  "QUESTION",
  "VALUE JUDGMENT",
  "PROPOSAL",
];
export function parseVerdicts(
  raw: string,
  known: Evidence[],
  hasScope: boolean,
): {
  text: string;
  status: Status;
  reason: string;
  evidenceIds: string[];
  basis?: Basis;
}[] {
  const cleaned = raw
    .replace(/<think>[\s\S]*?<\/think>/g, "")
    .replace(/^```(?:json)?\s*|\s*```$/g, "")
    .trim();
  const obj = JSON.parse(cleaned);
  if (!Array.isArray(obj.claims) || obj.claims.length > 20)
    throw Error("판정 형식이 올바르지 않습니다.");
  return obj.claims.map((v: Record<string, unknown>) => {
    if (
      typeof v.text !== "string" ||
      typeof v.reason !== "string" ||
      !statuses.includes(v.status as Status)
    )
      throw Error("잘못된 판정 응답");
    const ids = Array.isArray(v.evidenceIds)
      ? v.evidenceIds.filter(
          (id): id is string =>
            typeof id === "string" && known.some((e) => e.id === id),
        )
      : [];
    let status = v.status as Status;
    let reason = v.reason.slice(0, 1000);
    if (
      (["VERIFIED", "CONTRADICTED"].includes(status) && ids.length === 0) ||
      (status === "UNSUPPORTED ASSERTION" && !hasScope)
    ) {
      status = "UNVERIFIABLE";
      reason = "확인 가능한 근거가 부족합니다. " + reason;
    }
    if (
      v.basis === "MODEL_KNOWLEDGE" &&
      ["VERIFIED", "CONTRADICTED", "UNSUPPORTED ASSERTION"].includes(status)
    )
      status = "UNVERIFIABLE";
    if (status === "GENERAL_KNOWLEDGE" && timeSensitive(v.text)) {
      status = "UNVERIFIABLE";
      reason = "시점·버전에 따라 달라지는 주장은 자료 또는 검색이 필요합니다.";
    }
    const general = status === "GENERAL_KNOWLEDGE";
    return {
      text: v.text.slice(0, 3000),
      status,
      reason: general
        ? "일반 지식 판단 · 출처 미확인 · " +
          reason.replace(/^일반 지식 판단 · 출처 미확인[ ·]*/, "")
        : reason,
      evidenceIds: general ? [] : ids,
      basis: general
        ? "MODEL_KNOWLEDGE"
        : ids.length
          ? known.some((e) => ids.includes(e.id) && e.basis === "WEB")
            ? "WEB"
            : "DOCUMENT"
          : undefined,
    };
  });
}
export function exportMeeting(m: Meeting, profiles: boolean): string {
  return JSON.stringify(
    {
      ...m,
      speakers: m.speakers.map((s) =>
        profiles
          ? {
              id: s.id,
              name: s.name,
              embedding: s.embedding,
              baseline: s.baseline,
            }
          : { id: s.id, name: s.name },
      ),
      profileSnapshots: profiles
        ? m.speakers.map((s) => ({
            id: s.id,
            name: s.name,
            embedding: s.embedding,
            baseline: policy(m, s.id),
          }))
        : [],
    },
    null,
    2,
  );
}
export function importMeeting(raw: string): Meeting {
  if (raw.length > 10_000_000) throw Error("파일이 너무 큽니다.");
  const m = JSON.parse(raw);
  const fail = () => {
    throw Error("회의 파일 형식 오류");
  };
  const str = (v: unknown, max = 10000): string =>
    typeof v === "string" && v.length <= max ? v : fail();
  const num = (v: unknown): number =>
    typeof v === "number" && Number.isFinite(v) && v >= 0 ? v : fail();
  const array = (v: unknown, max = 10000): unknown[] =>
    Array.isArray(v) && v.length <= max ? v : fail();
  const object = (v: unknown): Record<string, unknown> =>
    v !== null && typeof v === "object" && !Array.isArray(v)
      ? (v as Record<string, unknown>)
      : fail();
  const status = (v: unknown): Status =>
    [...statuses, "PENDING"].includes(v as Status) ? (v as Status) : fail();
  const readPolicy = (v: unknown): Policy => {
    const p = object(v);
    if (!["NORMAL", "EVIDENCE REQUIRED"].includes(String(p.mode))) fail();
    return {
      mode: p.mode as Policy["mode"],
      unsupported: num(p.unsupported),
      calibrated: num(p.calibrated),
    };
  };
  if (![1, 2].includes(m.schemaVersion)) fail();
  const readBasis = (v: unknown): Basis =>
    ["CALCULATION", "MODEL_KNOWLEDGE", "DOCUMENT", "WEB"].includes(String(v))
      ? (v as Basis)
      : fail();
  const speakers = array(m.speakers, 100).map((v) => {
    const s = object(v);
    return {
      id: str(s.id, 100),
      name: str(s.name, 200),
      ...(s.embedding === undefined
        ? {}
        : {
            embedding: array(s.embedding, 4096).map((v) =>
              typeof v === "number" && Number.isFinite(v) ? v : fail(),
            ),
          }),
      ...(s.baseline === undefined ? {} : { baseline: readPolicy(s.baseline) }),
    };
  });
  const evidence = array(m.evidence, 50000).map((v) => {
    const e = object(v);
    return {
      id: str(e.id, 100),
      text: str(e.text),
      title: str(e.title, 1000),
      source: str(e.source, 3000),
      version: str(e.version, 1000),
      checkedAt: num(e.checkedAt),
      ...(e.page == null ? {} : { page: num(e.page) }),
      ...(e.documentHash == null
        ? {}
        : { documentHash: str(e.documentHash, 64) }),
      ...(e.basis == null ? {} : { basis: readBasis(e.basis) }),
    };
  });
  const claims = array(m.claims).map((v) => {
    const c = object(v);
    if (c.speakerId !== null && !speakers.some((s) => s.id === c.speakerId))
      fail();
    return {
      id: str(c.id, 200),
      utteranceId: str(c.utteranceId, 200),
      text: str(c.text, 3000),
      speakerId: c.speakerId === null ? null : str(c.speakerId, 100),
      speakerCertain: c.speakerCertain === true,
      status: status(c.status),
      reason: str(c.reason, 3000),
      evidenceIds: array(c.evidenceIds, 100).map((v) => str(v, 100)),
      endedAt: num(c.endedAt),
      checkedAt: num(c.checkedAt),
      spoken: true,
      ...(c.basis == null ? {} : { basis: readBasis(c.basis) }),
      attempts: array(c.attempts ?? [], 100).map((v) => {
        const a = object(v);
        if (!["CALCULATION", "LOCAL", "OPENROUTER"].includes(String(a.route)))
          fail();
        return {
          route: a.route as Attempt["route"],
          reason: str(a.reason, 1000),
          startedAt: num(a.startedAt),
          durationMs: num(a.durationMs),
          outcome: str(a.outcome, 1000),
          ...(a.inputTokenUpperBound == null
            ? {}
            : { inputTokenUpperBound: num(a.inputTokenUpperBound) }),
          ...(a.retrievalMs == null ? {} : { retrievalMs: num(a.retrievalMs) }),
          ...(a.queueMs == null ? {} : { queueMs: num(a.queueMs) }),
        };
      }),
      audioSpans: array(c.audioSpans ?? [], 100).map((v) => {
        const a = object(v);
        const startMs = num(a.startMs),
          endMs = num(a.endMs);
        if (endMs < startMs) fail();
        return {
          uri: str(a.uri, 3000),
          file: str(a.file, 300),
          startMs,
          endMs,
        };
      }),
      revisions: array(c.revisions, 1000).map((v) => {
        const r = object(v);
        return {
          basis: r.basis == null ? undefined : readBasis(r.basis),
          evidenceIds: array(r.evidenceIds ?? [], 100).map((v) => str(v, 100)),
          status: status(r.status),
          reason: str(r.reason, 3000),
          at: num(r.at),
        };
      }),
    };
  });
  if (
    new Set(speakers.map((s) => s.id)).size !== speakers.length ||
    new Set(claims.map((c) => c.id)).size !== claims.length ||
    new Set(evidence.map((e) => e.id)).size !== evidence.length
  )
    fail();
  return {
    schemaVersion: 2,
    appVersion: VERSION,
    speakers,
    claims,
    evidence,
    listeningState: "PAUSED",
    speakerHistory: array(m.speakerHistory ?? [], 10000).map((v) => {
      const h = object(v);
      return {
        at: num(h.at),
        kind: str(h.kind, 100),
        id: str(h.id, 200),
        from: str(h.from, 200),
        to: str(h.to, 200),
      };
    }),
  };
}

export function timeSensitive(text: string): boolean {
  return /현재|지금|오늘|올해|최신|최근|버전|가격|주가|대통령|대표이사|법률|법규|규정|출시|20\d{2}|version|latest|current|today|price|president|CEO/i.test(
    text,
  );
}
