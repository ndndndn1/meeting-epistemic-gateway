import {
  getDocument,
  GlobalWorkerOptions,
} from "pdfjs-dist/legacy/build/pdf.mjs";
import worker from "pdfjs-dist/legacy/build/pdf.worker.min.mjs?url";
import { indexEvidence, paragraphs, type Evidence } from "./core";
GlobalWorkerOptions.workerSrc = worker;
export async function readDocument(file: File): Promise<Evidence[]> {
  if (file.size > 25_000_000) throw Error("자료는 파일당 25MB까지 지원합니다.");
  const data = await file.arrayBuffer();
  const hash = Array.from(
    new Uint8Array(await crypto.subtle.digest("SHA-256", data)),
    (v) => v.toString(16).padStart(2, "0"),
  ).join("");
  const stamp = (docs: Evidence[]) => {
    const result = docs.map((e) => ({
      ...e,
      documentHash: hash,
      version: `SHA-256 ${hash}`,
    }));
    indexEvidence(result);
    return result;
  };
  if (file.name.toLowerCase().endsWith(".pdf")) {
    const pdf = await getDocument({
      data: data.slice(0),
      isEvalSupported: false,
    }).promise;
    try {
      if (pdf.numPages > 200) throw Error("PDF는 200페이지까지 지원합니다.");
      const docs: Evidence[] = [];
      for (let i = 1; i <= pdf.numPages; i++) {
        const page = await pdf.getPage(i),
          content = await page.getTextContent();
        const text = content.items
          .map((x) =>
            "str" in x ? x.str + ("hasEOL" in x && x.hasEOL ? "\n" : " ") : "",
          )
          .join("");
        docs.push(...paragraphs(text, file.name, file.name, "사용자 제공", i));
      }
      if (!docs.length)
        throw Error("텍스트가 없는 PDF입니다. OCR은 지원하지 않습니다.");
      return stamp(docs);
    } finally {
      await pdf.destroy();
    }
  }
  if (!/\.(txt|md|markdown)$/i.test(file.name))
    throw Error("PDF·TXT·Markdown 파일을 선택하세요.");
  return stamp(paragraphs(new TextDecoder().decode(data), file.name));
}
