import manifest from "../../contracts/models.json";
export const speechAssets = manifest.assets.filter(
  (a) => !a.id.startsWith("llm-"),
);
export async function importModel(file: File): Promise<string> {
  if (file.size > 300_000_000)
    throw Error("음성 모델 파일은 300MB까지 지원합니다.");
  const bytes = await file.arrayBuffer();
  const hash = Array.from(
    new Uint8Array(await crypto.subtle.digest("SHA-256", bytes)),
    (n) => n.toString(16).padStart(2, "0"),
  ).join("");
  const asset = speechAssets.find((a) => a.sha256 === hash);
  if (!asset)
    throw Error("등록된 모델과 체크섬이 다릅니다. 공식 파일을 선택하세요.");
  const cache = await caches.open(
    asset.id.startsWith("tts-") ? "meg-tts-v1" : "meg-speech-v1",
  );
  const url =
    asset.id === "vad"
      ? new URL(
          "runtime/vad.onnx",
          new URL(import.meta.env.BASE_URL, location.origin),
        ).href
      : asset.url;
  await cache.put(url, new Response(bytes));
  return asset.id;
}
