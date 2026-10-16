#!/usr/bin/env node
// Applies one Site2App app config to the Android project, so the next Gradle
// build produces that customer's app. It writes generated files only:
//
//   android/shell.properties                         applicationId, versionName, versionCode
//   android/app/src/main/assets/app-config.json      the config, read by the app at launch
//   android/app/src/main/res/values/shell.xml        the app name and colours
//   android/app/src/main/res/mipmap-*/ic_launcher_foreground.png
//   android/build/launcher-icon.png                  a 192 px picture of the finished icon
//
// Usage: node scripts/apply-config.mjs <config.json> [--version-code N]
//
// The Site2App site validates the whole config before a build starts. This
// script still checks every value it writes into a build file, because those
// land in XML, a properties file and Gradle, where a stray character is a
// broken build or worse.

import { appendFile, mkdir, readFile, writeFile } from "node:fs/promises";
import net from "node:net";
import path from "node:path";
import { fileURLToPath } from "node:url";
import sharp from "sharp";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(HERE, "..");

export const DENSITIES = [
  ["mdpi", 1],
  ["hdpi", 1.5],
  ["xhdpi", 2],
  ["xxhdpi", 3],
  ["xxxhdpi", 4],
];

// Adaptive icon geometry in dp. The layer is 108 across; every launcher mask
// shows at most the middle 72. A square icon fills that 72; a tile with its
// own rounded corners is drawn at 84 so the corners fall outside every mask;
// a logo on a transparent background sits in a 56 box on a solid colour.
const LAYER_DP = 108;
const VISIBLE_DP = 72;
const TILE_DP = 84;
const LOGO_DP = 56;
const MASTER_PX = 512;
const TRANSPARENT = { r: 0, g: 0, b: 0, alpha: 0 };

/** Below this a bitmap icon is too small to scale up to a launcher icon. */
export const MIN_ICON_PX = 96;
const MAX_ICON_BYTES = 5 * 1024 * 1024;
const USER_AGENT = "Site2AppBuild/1 (+https://site2app-iota.vercel.app)";
const PNG_SIGNATURE = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);

const PACKAGE = /^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$/;
const VERSION_NAME = /^\d{1,4}\.\d{1,4}\.\d{1,4}$/;
const HEX = /^#[0-9a-f]{6}$/;
const HOST = /^(\*\.)?([a-z0-9_]([a-z0-9_-]{0,61}[a-z0-9_])?\.)+[a-z0-9-]{2,63}$/;
const USER_AGENT_SUFFIX = /^[A-Za-z0-9._/ -]{0,64}$/;
const ICON_NAME = /^[a-z0-9-]{1,40}$/;
const CONTROL = /[\x00-\x1f\x7f-\x9f]/;

const TAB_ICONS = JSON.parse(await readFile(path.join(ROOT, "icons/tab-icons.json"), "utf8"));
export const TAB_ICON_NAMES = new Set(Object.keys(TAB_ICONS.icons));

export class ConfigError extends Error {
  constructor(problems) {
    super(`The app config is not valid:\n${problems.map((problem) => `  - ${problem}`).join("\n")}`);
    this.problems = problems;
  }
}

/** A launcher icon that cannot be made from the site's artwork. The build goes on with a letter icon. */
export class IconError extends Error {}

const isObject = (value) => value !== null && typeof value === "object" && !Array.isArray(value);
const isText = (value, min, max) => typeof value === "string" && value.trim().length >= min && value.length <= max;
const isLine = (value, min, max) => isText(value, min, max) && !CONTROL.test(value);

function isUrl(value, protocols) {
  if (typeof value !== "string" || value.length > 2048) return false;
  try {
    return protocols.includes(new URL(value).protocol);
  } catch {
    return false;
  }
}

/** Every problem with the values this script and the app read. Empty means usable. */
export function checkConfig(config) {
  if (!isObject(config)) return ["The config is not a JSON object."];
  const problems = [];
  const check = (where, ok, message) => {
    if (!ok) problems.push(`${where}: ${message}`);
  };

  check("schemaVersion", config.schemaVersion === 1, "must be 1");
  const sections = ["app", "branding", "navigation", "push", "offline", "webview"];
  for (const key of sections) check(key, isObject(config[key]), "is missing");
  if (problems.length > 0) return problems;
  const { app, branding, navigation, push, offline, webview } = config;

  check("app.name", isLine(app.name, 1, 30), "1 to 30 characters on one line");
  check("app.launcherName", isLine(app.launcherName, 1, 30), "1 to 30 characters on one line");
  check("app.startUrl", isUrl(app.startUrl, ["https:"]), "must be an https address");
  check(
    "app.androidPackage",
    typeof app.androidPackage === "string" && app.androidPackage.length <= 255 && PACKAGE.test(app.androidPackage),
    "lower-case words joined by dots, such as com.example.app",
  );
  check("app.versionName", typeof app.versionName === "string" && VERSION_NAME.test(app.versionName), "three numbers such as 1.0.0");

  for (const key of ["themeColor", "backgroundColor", "splashBackgroundColor"]) {
    check(`branding.${key}`, typeof branding[key] === "string" && HEX.test(branding[key]), "a #rrggbb colour");
  }
  check("branding.statusBarContent", ["light", "dark"].includes(branding.statusBarContent), "light or dark");
  check("branding.iconUrl", branding.iconUrl === null || isUrl(branding.iconUrl, ["https:", "http:"]), "a web address, or null");

  const hosts = navigation.internalHosts;
  check(
    "navigation.internalHosts",
    Array.isArray(hosts) &&
      hosts.length >= 1 &&
      hosts.length <= 20 &&
      hosts.every((host) => typeof host === "string" && host.length <= 260 && HOST.test(host)),
    "1 to 20 hosts such as example.com or *.example.com",
  );
  check("navigation.externalLinks", ["in-app-browser", "system-browser"].includes(navigation.externalLinks), "in-app-browser or system-browser");
  check("navigation.pullToRefresh", typeof navigation.pullToRefresh === "boolean", "true or false");
  check("navigation.tabs", Array.isArray(navigation.tabs) && navigation.tabs.length <= 5, "a list of at most 5 tabs");
  if (Array.isArray(navigation.tabs)) {
    navigation.tabs.forEach((tab, index) => {
      check(
        `navigation.tabs.${index}`,
        isObject(tab) && isLine(tab.label, 1, 20) && typeof tab.icon === "string" && ICON_NAME.test(tab.icon) && isUrl(tab.url, ["https:"]),
        "a label of 1 to 20 characters, an icon name and an https address",
      );
    });
  }

  check("push.enabled", typeof push.enabled === "boolean", "true or false");
  check("push.prompt", ["after-first-page", "manual"].includes(push.prompt), "after-first-page or manual");
  check("offline.message", isText(offline.message, 1, 140), "1 to 140 characters");
  check(
    "webview.userAgentSuffix",
    typeof webview.userAgentSuffix === "string" && USER_AGENT_SUFFIX.test(webview.userAgentSuffix),
    "up to 64 letters, digits, spaces and . _ / -",
  );
  check("webview.injectCss", typeof webview.injectCss === "string" && webview.injectCss.length <= 20_000, "text up to 20,000 characters");
  check("webview.injectJs", typeof webview.injectJs === "string" && webview.injectJs.length <= 20_000, "text up to 20,000 characters");
  return problems;
}

/** Text for an Android string resource: aapt's escapes, then XML's. */
export function androidString(text) {
  let out = text
    .replace(/\\/g, "\\\\")
    .replace(/"/g, '\\"')
    .replace(/'/g, "\\'")
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;");
  // A leading @ or ? would make aapt read the name as a resource reference.
  if (out.startsWith("@") || out.startsWith("?")) out = `\\${out}`;
  return out;
}

function propertiesFile(config, versionCode) {
  return [
    "# Written by scripts/apply-config.mjs from the app config. Do not edit by hand.",
    `applicationId=${config.app.androidPackage}`,
    `versionName=${config.app.versionName}`,
    `versionCode=${versionCode}`,
    "",
  ].join("\n");
}

function valuesXml(config, iconBackground) {
  const { branding } = config;
  return `<?xml version="1.0" encoding="utf-8"?>
<!-- Written by scripts/apply-config.mjs from the app config. Do not edit by hand. -->
<resources>
    <string name="app_name" translatable="false" formatted="false">${androidString(config.app.launcherName)}</string>
    <color name="shell_theme">${branding.themeColor}</color>
    <color name="shell_background">${branding.backgroundColor}</color>
    <color name="shell_splash">${branding.splashBackgroundColor}</color>
    <color name="ic_launcher_background">${iconBackground}</color>
</resources>
`;
}

// Colour helpers, on "#rrggbb".

function toHex(r, g, b) {
  return `#${[r, g, b].map((value) => Math.round(value).toString(16).padStart(2, "0")).join("")}`;
}

function rgb(hex) {
  return [1, 3, 5].map((offset) => parseInt(hex.slice(offset, offset + 2), 16));
}

/** WCAG relative luminance. */
export function luminance(hex) {
  const [r, g, b] = rgb(hex).map((value) => {
    const channel = value / 255;
    return channel <= 0.03928 ? channel / 12.92 : ((channel + 0.055) / 1.055) ** 2.4;
  });
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}

export function contrast(a, b) {
  const [light, dark] = [luminance(a), luminance(b)].sort((x, y) => y - x);
  return (light + 0.05) / (dark + 0.05);
}

// Fetching the site's icon.

/** Hosts a build has no reason to fetch from: loopback, private ranges, link-local, the cloud metadata address. */
export function isPrivateHost(hostname) {
  const host = hostname.replace(/^\[|\]$/g, "").toLowerCase();
  if (host === "localhost" || /\.(localhost|local|internal)$/.test(host)) return true;
  if (net.isIPv4(host)) {
    const [a, b] = host.split(".").map(Number);
    return (
      a === 0 ||
      a === 10 ||
      a === 127 ||
      a >= 224 ||
      (a === 169 && b === 254) ||
      (a === 172 && b >= 16 && b <= 31) ||
      (a === 192 && b === 168) ||
      (a === 100 && b >= 64 && b <= 127)
    );
  }
  if (net.isIPv6(host)) {
    return host === "::" || host === "::1" || /^f[cd]/.test(host) || /^fe[89ab]/.test(host) || host.startsWith("::ffff:");
  }
  return false;
}

export async function fetchIcon(url, { allowPrivate = process.env.SITE2APP_ALLOW_PRIVATE_ICONS === "1" } = {}) {
  const target = new URL(url);
  if (!allowPrivate && isPrivateHost(target.hostname)) throw new IconError("the icon address is on a private network");
  let response;
  try {
    response = await fetch(target, {
      redirect: "follow",
      signal: AbortSignal.timeout(20_000),
      headers: { "user-agent": USER_AGENT, accept: "image/png,image/webp,image/svg+xml,image/*;q=0.8" },
    });
  } catch (error) {
    throw new IconError(`the icon could not be downloaded (${error.cause?.code ?? error.name})`);
  }
  if (!response.ok) throw new IconError(`the icon address answered HTTP ${response.status}`);
  if (Number(response.headers.get("content-length") ?? 0) > MAX_ICON_BYTES) throw new IconError("the icon is larger than 5 MB");
  const chunks = [];
  let size = 0;
  try {
    for await (const chunk of response.body) {
      size += chunk.length;
      if (size > MAX_ICON_BYTES) throw new IconError("the icon is larger than 5 MB");
      chunks.push(chunk);
    }
  } catch (error) {
    if (error instanceof IconError) throw error;
    throw new IconError(`the icon download broke off (${error.name})`);
  }
  return Buffer.concat(chunks);
}

/** The largest PNG inside a .ico file, or null when the buffer is not one or holds only bitmaps. */
export function unwrapIco(buffer) {
  if (buffer.length < 6 || buffer.readUInt16LE(0) !== 0 || buffer.readUInt16LE(2) !== 1) return null;
  const count = buffer.readUInt16LE(4);
  let best = null;
  for (let index = 0; index < count; index++) {
    const entry = 6 + index * 16;
    if (entry + 16 > buffer.length) break;
    const width = buffer[entry] || 256;
    const size = buffer.readUInt32LE(entry + 8);
    const offset = buffer.readUInt32LE(entry + 12);
    if (size < PNG_SIGNATURE.length || offset + size > buffer.length) continue;
    const data = buffer.subarray(offset, offset + size);
    if (!data.subarray(0, PNG_SIGNATURE.length).equals(PNG_SIGNATURE)) continue;
    if (!best || width > best.width) best = { width, data };
  }
  return best?.data ?? null;
}

async function rawSquare(input, options = {}) {
  const { data } = await sharp(input, { ...options, animated: false })
    .resize(MASTER_PX, MASTER_PX, { fit: "contain", background: TRANSPARENT })
    .ensureAlpha()
    .raw()
    .toBuffer({ resolveWithObject: true });
  return data;
}

/** Any image sharp reads (PNG, JPEG, WebP, GIF, AVIF, SVG; ICO with PNGs inside), as 512 px square RGBA. */
export async function masterFrom(buffer) {
  const input = unwrapIco(buffer) ?? buffer;
  let meta;
  try {
    meta = await sharp(input).metadata();
  } catch {
    throw new IconError("the icon is not an image this build can read");
  }
  const vector = meta.format === "svg";
  const longest = Math.max(meta.width ?? 0, meta.height ?? 0);
  if (!longest) throw new IconError("the icon has no size");
  if (!vector && longest < MIN_ICON_PX) {
    throw new IconError(`the icon is only ${longest} px, and ${MIN_ICON_PX} px is the least that scales up cleanly`);
  }
  // sharp draws SVG at 72 dpi by default; raise the density so it comes out
  // at the master size instead of being enlarged as a bitmap.
  const options = vector ? { density: Math.min(2400, Math.max(1, Math.ceil((72 * MASTER_PX) / longest))) } : {};
  try {
    return await rawSquare(input, options);
  } catch {
    throw new IconError("the icon is not an image this build can read");
  }
}

/**
 * Sorts artwork into three shapes by its edges. Opaque corners: a square
 * icon. Opaque edge middles with clear corners: a tile with rounded corners.
 * Anything else: a logo on a transparent background.
 */
export function analyse(master) {
  const at = (x, y) => (y * MASTER_PX + x) * 4;
  const inset = Math.round(MASTER_PX * 0.02);
  const far = MASTER_PX - 1 - inset;
  const middle = Math.round(MASTER_PX / 2);
  const corners = [
    [inset, inset],
    [far, inset],
    [inset, far],
    [far, far],
  ];
  const edges = [
    [middle, inset],
    [middle, far],
    [inset, middle],
    [far, middle],
  ];
  const opaque = (points) => points.every(([x, y]) => master[at(x, y) + 3] >= 250);
  const mean = (points) => {
    const sum = [0, 0, 0];
    for (const [x, y] of points) for (let channel = 0; channel < 3; channel++) sum[channel] += master[at(x, y) + channel];
    return toHex(...sum.map((value) => value / points.length));
  };

  let red = 0;
  let green = 0;
  let blue = 0;
  let weight = 0;
  for (let offset = 0; offset < master.length; offset += 4) {
    const alpha = master[offset + 3];
    if (alpha < 32) continue;
    red += master[offset] * alpha;
    green += master[offset + 1] * alpha;
    blue += master[offset + 2] * alpha;
    weight += alpha;
  }
  const average = weight > 0 ? toHex(red / weight, green / weight, blue / weight) : "#000000";

  if (opaque(corners)) return { shape: "square", background: mean(corners), average };
  if (opaque(edges)) return { shape: "tile", background: mean(edges), average };
  return { shape: "logo", background: null, average };
}

/** A solid colour a logo of this average colour stands out on, preferring the app's own colours. */
export function logoBackground(average, branding) {
  const candidates = [branding.backgroundColor, branding.themeColor, "#ffffff", "#1a1a1a"];
  return candidates.find((candidate) => contrast(candidate, average) >= 1.6) ?? "#ffffff";
}

function escapeXml(text) {
  return text.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");
}

/** The first letter of the app's name on its theme colour: the icon when the site has none worth using. */
export async function letterPlan(config) {
  const name = config.app.launcherName.trim();
  const first = [...new Intl.Segmenter(undefined, { granularity: "grapheme" }).segment(name)][0]?.segment ?? "A";
  const theme = config.branding.themeColor;
  const ink = contrast("#ffffff", theme) >= contrast("#1a1a1a", theme) ? "#ffffff" : "#1a1a1a";
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="${MASTER_PX}" height="${MASTER_PX}" viewBox="0 0 ${MASTER_PX} ${MASTER_PX}">
<text x="256" y="256" dy="0.36em" text-anchor="middle" font-family="DejaVu Sans, Liberation Sans, Arial, Helvetica, sans-serif" font-weight="700" font-size="330" fill="${ink}">${escapeXml(first.toLocaleUpperCase())}</text>
</svg>`;
  return { source: "letter", shape: "logo", master: await rawSquare(Buffer.from(svg)), boxDp: LOGO_DP, background: theme };
}

export async function planIcon(config, { fetcher = fetchIcon } = {}) {
  const { iconUrl } = config.branding;
  if (!iconUrl) return { ...(await letterPlan(config)), reason: "the config has no icon" };
  try {
    const master = await masterFrom(await fetcher(iconUrl));
    const look = analyse(master);
    if (look.shape === "square") return { source: "site", shape: "square", master, boxDp: VISIBLE_DP, background: look.background };
    if (look.shape === "tile") return { source: "site", shape: "tile", master, boxDp: TILE_DP, background: look.background };
    return { source: "site", shape: "logo", master, boxDp: LOGO_DP, background: logoBackground(look.average, config.branding) };
  } catch (error) {
    if (!(error instanceof IconError)) throw error;
    return { ...(await letterPlan(config)), reason: error.message };
  }
}

async function artwork(plan, sizePx) {
  return sharp(plan.master, { raw: { width: MASTER_PX, height: MASTER_PX, channels: 4 } })
    .resize(sizePx, sizePx, { fit: "contain", background: TRANSPARENT, kernel: "lanczos3" })
    .png()
    .toBuffer();
}

/** The adaptive icon's foreground layer at each density. */
export async function writeLauncherIcon(plan, resDir) {
  for (const [density, scale] of DENSITIES) {
    const layer = Math.round(LAYER_DP * scale);
    const art = await artwork(plan, Math.round(plan.boxDp * scale));
    const png = await sharp({ create: { width: layer, height: layer, channels: 4, background: TRANSPARENT } })
      .composite([{ input: art, gravity: "centre" }])
      .png({ compressionLevel: 9 })
      .toBuffer();
    await mkdir(path.join(resDir, `mipmap-${density}`), { recursive: true });
    await writeFile(path.join(resDir, `mipmap-${density}`, "ic_launcher_foreground.png"), png);
  }
}

/** The finished icon as a launcher shows it, under a rounded-square mask, 192 px. */
export async function previewIcon(plan, size = 192) {
  const pxPerDp = size / VISIBLE_DP;
  const layer = Math.round(LAYER_DP * pxPerDp);
  const [r, g, b] = rgb(plan.background);
  const full = await sharp({ create: { width: layer, height: layer, channels: 4, background: { r, g, b, alpha: 1 } } })
    .composite([{ input: await artwork(plan, Math.round(plan.boxDp * pxPerDp)), gravity: "centre" }])
    .png()
    .toBuffer();
  const offset = Math.round((layer - size) / 2);
  const radius = Math.round(size * 0.23);
  const mask = Buffer.from(
    `<svg xmlns="http://www.w3.org/2000/svg" width="${size}" height="${size}"><rect width="${size}" height="${size}" rx="${radius}" ry="${radius}"/></svg>`,
  );
  const cropped = await sharp(full).extract({ left: offset, top: offset, width: size, height: size }).png().toBuffer();
  return sharp(cropped).composite([{ input: mask, blend: "dest-in" }]).png().toBuffer();
}

/** Writes every generated file for [config] into [androidDir]. Returns what was applied, and warnings. */
export async function applyConfig(config, { versionCode = 1, androidDir = path.join(ROOT, "android"), fetcher } = {}) {
  const problems = checkConfig(config);
  if (!Number.isInteger(versionCode) || versionCode < 1 || versionCode > 2_100_000_000) {
    problems.push("versionCode: a whole number from 1 to 2100000000");
  }
  if (problems.length > 0) throw new ConfigError(problems);

  const warnings = [];
  for (const tab of config.navigation.tabs) {
    if (!TAB_ICON_NAMES.has(tab.icon)) warnings.push(`Tab "${tab.label}" asks for the icon "${tab.icon}", which the shell does not have, so it shows a dot.`);
  }
  if (config.push.enabled) warnings.push("Push is switched on in the config, but test builds leave it out: it needs the app's own Firebase project.");

  const plan = await planIcon(config, fetcher ? { fetcher } : {});
  if (plan.reason) warnings.push(`Launcher icon: ${plan.reason}, so the app uses a letter icon.`);

  const main = path.join(androidDir, "app/src/main");
  await writeFile(path.join(androidDir, "shell.properties"), propertiesFile(config, versionCode));
  await mkdir(path.join(main, "assets"), { recursive: true });
  await writeFile(path.join(main, "assets/app-config.json"), `${JSON.stringify(config, null, 2)}\n`);
  await mkdir(path.join(main, "res/values"), { recursive: true });
  await writeFile(path.join(main, "res/values/shell.xml"), valuesXml(config, plan.background));
  await writeLauncherIcon(plan, path.join(main, "res"));
  await mkdir(path.join(androidDir, "build"), { recursive: true });
  await writeFile(path.join(androidDir, "build/launcher-icon.png"), await previewIcon(plan));

  return {
    applicationId: config.app.androidPackage,
    label: config.app.launcherName,
    versionName: config.app.versionName,
    versionCode,
    icon: { source: plan.source, shape: plan.shape, background: plan.background },
    warnings,
  };
}

function isMain(moduleUrl) {
  if (!process.argv[1]) return false;
  const self = fileURLToPath(moduleUrl);
  const invoked = path.resolve(process.argv[1]);
  return process.platform === "win32" ? self.toLowerCase() === invoked.toLowerCase() : self === invoked;
}

async function main(argv) {
  let file;
  let versionCode = 1;
  for (let index = 0; index < argv.length; index++) {
    if (argv[index] === "--version-code") versionCode = Number(argv[++index]);
    else if (!file) file = argv[index];
    else file = null;
  }
  if (!file) {
    console.error("Usage: node scripts/apply-config.mjs <config.json> [--version-code N]");
    process.exit(2);
  }

  let config;
  try {
    config = JSON.parse(await readFile(file, "utf8"));
  } catch (error) {
    console.error(`Could not read ${file}: ${error.message}`);
    process.exit(1);
  }

  let summary;
  try {
    summary = await applyConfig(config, { versionCode });
  } catch (error) {
    if (!(error instanceof ConfigError)) throw error;
    console.error(error.message);
    process.exit(1);
  }

  console.log(JSON.stringify(summary, null, 2));
  if (process.env.GITHUB_STEP_SUMMARY) {
    const lines = [
      `### ${summary.label}`,
      "",
      `\`${summary.applicationId}\`, version ${summary.versionName} (${summary.versionCode})`,
      `Launcher icon: ${summary.icon.source === "site" ? `the site's artwork (${summary.icon.shape})` : "a letter"} on ${summary.icon.background}`,
      "",
      ...summary.warnings.map((warning) => `- ${warning}`),
      "",
    ];
    await appendFile(process.env.GITHUB_STEP_SUMMARY, lines.join("\n"));
  }
}

if (isMain(import.meta.url)) await main(process.argv.slice(2));
