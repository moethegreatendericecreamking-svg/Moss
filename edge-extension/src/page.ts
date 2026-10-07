export interface PageSnapshot {
  title: string;
  url: string;
  text: string;
  selection: string;
  // True when the page text was longer than MAX_PAGE_CHARS and was cut.
  truncated: boolean;
}

// A request queued by the context menu for the side panel to send.
export interface PendingRequest {
  id: string;
  windowId: number;
  prompt: string;
  page: PageSnapshot | null;
  error?: string;
}

// About 50k tokens. Keeps a huge page from quietly costing a lot; the side
// panel tells the user when a page was cut.
export const MAX_PAGE_CHARS = 200_000;

// Runs inside the web page, so it can't use anything from this module's scope.
function snapshot(maxChars: number) {
  const text = (document.body?.innerText ?? "")
    .replace(/[ \t]+\n/g, "\n")
    .replace(/\n{3,}/g, "\n\n")
    .trim();
  return {
    title: document.title,
    url: location.href,
    text: text.slice(0, maxChars),
    selection: String(window.getSelection() ?? "").trim(),
    truncated: text.length > maxChars,
  };
}

export async function readTab(tabId: number): Promise<PageSnapshot> {
  let results;
  try {
    results = await chrome.scripting.executeScript({
      target: { tabId },
      func: snapshot,
      args: [MAX_PAGE_CHARS],
    });
  } catch {
    throw new Error(
      "Claude can't read this page. Browser pages (edge://), the Add-ons store and built-in PDF viewer are off limits to extensions.",
    );
  }
  const page = results[0]?.result;
  if (!page) throw new Error("Couldn't read this page.");
  return page;
}

function escapeAttr(value: string): string {
  return value.replace(/&/g, "&amp;").replace(/"/g, "&quot;").replace(/</g, "&lt;");
}

// The page as Claude sees it. A literal closing tag in the page text is
// defused so the page can't end its own <page> block early.
export function formatPage(page: PageSnapshot): string {
  const body = (s: string, tag: string) => s.replaceAll(`</${tag}>`, `<\\/${tag}>`);
  const truncated = page.truncated ? ' truncated="true"' : "";
  let out = `<page title="${escapeAttr(page.title)}" url="${escapeAttr(page.url)}"${truncated}>\n${body(page.text, "page")}\n</page>`;
  if (page.selection) {
    out += `\n\n<selection>\n${body(page.selection, "selection")}\n</selection>`;
  }
  return out;
}
