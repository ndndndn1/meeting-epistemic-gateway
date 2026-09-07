import { getDocument, GlobalWorkerOptions } from "pdfjs-dist";
import worker from "pdfjs-dist/build/pdf.worker.min.mjs?url";
import { paragraphs, type Evidence } from "./core";
GlobalWorkerOptions.workerSrc = worker;
export async function readDocument(file: File): Promise<Evidence[]> {
  if (file.size > 25_000_000) throw Error("자료는 파일당 25MB까지 지원합니다.");
  if (file.name.toLowerCase().endsWith(".pdf")) {
    const pdf = await getDocument({
      data: await file.arrayBuffer(),
      isEvalSupported: false,
    }).promise;
    try {
      if (pdf.numPages > 200) throw Error("PDF는 200페이지까지 지원합니다.");
      const docs: Evidence[] = [];
      for (let i = 1; i <= pdf.numPages; i++) {
        const page = await pdf.getPage(i),
          content = await page.getTextContent();
        const text = content.items
          .map((x) => ("str" in x ? x.str : ""))
          .join(" ");
        docs.push(...paragraphs(text, file.name, file.name, "사용자 제공", i));
      }
      if (!docs.length)
        throw Error("텍스트가 없는 PDF입니다. OCR은 지원하지 않습니다.");
      return docs;
    } finally {
      await pdf.destroy();
    }
  }
  if (!/\.(txt|md|markdown)$/i.test(file.name))
    throw Error("PDF·TXT·Markdown 파일을 선택하세요.");
  return paragraphs(await file.text(), file.name);
}
