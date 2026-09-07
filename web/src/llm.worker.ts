import { WebWorkerMLCEngineHandler } from "@mlc-ai/web-llm";
import models from "../../contracts/mlc-models.json";
// WebLLM verifies its executable/config/tokenizers through SRI. Verify each
// pinned weight shard too before the runtime can persist or use it.
const originalFetch = globalThis.fetch.bind(globalThis);
globalThis.fetch = async (input, init) => {
  const url = input instanceof Request ? input.url : String(input);
  const record = models.find((m) => url.startsWith(m.model));
  const artifact = record?.files.find(
    (f) => url === record.model + f.name && f.sha256,
  );
  const response = await originalFetch(input, init);
  if (!artifact || !response.ok) return response;
  const bytes = await response.arrayBuffer();
  const hash = Array.from(
    new Uint8Array(await crypto.subtle.digest("SHA-256", bytes)),
    (n) => n.toString(16).padStart(2, "0"),
  ).join("");
  if (bytes.byteLength !== artifact.bytes || hash !== artifact.sha256)
    throw Error("언어 모델 파일 무결성 오류");
  return new Response(bytes, {
    status: response.status,
    statusText: response.statusText,
    headers: response.headers,
  });
};
const handler = new WebWorkerMLCEngineHandler();
self.onmessage = (event: MessageEvent) => handler.onmessage(event);
