import { describe, it, expect } from "vitest";
import cases from "../../contracts/policy-cases.json";
import {
  transition,
  normal,
  policy,
  emptyMeeting,
  parseVerdicts,
  intervention,
  revise,
  exportMeeting,
  importMeeting,
  type Claim,
  type Status,
} from "./core";
const claim = (id: string, status: Status): Claim => ({
  id,
  utteranceId: id,
  text: "주장",
  speakerId: "A",
  speakerCertain: true,
  status,
  reason: "",
  evidenceIds: [],
  endedAt: Date.now(),
  checkedAt: Date.now(),
  revisions: [],
  spoken: false,
});
describe("cross-platform policy", () => {
  for (const c of cases)
    it(c.name, () =>
      expect(
        c.statuses.reduce((s, x) => transition(s, x as Status), normal()),
      ).toEqual({
        mode: c.mode,
        unsupported: c.unsupported,
        calibrated: c.calibrated,
      }),
    );
});
it("replays corrections, duplicate events and uncertain attribution", () => {
  const m = emptyMeeting();
  m.claims = [
    claim("1", "UNSUPPORTED ASSERTION"),
    claim("2", "UNSUPPORTED ASSERTION"),
    claim("3", "UNSUPPORTED ASSERTION"),
  ];
  expect(policy(m, "A").mode).toBe("EVIDENCE REQUIRED");
  m.claims[1] = revise(m.claims[1], "VERIFIED", "정정");
  expect(policy(m, "A").mode).toBe("NORMAL");
  m.claims[1] = {
    ...claim("2", "UNSUPPORTED ASSERTION"),
    speakerCertain: false,
  };
  m.claims.push(m.claims[0]);
  expect(policy(m, "A").unsupported).toBe(2);
});
it("rejects hallucinated citations and no-scope accusations", () => {
  for (const status of ["VERIFIED", "CONTRADICTED", "UNSUPPORTED ASSERTION"])
    expect(
      parseVerdicts(
        JSON.stringify({
          claims: [{ text: "x", status, reason: "x", evidenceIds: ["fake"] }],
        }),
        [],
        false,
      )[0].status,
    ).toBe("UNVERIFIABLE");
});
it("expires and cancels speech", () => {
  const c = claim("1", "CONTRADICTED");
  expect(intervention(c, c.endedAt + 30001)).toBeNull();
  expect(intervention({ ...c, spoken: true })).toBeNull();
  expect(intervention({ ...c, speakerCertain: false })).toBeNull();
  expect(intervention(c)).toContain("잠시 사실관계");
});
it("exports opt-in profiles and excludes unknown credentials on import", () => {
  const m = emptyMeeting();
  m.speakers = [{ id: "A", name: "A", embedding: [1, 2] }];
  expect(exportMeeting(m, false)).not.toContain("embedding");
  expect(
    importMeeting(JSON.stringify({ ...m, key: "secret" })),
  ).not.toHaveProperty("key");
  expect(() => importMeeting('{"schemaVersion":9}')).toThrow();
});
