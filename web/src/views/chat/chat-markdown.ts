import createDOMPurify from "dompurify";
import { marked } from "marked";

const purifier = createDOMPurify(window);
const INLINE_SOURCE_PATTERN = /[【[]\s*来源\s*[:：][^】\]\r\n]+[】\]]/gu;

export function answerBody(content: string): string {
  return content.replace(INLINE_SOURCE_PATTERN, "").replace(/[ \t]+\n/gu, "\n").trim();
}

export function renderAnswerMarkdown(content: string): string {
  const html = marked.parse(answerBody(content), { async: false, breaks: true, gfm: true });
  return purifier.sanitize(html, {
    FORBID_TAGS: ["script", "style", "iframe", "object", "embed", "form", "img"],
    FORBID_ATTR: ["style"]
  });
}
