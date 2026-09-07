import { type Evidence, type Status, type Attempt } from "./core";
// Deliberately accepts only a complete, single arithmetic assertion; never evaluates code.
export function calculate(text: string) {
  const normalized = text
    .trim()
    .replace(/[。.!]$/, "")
    .replace(/더하기/g, "+")
    .replace(/빼기/g, "-")
    .replace(/곱하기|×/g, "*")
    .replace(/나누기|÷/g, "/");
  const n = "([+-]?\\d{1,12}(?:\\.\\d{1,8})?)";
  const m = normalized.match(
    new RegExp(
      `^${n}\\s*([+*/-])\\s*${n}\\s*(?:은|는|=|equals)\\s*${n}\\s*(?:입니다|이다|야|다)?$`,
      "i",
    ),
  );
  if (!m) return null;
  const rational = (s: string): [bigint, bigint] => {
    const [a, b = ""] = s.split(".");
    return [BigInt(a + b), 10n ** BigInt(b.length)];
  };
  const [a, b] = rational(m[1]),
    [c, d] = rational(m[3]),
    [e, f] = rational(m[4]);
  if (m[2] === "/" && c === 0n) return null;
  const [num, den] =
    m[2] === "+"
      ? [a * d + c * b, b * d]
      : m[2] === "-"
        ? [a * d - c * b, b * d]
        : m[2] === "*"
          ? [a * c, b * d]
          : [a * d, b * c];
  const valid = num * f === e * den;
  const answer =
    den === 1n || num % den === 0n ? String(num / den) : `${num}/${den}`;
  const equation = `${m[1]} ${m[2]} ${m[3]} = ${answer}`;
  const ev: Evidence = {
    id: crypto.randomUUID(),
    text: equation,
    title: "정확한 유리수 계산",
    source: "내장 계산기",
    version: "rational-v1",
    basis: "CALCULATION",
    checkedAt: Date.now(),
  };
  return {
    results: [
      {
        text,
        status: (valid ? "VERIFIED" : "CONTRADICTED") as Status,
        reason: equation,
        evidenceIds: [ev.id],
        basis: "CALCULATION" as const,
      },
    ],
    evidence: [ev],
    warning: "",
    attempts: [
      {
        route: "CALCULATION",
        reason: "결정적 산술 검증",
        startedAt: Date.now(),
        durationMs: 0,
        outcome: "completed",
      } as Attempt,
    ],
  };
}
