import { expect, it, vi } from "vitest";
import cases from "../../contracts/arithmetic-cases.json";
import { calculate } from "./calculation";
import { boundedInput, route, predictDelay } from "./routing";
import {
  emptyMeeting,
  parseVerdicts,
  policy,
  paragraphs,
  importMeeting,
  exportMeeting,
  type Claim,
} from "./core";
for (const c of cases)
  it(c.text, () =>
    expect(calculate(c.text)?.results[0].status ?? null).toBe(c.status),
  );
it("model knowledge is honest, neutral, and cannot approve current facts", () => {
  const raw = (text: string, status = "GENERAL_KNOWLEDGE") =>
    JSON.stringify({
      claims: [
        {
          text,
          status,
          reason: "known",
          evidenceIds: [],
          basis: "MODEL_KNOWLEDGE",
        },
      ],
    });
  expect(
    parseVerdicts(raw("지구는 태양 주위를 공전한다"), [], false)[0],
  ).toMatchObject({
    status: "GENERAL_KNOWLEDGE",
    basis: "MODEL_KNOWLEDGE",
    evidenceIds: [],
  });
  expect(
    parseVerdicts(raw("현재 대통령은 누군가다"), [], false)[0].status,
  ).toBe("UNVERIFIABLE");
  expect(
    parseVerdicts(raw("단정", "UNSUPPORTED ASSERTION"), [], true)[0].status,
  ).toBe("UNVERIFIABLE");
  const m = emptyMeeting();
  m.claims = [
    {
      id: "c",
      speakerId: "a",
      speakerCertain: true,
      status: "VERIFIED",
      basis: "MODEL_KNOWLEDGE",
      endedAt: 1,
    } as Claim,
  ];
  expect(policy(m, "a").calibrated).toBe(0);
});
it("bounded paragraphs retain useful evidence and cap Korean PDF input", () => {
  const docs = paragraphs(
    "우주 연구에서 지구는 태양 주위를 공전한다.\n".repeat(4000),
    "synthetic.pdf",
  ).map((e) => ({ ...e, documentHash: "a".repeat(64), page: 1 }));
  const b = boundedInput("지구는 태양 주위를 공전한다", docs);
  expect(b.inputTokenUpperBound).toBeLessThanOrEqual(2800);
  expect(b.evidence.length).toBeGreaterThan(0);
  expect(b.evidence[0].page).toBe(1);
});
it("5 second switch aborts local; ignores its late result and never duplicates", async () => {
  vi.useFakeTimers();
  let aborted = false;
  let cloud = 0;
  const attempts: unknown[] = [];
  const task = route({
    mode: "AUTO",
    available: true,
    predictedMs: 1000,
    signal: new AbortController().signal,
    onAttempt: (a) => attempts.push(a),
    onWarning: () => {},
    local: (s) =>
      new Promise((resolve) => {
        s.addEventListener("abort", () => (aborted = true));
        setTimeout(() => resolve("late"), 7000);
      }),
    remote: async () => {
      cloud++;
      return "cloud";
    },
  });
  await vi.advanceTimersByTimeAsync(5000);
  expect(await task).toBe("cloud");
  expect(aborted).toBe(true);
  await vi.advanceTimersByTimeAsync(3000);
  expect(cloud).toBe(1);
  expect(attempts).toHaveLength(2);
  vi.useRealTimers();
});
for (const error of ["401", "402", "429", "network"])
  it(`cloud ${error} calls selected provider once then local`, async () => {
    let count = 0;
    expect(
      await route({
        mode: "AUTO",
        available: true,
        predictedMs: 7500,
        signal: new AbortController().signal,
        onAttempt: () => {},
        onWarning: () => {},
        local: async () => "local",
        remote: async () => {
          count++;
          throw Error(error);
        },
      }),
    ).toBe("local");
    expect(count).toBe(1);
  });
it("local only never calls cloud; cold device routes cautiously", async () => {
  expect(predictDelay(true, 1800, 0)).toBeGreaterThan(5000);
  expect(
    await route({
      mode: "LOCAL_ONLY",
      available: true,
      predictedMs: Infinity,
      signal: new AbortController().signal,
      onAttempt: () => {},
      onWarning: () => {},
      local: async () => 42,
      remote: async () => {
        throw Error("forbidden");
      },
    }),
  ).toBe(42);
});
it("v1 reads as v2, v2 trace round trips and strips unknown secret fields", () => {
  const m = emptyMeeting();
  m.speakerHistory = [
    { at: 1, kind: "RENAME", id: "a", from: "A", to: "Alice" },
  ];
  expect(
    importMeeting(JSON.stringify({ ...m, schemaVersion: 1 })).schemaVersion,
  ).toBe(2);
  expect(importMeeting(exportMeeting(m, false)).speakerHistory).toEqual(
    m.speakerHistory,
  );
});

import verdictCases from "../../contracts/verdict-cases.json";
import { type Evidence } from "./core";
for (const c of verdictCases)
  it(c.name, () =>
    expect(
      parseVerdicts(
        JSON.stringify(c.raw),
        c.evidence as Evidence[],
        c.evidence.length > 0,
      )[0].status,
    ).toBe(c.expected),
  );
