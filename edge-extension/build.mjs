// Bundles src/ into dist/ and copies static/ (manifest, HTML, CSS, icons)
// alongside it. Load dist/ in edge://extensions with "Load unpacked".
import * as esbuild from "esbuild";
import { cp, rm } from "node:fs/promises";

const watch = process.argv.includes("--watch");

await rm("dist", { recursive: true, force: true });
await cp("static", "dist", { recursive: true });

const options = {
  entryPoints: ["src/background.ts", "src/sidepanel.ts", "src/options.ts"],
  outdir: "dist",
  bundle: true,
  // The side panel and settings page share one copy of the SDK.
  splitting: true,
  format: "esm",
  platform: "browser",
  target: "chrome116",
  // Left readable on purpose: the Edge Add-ons review reads the code.
  minify: false,
  sourcemap: watch ? "inline" : false,
  logLevel: "info",
};

if (watch) {
  const ctx = await esbuild.context(options);
  await ctx.watch();
} else {
  await esbuild.build(options);
}
