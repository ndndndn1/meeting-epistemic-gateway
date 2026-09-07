import { type Evidence, type Attempt, systemPrompt, retrieve } from "./core";
export type Mode = "AUTO" | "LOCAL_ONLY" | "OPENROUTER_FIRST";
// UTF-8 bytes conservatively bound byte-BPE tokens, including JSON and chat framing.
export function boundedInput(text: string, docs: Evidence[]) {
  const start = performance.now();
  const evidence = retrieve(text, docs);
  const payload = () =>
    JSON.stringify({
      utterance: text,
      evidence,
      hasEvidenceScope: evidence.length > 0,
    });
  const size = () =>
    new TextEncoder().encode(systemPrompt + payload()).length + 128;
  while (evidence.length && size() > 2800) evidence.pop();
  return {
    evidence,
    content: payload(),
    inputTokenUpperBound: size(),
    retrievalMs: performance.now() - start,
  };
}
export function predictDelay(
  ready: boolean,
  tokens: number,
  queue: number,
  recent: number[] = [],
) {
  // S24 v0.1 native minimum 7.5s; browser generation failed. No optimistic cold start.
  return !ready
    ? Infinity
    : (recent.length ? Math.max(...recent.slice(-5)) : 7500) *
        Math.max(1, tokens / 1800) +
        queue * 5000;
}
export async function route<T>(o: {
  mode: Mode;
  available: boolean;
  predictedMs: number;
  local: (signal: AbortSignal) => Promise<T>;
  remote: () => Promise<T>;
  signal: AbortSignal;
  onAttempt: (a: Attempt) => void;
  onWarning: (s: string) => void;
  budgetMs?: number;
}) {
  const budget = o.budgetMs ?? 5000;
  let triedRemote = false;
  const attempt = async (
    kind: "LOCAL" | "OPENROUTER",
    reason: string,
    run: () => Promise<T>,
  ) => {
    const startedAt = Date.now();
    try {
      const result = await run();
      o.signal.throwIfAborted();
      o.onAttempt({
        route: kind,
        reason,
        startedAt,
        durationMs: Date.now() - startedAt,
        outcome: "completed",
      });
      return result;
    } catch (e) {
      o.onAttempt({
        route: kind,
        reason,
        startedAt,
        durationMs: Date.now() - startedAt,
        outcome: e instanceof Error ? e.message : String(e),
      });
      throw e;
    }
  };
  const cloud = async (reason: string) => {
    triedRemote = true;
    return attempt("OPENROUTER", reason, o.remote);
  };
  o.signal.throwIfAborted();
  if (o.mode !== "LOCAL_ONLY" && !o.available)
    o.onWarning(
      "OpenRouter 계정 연결과 모델 선택이 필요합니다. 로컬 처리를 유지합니다.",
    );
  if (
    o.available &&
    (o.mode === "OPENROUTER_FIRST" ||
      (o.mode === "AUTO" && o.predictedMs > budget))
  ) {
    try {
      return await cloud(
        o.mode === "AUTO" ? "예상 지연 5초 초과" : "OpenRouter 우선 선택",
      );
    } catch (e) {
      o.signal.throwIfAborted();
      o.onWarning(`${String(e)} · 로컬 검증으로 전환`);
    }
  }
  const localController = new AbortController();
  const signal = AbortSignal.any([o.signal, localController.signal]);
  let timer: ReturnType<typeof setTimeout> | undefined;
  let exceeded = false;
  try {
    return await attempt(
      "LOCAL",
      triedRemote ? "OpenRouter 오류 후 로컬" : "로컬 실행",
      () => {
        const task = o.local(signal);
        if (o.mode !== "AUTO" || !o.available || triedRemote) return task;
        return Promise.race([
          task,
          new Promise<never>((_, reject) => {
            timer = setTimeout(() => {
              exceeded = true;
              localController.abort();
              reject(Error("로컬 5초 예산 초과 · 취소"));
            }, budget);
          }),
        ]);
      },
    );
  } catch (e) {
    o.signal.throwIfAborted();
    if (exceeded && !triedRemote)
      return await cloud("로컬 5초 초과 후 취소·전환");
    throw e;
  } finally {
    clearTimeout(timer);
  }
}
