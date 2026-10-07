# Side Panel Chat for Claude (Microsoft Edge)

A Microsoft Edge extension that puts a Claude chat in Edge's side panel. Claude can read the page
you're on, so you can ask it to summarize an article, explain a passage, or answer questions about
what you're looking at.

It's unofficial and not made by Anthropic. It talks to Claude through the
[Anthropic API](https://platform.claude.com/docs) with your own API key, and usage is billed to your
Anthropic account.

- **Side panel chat**: click the toolbar button, or press <kbd>Alt</kbd>+<kbd>Shift</kbd>+<kbd>C</kbd>.
  Replies stream in as Markdown, and you can expand Claude's thought process.
- **Ask about the page**: click the paperclip to attach the current page, including any text you
  have selected. Or use the shortcuts on the start screen: *Summarize this page*,
  *Key takeaways*, *Explain my selection*.
- **Right-click menu**: select text and choose **Ask Claude about "…"**, or right-click anywhere on a
  page and choose **Summarize this page with Claude**.
- **Settings**: choose the model (Claude Opus 5.5 by default) and how much effort Claude puts in.

## Install

1. Get the extension, either:
   - **Prebuilt**: open this repo's **Actions** tab → the latest **Build Edge extension** run →
     download the `claude-edge-extension` artifact and unzip it to a folder you'll keep, or
   - **Build it yourself** (needs [Node.js](https://nodejs.org) 20+):
     ```sh
     cd edge-extension
     npm ci
     npm run build      # output: edge-extension/dist
     ```
2. In Edge, go to `edge://extensions`, turn on **Developer mode** (left sidebar), click
   **Load unpacked**, and pick the unzipped folder (or `edge-extension/dist`).
3. The settings page opens. Paste an API key from
   [console.anthropic.com](https://console.anthropic.com/settings/keys) and click **Save**. It checks
   that the key works.
4. Pin the extension to the toolbar (puzzle-piece menu → eye icon), then click it to open the panel.

To update, replace the folder's contents with a newer build and click **Reload** on the extension's
card in `edge://extensions`.

## Privacy and permissions

| Permission | Why |
|---|---|
| `api.anthropic.com` | Sends your messages to Claude. Nothing goes anywhere else. |
| `storage` | Keeps your API key and settings in this browser profile (not synced). |
| `sidePanel`, `contextMenus` | The side panel and the right-click menu items. |
| `activeTab`, `scripting` | Reads the page's text when you use the right-click menu. |
| All sites (optional) | Asked for the first time you click the paperclip, so the panel can read whichever tab you're on. |

Pages are only read when you attach one or use the right-click menu. Conversations live only in the
open panel; closing it or clicking **New chat** clears them. Very long pages are cut at 200,000
characters (about 50k tokens), and the panel tells you when that happens.

Replies are rendered as text formatting only. Images, scripts and other HTML in a reply are
stripped, so a malicious page can't make Claude's answer load a URL that leaks your conversation.

## How it calls Claude

The side panel uses the official [`@anthropic-ai/sdk`](https://www.npmjs.com/package/@anthropic-ai/sdk)
and streams responses from the Messages API:

- Adaptive thinking with summarized thoughts, and the effort level from settings
  (`output_config.effort`, Medium by default).
- Automatic prompt caching, so follow-up questions about a long page cost less.
- **Refusal fallback**: if the chosen model declines a request on safety grounds, the API retries it
  on Anthropic's recommended fallback model (`fallbacks: "default"`), and the reply says which model
  answered. Claude Haiku 5.5 has no server-side fallback, so it's not sent there.
- The conversation is only ever appended to, as the API expects when Claude reads back its own
  earlier thinking. A declined or failed turn isn't added.

## Troubleshooting

- **"Claude can't read this page"**: extensions can't read `edge://` pages, the Edge Add-ons store,
  or Edge's built-in PDF viewer.
- **The paperclip says it needs permission**: allow site access when Edge asks, or open
  `edge://extensions` → **Details** on this extension → **Site access** → **On all sites**.
- **"Your API key was rejected"**: re-paste the key in settings. A low credit balance shows up as
  a rejected request with the API's message.

## Development

```sh
npm ci
npm run watch       # rebuilds dist/ on change; click Reload in edge://extensions
npm run typecheck
```

- `src/sidepanel.ts`: chat UI and the Claude API calls
- `src/background.ts`: toolbar button and right-click menu
- `src/page.ts`: reads a tab's text and formats it for Claude
- `src/options.ts`, `src/settings.ts`: settings page and storage
- `static/`: manifest, HTML, CSS and icons, copied into `dist/` as they are

To publish on the [Edge Add-ons store](https://partner.microsoft.com/dashboard/microsoftedge),
zip the *contents* of `dist/` and upload the zip in Partner Center. Keep the name and description
clear that the extension is unofficial.
