#!/usr/bin/env node
// One Site2App build, inside a Fly machine that is destroyed when this exits.
//
// Inputs, from the machine's environment (set by the Site2App site):
//   BUILD_ID      the build's id; names its files
//   CONFIG        the app config: JSON, gzipped, base64
//   VERSION_CODE  Android versionCode
//   UPLOADS       JSON { apk, icon, result }: presigned PUT URLs, one file each
// Secrets, from the Fly app: SHELL_KEYSTORE_B64, SHELL_KEYSTORE_PASSWORD,
//   SHELL_KEY_ALIAS, SHELL_KEY_PASSWORD (the shared test signing key).
//
// The machine holds no storage credentials: it can write exactly the three
// files it was given URLs for. result.json is always written, success or not,
// because it is how the site learns what happened.

import { spawn } from "node:child_process";
import { mkdir, readFile, writeFile } from "node:fs/promises";
import { gunzipSync } from "node:zlib";

const APP = "/app";
const WORK = "/tmp/build";
const started = Date.now();
const env = process.env;
const log = [];

function say(line) {
  const stamped = `${((Date.now() - started) / 1000).toFixed(1).padStart(6)}s ${line}`;
  console.log(stamped);
  log.push(stamped);
}

/** Runs a command, keeping its output for the log. Rejects with the last lines when it fails. */
function run(command, args, options) {
  return new Promise((resolve, reject) => {
    const child = spawn(command, args, { ...options, stdio: ["ignore", "pipe", "pipe"] });
    let output = "";
    const keep = (chunk) => {
      const text = chunk.toString();
      output += text;
      process.stdout.write(text);
    };
    child.stdout.on("data", keep);
    child.stderr.on("data", keep);
    child.on("error", reject);
    child.on("close", (code) => {
      log.push(...output.trimEnd().split("\n").slice(-40));
      if (code === 0) resolve(output);
      else reject(new Error(`${command} exited with ${code}`));
    });
  });
}

async function upload(url, body, contentType) {
  const response = await fetch(url, {
    method: "PUT",
    body,
    headers: { "x-content-type": contentType },
    signal: AbortSignal.timeout(120_000),
  });
  if (!response.ok) throw new Error(`upload refused: HTTP ${response.status} ${(await response.text()).slice(0, 200)}`);
}

const result = { ok: false, buildId: env.BUILD_ID ?? null, step: "start", message: null, apk: null, summary: null };
let uploads = null;

try {
  if (!/^[a-z0-9]{12,40}$/.test(env.BUILD_ID ?? "")) throw new Error("BUILD_ID is missing or malformed");
  if (!/^[1-9][0-9]{0,9}$/.test(env.VERSION_CODE ?? "")) throw new Error("VERSION_CODE is missing or malformed");
  uploads = JSON.parse(env.UPLOADS ?? "null");
  if (!uploads?.apk || !uploads?.icon || !uploads?.result) throw new Error("UPLOADS is missing");

  result.step = "config";
  say("applying the app config");
  await mkdir(WORK, { recursive: true });
  await writeFile(`${WORK}/app-config.json`, gunzipSync(Buffer.from(env.CONFIG ?? "", "base64")));
  const applied = await run("node", ["scripts/apply-config.mjs", `${WORK}/app-config.json`, "--version-code", env.VERSION_CODE], { cwd: APP });
  result.summary = JSON.parse(applied.slice(applied.indexOf("{")));

  result.step = "compile";
  say("compiling and signing");
  await writeFile(`${WORK}/test.jks`, Buffer.from(env.SHELL_KEYSTORE_B64 ?? "", "base64"));
  // Lint is CI's job: the code is the same in every customer build, only resources differ.
  await run("./gradlew", ["--offline", "--no-daemon", "--console=plain", ":app:assembleRelease", "-x", "lintVitalRelease"], {
    cwd: `${APP}/android`,
    env: { ...env, SHELL_KEYSTORE_FILE: `${WORK}/test.jks` },
  });

  result.step = "publish";
  say("uploading the app");
  const apk = await readFile(`${APP}/android/app/build/outputs/apk/release/app-release.apk`);
  await upload(uploads.apk, apk, "application/vnd.android.package-archive");
  await upload(uploads.icon, await readFile(`${APP}/android/build/launcher-icon.png`), "image/png");
  result.apk = { bytes: apk.length };
  result.ok = true;
  result.step = "done";
  say(`done, ${apk.length} bytes`);
} catch (error) {
  result.message = error.message;
  say(`failed at ${result.step}: ${error.message}`);
}

result.seconds = Math.round((Date.now() - started) / 1000);
result.log = log.slice(-80);
if (uploads?.result) {
  try {
    await upload(uploads.result, JSON.stringify(result, null, 2), "application/json");
  } catch (error) {
    console.error(`could not write result.json: ${error.message}`);
    process.exit(2);
  }
}
process.exit(result.ok ? 0 : 1);
