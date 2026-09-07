import React, { useEffect, useRef, useState } from "react";
import { createRoot } from "react-dom/client";
import {
  emptyMeeting,
  exportMeeting,
  importMeeting,
  intervention,
  paragraphs,
  policy,
  revise,
  VERSION,
  type Claim,
  type Meeting,
  type Status,
} from "./core";
import * as ai from "./inference";
import { readDocument } from "./documents";
import { Voice } from "./speech";
import { Capture } from "./capture";
import { importModel, speechAssets } from "./model-import";
import "./style.css";
const labels: Record<Status, string> = {
  VERIFIED: "근거 확인",
  EXPERIENCE: "직접 경험",
  HYPOTHESIS: "추정·가설",
  "UNSUPPORTED ASSERTION": "근거 없는 단정",
  CONTRADICTED: "근거와 충돌",
  UNVERIFIABLE: "검증 불가",
  PENDING: "검증 중",
  QUESTION: "질문",
  "VALUE JUDGMENT": "가치판단",
  PROPOSAL: "제안",
};
function App() {
  const [starting, setStarting] = useState(false);
  const [meeting, setMeeting] = useState<Meeting>(emptyMeeting),
    [tab, setTab] = useState("meeting"),
    [notice, setNotice] = useState(
      "모델 준비 후 회의를 시작하세요. 원음은 저장하지 않습니다.",
    ),
    [running, setRunning] = useState(false),
    [busy, setBusy] = useState(false),
    [text, setText] = useState(""),
    [speaker, setSpeaker] = useState(""),
    [remote, setRemote] = useState(false),
    [web, setWeb] = useState(true),
    [model, setModel] = useState(""),
    [localModel, setLocalModel] = useState("Qwen3-1.7B-q4f16_1-MLC"),
    [catalog, setCatalog] = useState<Awaited<ReturnType<typeof ai.models>>>([]),
    [profiles, setProfiles] = useState(false),
    [docText, setDocText] = useState(""),
    [docTitle, setDocTitle] = useState("회의 자료"),
    [ready, setReady] = useState(false),
    [balance, setBalance] = useState(""),
    [filter, setFilter] = useState("all");
  const state = useRef(meeting);
  state.current = meeting;
  const voice = useRef(new Voice());
  const capture = useRef<Capture | null>(null);
  const epoch = useRef(0);
  const stoppedSpeechAt = useRef(0);
  const speakerAliases = useRef(new Map<string, string>());
  const options = useRef({ remote, model, web });
  options.current = { remote, model, web };
  const live = useRef(false);
  live.current = running;
  useEffect(() => {
    const timer = setInterval(() => {
      if (!live.current || voice.current.speaking || !voice.current.available())
        return;
      const claim = state.current.claims.find(
        (c) => c.endedAt > stoppedSpeechAt.current && intervention(c),
      );
      if (!claim) return;
      voice.current.say(intervention(claim)!);
      setMeeting((m) => ({
        ...m,
        claims: m.claims.map((c) =>
          c.id === claim.id ? { ...c, spoken: true } : c,
        ),
      }));
    }, 250);
    const visibility = () => {
      if (document.hidden && live.current) {
        capture.current?.stop();
        voice.current.stop();
        live.current = false;
        setRunning(false);
        setNotice("화면이 전면에서 벗어나 마이크를 중지했습니다.");
      }
    };
    document.addEventListener("visibilitychange", visibility);
    return () => {
      clearInterval(timer);
      document.removeEventListener("visibilitychange", visibility);
    };
  }, []);
  useEffect(() => {
    ai.finishLogin()
      .then(async (ok) => {
        if (ok) {
          setRemote(true);
          setNotice(
            "OpenRouter 연결됨. 무료 모델도 웹 검색 요금이 발생할 수 있습니다.",
          );
          setCatalog(await ai.models());
          const b = await ai.account();
          setBalance(
            `키 잔여 한도: ${b.limit_remaining === null ? "별도 한도 없음" : `$${b.limit_remaining}`} · 사용 $${b.usage}`,
          );
        }
      })
      .catch((e) => setNotice(String(e)));
    return () => {
      capture.current?.stop();
      voice.current.stop();
    };
  }, []);
  async function submit(
    value = text,
    who = speaker,
    endedAt = Date.now(),
    certain = true,
    replaceId?: string,
  ) {
    if (!value.trim()) return;
    if (
      state.current.claims.filter((c) => c.status === "PENDING").length >= 4
    ) {
      setNotice("검증 대기열이 가득 찼습니다. 잠시 회의를 멈춰 주세요.");
      return;
    }
    const generation = epoch.current,
      id = replaceId ?? crypto.randomUUID();
    const previous = state.current.claims.find((c) => c.id === replaceId);
    const c: Claim = previous
      ? revise(previous, "PENDING", "이의제기: 재검증 중")
      : {
          id,
          utteranceId: id,
          text: value,
          speakerId: who || null,
          speakerCertain: certain && !!who,
          status: "PENDING",
          reason: "근거를 확인하고 있습니다.",
          evidenceIds: [],
          endedAt,
          checkedAt: Date.now(),
          revisions: [],
          spoken: false,
        };
    setText("");
    setMeeting((m) => ({
      ...m,
      claims: replaceId
        ? m.claims.map((x) => (x.id === id ? c : x))
        : [...m.claims, c],
    }));
    setBusy(true);
    try {
      const result = await ai.analyze(
        value,
        state.current.evidence,
        options.current,
      );
      if (epoch.current !== generation) return;
      const claims = result.results.map((r, i) => ({
        ...c,
        ...r,
        id: replaceId && i === 0 ? id : `${id}:${i}`,
        checkedAt: Date.now(),
      }));
      setMeeting((m) => ({
        ...m,
        claims: m.claims.flatMap((x) => (x.id === id ? claims : [x])),
        evidence: [
          ...m.evidence,
          ...result.evidence.filter(
            (e) => !m.evidence.some((x) => x.id === e.id),
          ),
        ],
      }));
      if (result.warning) setNotice(result.warning);
      if (
        previous?.spoken &&
        claims[0]?.status !== previous.status &&
        live.current
      )
        voice.current.say(
          "이전 판정을 정정합니다. 재검증 결과를 화면에서 확인해 주세요.",
        );
    } catch (e) {
      if (epoch.current !== generation) return;
      setNotice(String(e));
      setMeeting((m) => ({
        ...m,
        claims: m.claims.map((x) =>
          x.id === id ? revise(x, "UNVERIFIABLE", String(e)) : x,
        ),
      }));
    } finally {
      setBusy(false);
    }
  }
  async function start() {
    if (starting) return;
    setStarting(true);
    try {
      if (!ai.localReady() && !ai.connected())
        throw Error("먼저 설정에서 모델을 준비하세요.");
      if (!voice.current.available())
        setNotice(
          "오프라인 한국어 음성이 없습니다. 음성 설치 전까지 결과를 화면으로 표시합니다.",
        );
      const cap = new Capture(
        (t, id, end, certain) => {
          while (speakerAliases.current.has(id))
            id = speakerAliases.current.get(id)!;
          if (!voice.current.speaking) void submit(t, id, end, certain);
        },
        (id, embedding) =>
          setMeeting((m) =>
            speakerAliases.current.has(id) ||
            m.speakers.some((s) => s.id === id)
              ? m
              : {
                  ...m,
                  speakers: [
                    ...m.speakers,
                    {
                      id,
                      name: `화자 ${String.fromCharCode(65 + m.speakers.length)}`,
                      embedding,
                    },
                  ],
                },
          ),
        () => voice.current.speaking,
        () => voice.current.interrupt(),
        (message) => {
          setNotice(message);
          setRunning(false);
        },
        setNotice,
      );
      capture.current = cap;
      await cap.start();
      setRunning(true);
      setNotice("듣고 있습니다. 앱을 전면에 유지하세요.");
    } catch (e) {
      setNotice(String(e));
    } finally {
      setStarting(false);
    }
  }
  function end() {
    live.current = false;
    speakerAliases.current.clear();
    epoch.current++;
    ai.cancelAnalysis();
    capture.current?.stop();
    capture.current = null;
    voice.current.stop();
    setRunning(false);
    setBusy(false);
    setMeeting(emptyMeeting());
    setNotice(
      "회의를 종료했습니다. 저장하지 않은 기록과 목소리 정보는 폐기했습니다.",
    );
  }
  function save() {
    const blob = new Blob([exportMeeting(meeting, profiles)], {
        type: "application/json",
      }),
      a = document.createElement("a");
    a.href = URL.createObjectURL(blob);
    a.download = `meeting-${new Date().toISOString().slice(0, 10)}.json`;
    a.click();
    setTimeout(() => URL.revokeObjectURL(a.href), 1000);
  }
  async function files(list: FileList | null) {
    if (!list) return;
    try {
      for (const file of Array.from(list)) {
        const evidence = await readDocument(file);
        setMeeting((m) => ({ ...m, evidence: [...m.evidence, ...evidence] }));
      }
      setNotice("자료를 기기에 추가했습니다.");
    } catch (e) {
      setNotice(String(e));
    }
  }
  const unsupported = meeting.claims.filter(
      (c) => c.status === "UNSUPPORTED ASSERTION",
    ).length,
    verified = meeting.claims.filter((c) => c.status === "VERIFIED").length;
  return (
    <div className="shell">
      <aside>
        <a className="brand" href="#">
          <span className="mark">
            m<span>e</span>g
          </span>
          <strong>
            Meeting
            <br />
            Epistemic Gateway
          </strong>
        </a>
        <p className="eyebrow">근거가 중심이 되는 회의</p>
        <nav>
          {[
            ["meeting", "◉", "실시간 회의"],
            ["evidence", "▤", "회의 자료"],
            ["speakers", "◌", "참가자"],
            ["settings", "⚙", "모델 및 연결"],
          ].map(([id, icon, title]) => (
            <button
              key={id}
              className={tab === id ? "active" : ""}
              onClick={() => setTab(id)}
            >
              <span>{icon}</span>
              {title}
            </button>
          ))}
        </nav>
        <div className="privacy">
          <span className="dot" /> 기기에서 처리
          <br />
          <small>
            {remote
              ? "선택한 주장·자료만 OpenRouter 전송"
              : "원음 저장 없음 · 외부 AI 연결 없음"}
          </small>
        </div>
        <footer>
          v{VERSION} · 사전 릴리스
          <br />
          실시간 기준 미달 · 실험용
        </footer>
      </aside>
      <main>
        <header>
          <div>
            <span className="eyebrow">MEETING WORKSPACE</span>
            <h1>
              {
                (
                  {
                    meeting: "주장을 듣고, 근거를 확인합니다.",
                    evidence: "같은 자료에서 시작하는 대화",
                    speakers: "발언과 근거를 함께 살펴보세요.",
                    settings: "내 기기에서, 내 선택으로.",
                  } as Record<string, string>
                )[tab]
              }
            </h1>
          </div>
          <span className={"pill " + (running ? "live" : "")}>
            {running ? "● 회의 진행 중" : "회의 대기"}
          </span>
        </header>
        <div className="notice" role="status">
          {notice}
        </div>
        {tab === "meeting" && (
          <>
            <section className="hero">
              <div>
                <span className="eyebrow">LIVE FACT CHECK</span>
                <h2>
                  확신보다
                  <br />
                  <em>확인 가능한 근거.</em>
                </h2>
                <p>
                  경험은 경험으로, 가설은 가설로.
                  <br />
                  발언이 끝나면 필요한 사실관계만 짧게 짚습니다.
                </p>
                <div className="actions">
                  <button
                    className="primary"
                    disabled={starting}
                    onClick={running ? end : start}
                  >
                    {starting
                      ? "음성 모델 준비 중…"
                      : running
                        ? "회의 종료 · 미저장 기록 삭제"
                        : "마이크로 회의 시작"}{" "}
                    <span>↗</span>
                  </button>
                  <button
                    onClick={() => {
                      stoppedSpeechAt.current = Date.now();
                      voice.current.stop();
                      setNotice("AI 발언을 중지했습니다.");
                    }}
                  >
                    AI 발언 중지
                  </button>
                </div>
              </div>
              <div className="wave" aria-hidden="true">
                {Array.from({ length: 27 }, (_, i) => (
                  <i
                    key={i}
                    style={{
                      height: `${18 + Math.sin(i * 0.7) ** 2 * 74}px`,
                      animationDelay: `${i * 0.08}s`,
                    }}
                  />
                ))}
              </div>
            </section>
            <section className="metrics">
              <div>
                <small>검토한 주장</small>
                <strong>
                  {meeting.claims.length}
                  <span>건</span>
                </strong>
              </div>
              <div>
                <small>근거 확인</small>
                <strong>
                  {verified}
                  <span>건</span>
                </strong>
              </div>
              <div>
                <small>근거 없는 단정</small>
                <strong>
                  {unsupported}
                  <span>건</span>
                </strong>
              </div>
              <div>
                <small>참가자</small>
                <strong>
                  {meeting.speakers.length}
                  <span>명</span>
                </strong>
              </div>
            </section>
            <section className="ledger">
              <div className="section-head">
                <h2>
                  주장과 근거 <small>CLAIM LEDGER</small>
                </h2>
                <select
                  aria-label="판정 필터"
                  value={filter}
                  onChange={(e) => setFilter(e.target.value)}
                >
                  <option value="all">모든 판정</option>
                  <option value="UNSUPPORTED ASSERTION">근거 없는 단정</option>
                  <option value="CONTRADICTED">충돌</option>
                  <option value="VERIFIED">근거 확인</option>
                </select>
              </div>
              {meeting.claims.length === 0 ? (
                <div className="empty">
                  <span>↳</span>
                  <h3>첫 번째 주장을 기다리고 있습니다.</h3>
                  <p>
                    회의 자료를 추가하고 모델을 준비한 후 마이크를 켜세요.
                    <br />
                    아래에서 직접 입력한 문장도 같은 방식으로 검증할 수
                    있습니다.
                  </p>
                </div>
              ) : (
                meeting.claims
                  .filter((c) => filter === "all" || c.status === filter)
                  .slice()
                  .reverse()
                  .map((c) => (
                    <article className="claim" key={c.id}>
                      <div className="claim-top">
                        <span
                          className={"badge " + c.status.replaceAll(" ", "-")}
                        >
                          {labels[c.status]}
                        </span>
                        <select
                          aria-label="발언자 수정"
                          value={c.speakerId ?? ""}
                          onChange={(e) =>
                            setMeeting((m) => ({
                              ...m,
                              claims: m.claims.map((x) =>
                                x.id === c.id
                                  ? {
                                      ...x,
                                      speakerId: e.target.value || null,
                                      speakerCertain: !!e.target.value,
                                    }
                                  : x,
                              ),
                            }))
                          }
                        >
                          <option value="">화자 미확정</option>
                          {meeting.speakers.map((s) => (
                            <option key={s.id} value={s.id}>
                              {s.name}
                            </option>
                          ))}
                        </select>
                        <time>
                          {new Date(c.endedAt).toLocaleTimeString("ko-KR")}
                        </time>
                      </div>
                      <h3>{c.text}</h3>
                      <p>{c.reason}</p><small>확인 시각: {new Date(c.checkedAt).toLocaleTimeString("ko-KR")}</small>
                      {c.evidenceIds.map((id) => {
                        const e = meeting.evidence.find((x) => x.id === id);
                        return e ? (
                          <blockquote key={id}>
                            {e.text}
                            <small>
                              {/^https?:\/\//.test(e.source) ? (
                                <a
                                  href={e.source}
                                  target="_blank"
                                  rel="noreferrer"
                                >
                                  {e.title} ↗
                                </a>
                              ) : (
                                e.title
                              )}{" "}
                              · {e.version}
                              {e.page ? ` · p.${e.page}` : ""}
                            </small>
                          </blockquote>
                        ) : null;
                      })}
                      <div className="actions compact">
                        {c.speakerId &&
                          policy(meeting, c.speakerId).mode ===
                            "EVIDENCE REQUIRED" && (
                            <>
                              <strong>
                                근거 또는 표현 유형을 선택해 주세요.
                              </strong>
                              <button onClick={() => setTab("evidence")}>
                                근거 제시
                              </button>
                            </>
                          )}
                        <button
                          disabled={busy}
                          onClick={() => {
                            void submit(
                              c.text,
                              c.speakerId ?? "",
                              c.endedAt,
                              c.speakerCertain,
                              c.id,
                            );
                          }}
                        >
                          이의제기 · 재검증
                        </button>
                        {(["EXPERIENCE", "HYPOTHESIS"] as Status[]).map((s) => (
                          <button
                            key={s}
                            onClick={() => {
                              if (c.spoken)
                                voice.current.say(
                                  "이전 판정을 정정합니다. 발언자가 경험 또는 추정으로 표시했습니다.",
                                );
                              setMeeting((m) => ({
                                ...m,
                                claims: m.claims.map((x) =>
                                  x.id === c.id
                                    ? revise(
                                        x,
                                        s,
                                        "발언자가 표현 유형을 수정했습니다.",
                                      )
                                    : x,
                                ),
                              }));
                            }}
                          >
                            {labels[s]}으로 표시
                          </button>
                        ))}
                      </div>
                    </article>
                  ))
              )}
            </section>
            <section className="manual">
              <label htmlFor="claim-input">직접 입력하여 확인</label>
              <textarea
                id="claim-input"
                value={text}
                onChange={(e) => setText(e.target.value)}
                placeholder="예: 이 장비의 제조사 최대 입력 전압은 48V입니다."
              />
              <div className="actions">
                <select
                  aria-label="직접 입력 발언자"
                  value={speaker}
                  onChange={(e) => setSpeaker(e.target.value)}
                >
                  <option value="">화자 미확정 · 누적 제외</option>
                  {meeting.speakers.map((s) => (
                    <option key={s.id} value={s.id}>
                      {s.name}
                    </option>
                  ))}
                </select>
                <button
                  className="primary"
                  disabled={busy || !text.trim()}
                  onClick={() => void submit()}
                >
                  {busy ? "검증 중…" : "주장 검증"}
                </button>
              </div>
            </section>
            <div className="export">
              <label>
                <input
                  type="checkbox"
                  checked={profiles}
                  onChange={(e) => setProfiles(e.target.checked)}
                />
                화자 이름·누적 상태 포함
              </label>
              <button onClick={save}>회의 기록 저장 ↓</button>
              <label className="file-button">
                기록 가져오기
                <input
                  type="file"
                  accept=".json"
                  onChange={async (e) => {
                    try {
                      const f = e.target.files?.[0];
                      if (f) {
                        const m = importMeeting(await f.text());
                        end();
                        setMeeting(m);
                        setNotice(
                          "저장된 회의를 불러왔습니다. 새 회의에 프로필을 연결하려면 참가자 화면을 사용하세요.",
                        );
                      }
                    } catch (err) {
                      setNotice(String(err));
                    }
                  }}
                />
              </label>
            </div>
          </>
        )}
        {tab === "evidence" && (
          <section className="panel">
            <h2>회의 자료 추가</h2>
            <p>
              PDF의 텍스트, TXT, Markdown을 기기에서 읽습니다. 스캔 PDF는
              지원하지 않습니다.
            </p>
            <label className="dropzone">
              ＋ 파일 선택
              <input
                type="file"
                accept=".pdf,.txt,.md"
                multiple
                onChange={(e) => void files(e.target.files)}
              />
            </label>
            <input
              aria-label="자료 이름"
              value={docTitle}
              onChange={(e) => setDocTitle(e.target.value)}
            />
            <textarea
              aria-label="자료 내용"
              rows={8}
              value={docText}
              onChange={(e) => setDocText(e.target.value)}
              placeholder="출처·적용 버전과 함께 자료를 붙여넣으세요."
            />
            <button
              className="primary"
              disabled={!docText.trim()}
              onClick={() => {
                setMeeting((m) => ({
                  ...m,
                  evidence: [...m.evidence, ...paragraphs(docText, docTitle)],
                }));
                setDocText("");
              }}
            >
              자료 추가
            </button>
            <h3>추가된 근거 {meeting.evidence.length}개</h3>
            {meeting.evidence.map((e) => (
              <article key={e.id}>
                <strong>{e.title}</strong>
                <p>{e.text.slice(0, 300)}</p>
                <button
                  onClick={() =>
                    setMeeting((m) => ({
                      ...m,
                      evidence: m.evidence.filter((x) => x.id !== e.id),
                      claims: m.claims.map((c) =>
                        c.evidenceIds.includes(e.id)
                          ? revise(
                              c,
                              "UNVERIFIABLE",
                              "근거가 삭제됐습니다.",
                              [],
                            )
                          : c,
                      ),
                    }))
                  }
                >
                  삭제
                </button>
              </article>
            ))}
          </section>
        )}
        {tab === "speakers" && (
          <section className="panel">
            <h2>참가자와 근거 제시 상태</h2>
            <p>
              목소리 자동 구분은 신원 확인이 아닙니다. 오배정은 수정할 수
              있습니다. 웹에서 다음 회의로 연결한 이름은 수동으로 배정하세요.
            </p>
            <button
              onClick={() => {
                const id = crypto.randomUUID();
                setMeeting((m) => ({
                  ...m,
                  speakers: [
                    ...m.speakers,
                    {
                      id,
                      name: `화자 ${String.fromCharCode(65 + m.speakers.length)}`,
                    },
                  ],
                }));
              }}
            >
              ＋ 참가자 추가
            </button>
            {meeting.speakers.map((s) => {
              const p = policy(meeting, s.id);
              return (
                <article key={s.id}>
                  <input
                    aria-label="참가자 이름"
                    value={s.name}
                    onChange={(e) =>
                      setMeeting((m) => ({
                        ...m,
                        speakers: m.speakers.map((x) =>
                          x.id === s.id ? { ...x, name: e.target.value } : x,
                        ),
                      }))
                    }
                  />
                  <span className="badge">{p.mode}</span>
                  <p>
                    연속 근거 없는 단정 {p.unsupported}회 · 적절한 표시{" "}
                    {p.calibrated}회
                  </p>
                  <select
                    aria-label="화자 병합"
                    value=""
                    onChange={(e) => {
                      const target = e.target.value;
                      if (target) speakerAliases.current.set(s.id, target);
                      if (target)
                        setMeeting((m) => ({
                          ...m,
                          speakers: m.speakers.filter((x) => x.id !== s.id),
                          claims: m.claims.map((c) =>
                            c.speakerId === s.id
                              ? { ...c, speakerId: target }
                              : c,
                          ),
                        }));
                    }}
                  >
                    <option value="">다른 화자와 병합…</option>
                    {meeting.speakers
                      .filter((x) => x.id !== s.id)
                      .map((x) => (
                        <option key={x.id} value={x.id}>
                          {x.name}
                        </option>
                      ))}
                  </select>
                </article>
              );
            })}
            <button
              onClick={() => {
                if (
                  confirm(
                    "선택한 화자 프로필과 현재 누적 상태를 새 회의에 연결할까요?",
                  )
                ) {
                  const speakers = state.current.speakers.map((s) => ({
                    ...s,
                    baseline: policy(state.current, s.id),
                  }));
                  end();
                  setMeeting({ ...emptyMeeting(), speakers });
                }
              }}
            >
              이름·누적 상태 확인 후 새 회의에 연결
            </button>
          </section>
        )}
        {tab === "settings" && (
          <section className="panel">
            <h2>기기 단독 실행</h2>
            <p>
              최초 수 GB 다운로드가 필요합니다. Wi-Fi 연결을 권장합니다.
              <br />
              모델 크기·성능은 아래 선택에 따라 다르며 실기기 초기 비교에서
              실시간 기준에 미달했습니다.
            </p>
            <select
              aria-label="로컬 모델"
              value={localModel}
              onChange={(e) => setLocalModel(e.target.value)}
            >
              <option value="Qwen3-1.7B-q4f16_1-MLC">
                Qwen3 1.7B · 약 1GB 가중치 + 실행 메모리
              </option>
              <option value="Qwen3-4B-q4f16_1-MLC">
                Qwen3 4B · 약 2.5GB 가중치 + 실행 메모리
              </option>
            </select>
            <button
              className="primary"
              disabled={busy}
              onClick={async () => {
                setBusy(true);
                try {
                  await ai.loadLocal(localModel, setNotice);
                  setReady(true);
                  setNotice(
                    "로컬 언어 모델 준비 완료. 음성 모델은 회의 시작 시 준비합니다.",
                  );
                } catch (e) {
                  setNotice(String(e));
                } finally {
                  setBusy(false);
                }
              }}
            >
              {ready ? "모델 다시 준비" : "모델 다운로드 및 준비"}
            </button>
            <p>브라우저 저장 공간을 지우면 다시 다운로드해야 합니다.</p>
            <button
              disabled={busy}
              onClick={async () => {
                setBusy(true);
                try {
                  await voice.current.prepareFallback(setNotice);
                  setNotice("한국어 오프라인 음성 준비 완료");
                } catch (e) {
                  setNotice(String(e));
                } finally {
                  setBusy(false);
                }
              }}
            >
              한국어 음성 다운로드 · 약 145MB · Wi-Fi 권장
            </button>
            <details>
              <summary>음성 모델 직접 가져오기 · 다운로드가 차단될 때</summary>
              <p>
                아래 공식 파일을 내려받아 선택하세요. SHA-256이 일치하는 파일만
                기기에 저장합니다.
              </p>
              <input
                aria-label="음성 모델 파일 가져오기"
                type="file"
                multiple
                onChange={async (e) => {
                  try {
                    for (const f of Array.from(e.target.files ?? []))
                      setNotice((await importModel(f)) + " 가져오기 완료");
                  } catch (err) {
                    setNotice(String(err));
                  }
                }}
              />
              {speechAssets.map((a) => (
                <p key={a.id}>
                  <a href={a.url} target="_blank" rel="noreferrer">
                    {a.id} · {(a.bytes / 1048576).toFixed(1)} MB ↗
                  </a>
                </p>
              ))}
            </details>
            <hr />
            <h2>
              OpenRouter 연결 <small>선택 사항</small>
            </h2>
            <p>
              원음 없이 검증 대상 주장과 필요한 자료 발췌를 전송합니다. 웹
              검색은 기본 ON이며 무료 모델도 검색 요금이 발생할 수 있습니다.
            </p>
            {!ai.connected() ? (
              <button onClick={() => void ai.login()}>
                OpenRouter로 로그인 ↗
              </button>
            ) : (
              <>
                <p>{balance}</p>
                <label>
                  <input
                    type="checkbox"
                    checked={remote}
                    onChange={(e) => setRemote(e.target.checked)}
                  />
                  외부 AI 사용
                </label>
                <label>
                  <input
                    type="checkbox"
                    checked={web}
                    onChange={(e) => setWeb(e.target.checked)}
                  />
                  웹 검색 사용 · 별도 요금 가능
                </label>
                <select
                  aria-label="OpenRouter 모델"
                  value={model}
                  onChange={(e) => setModel(e.target.value)}
                >
                  <option value="">무료·유료 모델 직접 선택</option>
                  {catalog.map((m) => (
                    <option value={m.id} key={m.id}>
                      {m.name} · 입력 ${Number(m.pricing.prompt) * 1e6}/M · 출력
                      ${Number(m.pricing.completion) * 1e6}/M
                    </option>
                  ))}
                </select>
                <button
                  onClick={() => {
                    ai.disconnect();
                    setRemote(false);
                    setNotice("연결을 해제했습니다.");
                  }}
                >
                  연결 해제
                </button>
              </>
            )}
            <hr />
            <h2>검증 상태</h2>
            <p>
              실기기 초기 비교에서 정확도·지연 목표에 미달했습니다. 60분 연속
              실행과 다인 회의는 미검증입니다. 결과는 검토를 위한 보조 정보이며,
              자료가 없으면 검증 불가로 남깁니다.
            </p>
            <a href="https://github.com/ndndndn1/meeting-epistemic-gateway/releases">
              S24 Ultra APK · 릴리스 기록 ↗
            </a>
          </section>
        )}
        <footer className="bottom">
          MEETING EPISTEMIC GATEWAY{" "}
          <span>사람의 확신이 아닌, 주장의 근거를 봅니다.</span>
        </footer>
      </main>
    </div>
  );
}
createRoot(document.getElementById("root")!).render(<App />);

if (import.meta.env.PROD && "serviceWorker" in navigator)
  navigator.serviceWorker
    .register(import.meta.env.BASE_URL + "sw.js")
    .catch(() => {});
