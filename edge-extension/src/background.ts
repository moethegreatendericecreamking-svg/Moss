import { readTab, type PageSnapshot, type PendingRequest } from "./page";

const MENU_EXPLAIN = "explain-selection";
const MENU_SUMMARIZE = "summarize-page";

// Clicking the toolbar button (or Alt+Shift+C) opens the side panel.
chrome.sidePanel.setPanelBehavior({ openPanelOnActionClick: true }).catch(console.error);

chrome.runtime.onInstalled.addListener(({ reason }) => {
  chrome.contextMenus.removeAll(() => {
    chrome.contextMenus.create({
      id: MENU_EXPLAIN,
      title: "Ask Claude about “%s”",
      contexts: ["selection"],
    });
    chrome.contextMenus.create({
      id: MENU_SUMMARIZE,
      title: "Summarize this page with Claude",
      contexts: ["page"],
    });
  });
  if (reason === chrome.runtime.OnInstalledReason.INSTALL) {
    chrome.runtime.openOptionsPage();
  }
});

chrome.contextMenus.onClicked.addListener((info, tab) => {
  if (tab?.id === undefined) return;
  // sidePanel.open() only works inside the click's user gesture, so it has
  // to run before anything is awaited.
  chrome.sidePanel.open({ windowId: tab.windowId }).catch(console.error);
  queueRequest(info, tab.id, tab.windowId).catch(console.error);
});

async function queueRequest(info: chrome.contextMenus.OnClickData, tabId: number, windowId: number) {
  // The context-menu click grants activeTab, so the page can be read without
  // any host permission.
  let page: PageSnapshot | null = null;
  let error: string | undefined;
  try {
    page = await readTab(tabId);
  } catch (e) {
    error = (e as Error).message;
  }

  let request: PendingRequest;
  if (info.menuItemId === MENU_SUMMARIZE) {
    request = { id: crypto.randomUUID(), windowId, prompt: "Summarize this page.", page, error };
  } else {
    const quoted = (info.selectionText ?? "").trim().replace(/^/gm, "> ");
    request = {
      id: crypto.randomUUID(),
      windowId,
      prompt: `Explain this passage from the page:\n\n${quoted}`,
      // The selection is already in the prompt; the page is there for context.
      page: page && { ...page, selection: "" },
    };
  }
  await chrome.storage.session.set({ pending: request });
}
