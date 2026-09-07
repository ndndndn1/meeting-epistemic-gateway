import {
  CreateWebWorkerMLCEngine,
  type WebWorkerMLCEngine,
} from "@mlc-ai/web-llm";
import { parseVerdicts, retrieve, systemPrompt, type Evidence } from "./core";
import lockedModels from "../../contracts/mlc-models.json";
const API = "https://openrouter.ai/api/v1";
let controller = new AbortController();
let serial: Promise<unknown> = Promise.resolve();
export function cancelAnalysis() {
  controller.abort();
  controller = new AbortController();
  engine?.interruptGenerate();
}
let key = "";
let engine: WebWorkerMLCEngine | undefined;
export const connected = () => Boolean(key);
export const disconnect = () => {
  key = "";
  sessionStorage.removeItem("meg-oauth");
};
const b64 = (x: Uint8Array) =>
  btoa(String.fromCharCode(...x))
    .replace(/\+/g, "-")
    .replace(/\//g, "_")
    .replace(/=+$/, "");
export async function login() {
  const verifier = b64(crypto.getRandomValues(new Uint8Array(32))),
    state = b64(crypto.getRandomValues(new Uint8Array(24)));
  const challenge = b64(
    new Uint8Array(
      await crypto.subtle.digest("SHA-256", new TextEncoder().encode(verifier)),
    ),
  );
  const callback = new URL(import.meta.env.BASE_URL, location.origin);
  callback.searchParams.set("state", state);
  sessionStorage.setItem(
    "meg-oauth",
    JSON.stringify({ verifier, state, at: Date.now() }),
  );
  location.assign(
    `https://openrouter.ai/auth?${new URLSearchParams({ callback_url: callback.href, code_challenge: challenge, code_challenge_method: "S256" })}`,
  );
}
export async function finishLogin() {
  const url = new URL(location.href),
    code = url.searchParams.get("code");
  if (!code) return false;
  const saved = sessionStorage.getItem("meg-oauth");
  sessionStorage.removeItem("meg-oauth");
  history.replaceState(null, "", import.meta.env.BASE_URL);
  if (!saved) throw Error("로그인 세션이 없습니다. 다시 연결하세요.");
  const p = JSON.parse(saved);
  if (url.searchParams.get("state") !== p.state || Date.now() - p.at > 600000)
    throw Error("로그인 상태가 일치하지 않거나 만료됐습니다.");
  const res = await fetch(`${API}/auth/keys`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      code,
      code_verifier: p.verifier,
      code_challenge_method: "S256",
    }),
    signal: AbortSignal.timeout(20000),
  });
  if (!res.ok) throw Error("OpenRouter 로그인 교환에 실패했습니다.");
  const data = await res.json();
  if (typeof data.key !== "string") throw Error("키 응답 오류");
  key = data.key;
  return true;
}
export async function account() {
  const res = await fetch(`${API}/key`, {
    headers: { Authorization: `Bearer ${key}` },
    signal: AbortSignal.timeout(15000),
  });
  if (!res.ok) throw Error(`계정 조회 실패 (${res.status})`);
  return (await res.json()).data as {
    limit_remaining: number | null;
    usage: number;
    is_free_tier: boolean;
  };
}
export async function models() {
  const r = await fetch(`${API}/models`, {
    signal: AbortSignal.timeout(15000),
  });
  if (!r.ok) throw Error("모델 목록을 불러오지 못했습니다.");
  return (await r.json()).data as {
    id: string;
    name: string;
    pricing: { prompt: string; completion: string };
  }[];
}
export async function loadLocal(model: string, progress: (s: string) => void) {
  if (!("gpu" in navigator))
    throw Error(
      "WebGPU 지원 Chrome·Edge가 필요합니다. APK 또는 선택적 OpenRouter 연결을 사용하세요.",
    );
  await engine?.unload();
  engine = await CreateWebWorkerMLCEngine(
    new Worker(new URL("./llm.worker.ts", import.meta.url), { type: "module" }),
    model,
    {
      appConfig: {
        model_list: lockedModels.map((m) => ({
          ...m,
          integrity: { ...m.integrity, onFailure: "error" as const },
        })),
      },
      initProgressCallback: (r) => progress(r.text),
    },
  );
  await navigator.storage?.persist?.();
}
export function localReady() {
  return Boolean(engine);
}
async function analyzeOne(
  text: string,
  docs: Evidence[],
  options: { remote: boolean; model: string; web: boolean },
  signal: AbortSignal,
) {
  signal.throwIfAborted();
  let evidence = retrieve(text, docs);
  const content = JSON.stringify({
    utterance: text,
    evidence,
    hasEvidenceScope: docs.length > 0,
  });
  const messages = [
    { role: "system" as const, content: systemPrompt },
    { role: "user" as const, content },
  ];
  let raw: string;
  let warning = "";
  if (options.remote && key) {
    try {
      if (!options.model) throw Error("모델을 선택하세요.");
      const r = await fetch(`${API}/chat/completions`, {
        method: "POST",
        headers: {
          Authorization: `Bearer ${key}`,
          "Content-Type": "application/json",
          "X-Title": "Meeting Epistemic Gateway",
        },
        body: JSON.stringify({
          model: options.model,
          messages,
          max_tokens: 900,
          temperature: 0,
          plugins: options.web ? [{ id: "web", max_results: 3 }] : undefined,
        }),
        signal: AbortSignal.any([signal, AbortSignal.timeout(25000)]),
      });
      if (!r.ok)
        throw Error(
          (
            {
              401: "로그인 만료",
              402: "잔액 부족",
              429: "요청 한도 초과",
            } as Record<number, string>
          )[r.status] ?? `요청 실패 (${r.status})`,
        );
      const d = await r.json(),
        msg = d.choices?.[0]?.message;
      if (!msg?.content) throw Error("판정 응답 없음");
      raw = msg.content;
      const citations = (msg.annotations ?? []).filter(
        (a: { type: string }) => a.type === "url_citation",
      );
      // Only supplied citation content may become evidence; model-generated URLs cannot.
      const webDocs: Evidence[] = citations.flatMap(
        (a: {
          url_citation: { url: string; title: string; content?: string };
        }) => {
          const c = a.url_citation;
          return /^https?:\/\//.test(c.url) && c.content
            ? [
                {
                  id: crypto.randomUUID(),
                  text: c.content,
                  title: c.title,
                  source: c.url,
                  version: "웹 검색",
                  checkedAt: Date.now(),
                },
              ]
            : [];
        },
      );
      if (webDocs.length) {
        evidence = [...evidence, ...webDocs];
        const r2 = await fetch(`${API}/chat/completions`, {
          method: "POST",
          headers: {
            Authorization: `Bearer ${key}`,
            "Content-Type": "application/json",
          },
          body: JSON.stringify({
            model: options.model,
            messages: [
              messages[0],
              {
                role: "user",
                content: JSON.stringify({
                  utterance: text,
                  evidence,
                  hasEvidenceScope: true,
                }),
              },
            ],
            temperature: 0,
            max_tokens: 900,
          }),
          signal: AbortSignal.any([signal, AbortSignal.timeout(20000)]),
        });
        if (!r2.ok) throw Error("검색 근거의 판정에 실패했습니다.");
        raw = (await r2.json()).choices[0].message.content;
      }
      return {
        results: parseVerdicts(
          raw,
          evidence,
          docs.length > 0 || webDocs.length > 0,
        ),
        evidence,
        warning,
      };
    } catch (e) {
      warning = String(e) + " · 로컬 검증으로 전환";
    }
  }
  signal.throwIfAborted();
  if (!engine)
    throw Error(warning || "로컬 모델을 준비하거나 OpenRouter를 연결하세요.");
  const result = await engine.chat.completions.create({
    messages,
    temperature: 0,
    max_tokens: 900,
    response_format: { type: "json_object" },
    extra_body: { enable_thinking: false },
  });
  raw = result.choices[0].message.content ?? "";
  return {
    results: parseVerdicts(raw, evidence, docs.length > 0),
    evidence,
    warning,
  };
}

export function analyze(
  text: string,
  docs: Evidence[],
  options: { remote: boolean; model: string; web: boolean },
) {
  const signal = controller.signal;
  const task = serial.then(() => analyzeOne(text, docs, options, signal));
  serial = task.catch(() => {});
  return task;
}
