import Anthropic from "@anthropic-ai/sdk";
import { EFFORTS, MODELS, loadSettings, saveSettings, type Effort } from "./settings";

const $ = <T extends HTMLElement = HTMLElement>(id: string) => document.getElementById(id) as T;

const form = $<HTMLFormElement>("form");
const apiKey = $<HTMLInputElement>("api-key");
const toggleKey = $<HTMLButtonElement>("toggle-key");
const model = $<HTMLSelectElement>("model");
const effort = $<HTMLSelectElement>("effort");
const status = $("status");

const EFFORT_LABELS: Record<Effort, string> = {
  low: "Low (quickest)",
  medium: "Medium (default)",
  high: "High",
  xhigh: "Extra high",
  max: "Max (slowest, most thorough)",
};

for (const m of MODELS) model.add(new Option(m.label, m.id));
for (const e of EFFORTS) effort.add(new Option(EFFORT_LABELS[e], e));

function setStatus(text: string, kind: "ok" | "error" | "" = "") {
  status.textContent = text;
  status.className = kind;
}

toggleKey.addEventListener("click", () => {
  const hidden = apiKey.type === "password";
  apiKey.type = hidden ? "text" : "password";
  toggleKey.textContent = hidden ? "Hide" : "Show";
});

form.addEventListener("submit", async (e) => {
  e.preventDefault();
  const key = apiKey.value.trim();
  await saveSettings({ apiKey: key, model: model.value, effort: effort.value as Effort });
  if (!key) {
    setStatus("Saved. Add a key to start chatting.");
    return;
  }

  // Looking up the model is free, and checks both the key and model access.
  setStatus("Saved. Checking the key…");
  const client = new Anthropic({ apiKey: key, dangerouslyAllowBrowser: true });
  try {
    await client.models.retrieve(model.value);
    setStatus("Saved. The key works.", "ok");
  } catch (err) {
    if (err instanceof Anthropic.AuthenticationError) {
      setStatus("Saved, but the API rejected this key.", "error");
    } else if (err instanceof Anthropic.NotFoundError || err instanceof Anthropic.PermissionDeniedError) {
      setStatus("Saved, but this key can't use that model.", "error");
    } else if (err instanceof Anthropic.APIConnectionError) {
      setStatus("Saved, but couldn't reach the Anthropic API to check the key.", "error");
    } else {
      setStatus(`Saved, but checking the key failed: ${(err as Error).message}`, "error");
    }
  }
});

loadSettings().then((s) => {
  apiKey.value = s.apiKey;
  model.value = s.model;
  effort.value = s.effort;
  if (!s.apiKey) apiKey.focus();
});
