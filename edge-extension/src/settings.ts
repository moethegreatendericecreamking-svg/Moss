export interface ModelInfo {
  id: string;
  label: string;
  // Whether the API can re-run a declined request on another model
  // (`fallbacks: "default"`). Haiku has no server-side fallback.
  serverFallback: boolean;
}

export const MODELS: ModelInfo[] = [
  { id: "claude-opus-5-5", label: "Claude Opus 5.5 (recommended)", serverFallback: true },
  { id: "claude-sonnet-5-5", label: "Claude Sonnet 5.5 (faster, cheaper)", serverFallback: true },
  { id: "claude-haiku-5-5", label: "Claude Haiku 5.5 (fastest, cheapest)", serverFallback: false },
  { id: "claude-fable-5-1", label: "Claude Fable 5.1 (most capable, priciest)", serverFallback: true },
];

export const EFFORTS = ["low", "medium", "high", "xhigh", "max"] as const;
export type Effort = (typeof EFFORTS)[number];

export interface Settings {
  apiKey: string;
  model: string;
  effort: Effort;
}

export const DEFAULTS: Settings = {
  apiKey: "",
  model: "claude-opus-5-5",
  effort: "medium",
};

export function modelInfo(id: string): ModelInfo {
  return MODELS.find((m) => m.id === id) ?? MODELS[0];
}

// chrome.storage.local, not sync: the API key should stay on this device.
export async function loadSettings(): Promise<Settings> {
  return (await chrome.storage.local.get(DEFAULTS)) as Settings;
}

export async function saveSettings(patch: Partial<Settings>): Promise<void> {
  await chrome.storage.local.set(patch);
}
