import assert from "node:assert/strict";
import { createServer } from "node:http";
import { mkdtemp, readFile, rm } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { after, before, describe, test } from "node:test";
import { fileURLToPath } from "node:url";
import sharp from "sharp";
import {
  ConfigError,
  IconError,
  analyse,
  androidString,
  applyConfig,
  checkConfig,
  fetchIcon,
  isPrivateHost,
  logoBackground,
  masterFrom,
  planIcon,
  unwrapIco,
} from "../apply-config.mjs";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const sample = JSON.parse(await readFile(path.join(HERE, "../samples/wikipedia-tabs.json"), "utf8"));
const clone = () => structuredClone(sample);

/** A solid square PNG, optionally with transparent corners or a transparent ground. */
async function png({ size = 256, color = "#1d3557", ground = null, round = 0 } = {}) {
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="${size}" height="${size}">
${ground ? `<rect width="${size}" height="${size}" fill="${ground}"/>` : ""}
<rect x="${ground ? size * 0.3 : 0}" y="${ground ? size * 0.3 : 0}" width="${ground ? size * 0.4 : size}" height="${ground ? size * 0.4 : size}" rx="${round}" fill="${color}"/>
</svg>`;
  return sharp(Buffer.from(svg)).png().toBuffer();
}

async function logoPng(color = "#ffffff", size = 256) {
  // A disc on a transparent ground.
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="${size}" height="${size}"><circle cx="${size / 2}" cy="${size / 2}" r="${size / 3}" fill="${color}"/></svg>`;
  return sharp(Buffer.from(svg)).png().toBuffer();
}

function ico(entries) {
  const header = Buffer.alloc(6 + entries.length * 16);
  header.writeUInt16LE(0, 0);
  header.writeUInt16LE(1, 2);
  header.writeUInt16LE(entries.length, 4);
  let offset = header.length;
  entries.forEach(({ width, data }, index) => {
    const at = 6 + index * 16;
    header[at] = width >= 256 ? 0 : width;
    header[at + 1] = width >= 256 ? 0 : width;
    header.writeUInt32LE(data.length, at + 8);
    header.writeUInt32LE(offset, at + 12);
    offset += data.length;
  });
  return Buffer.concat([header, ...entries.map((entry) => entry.data)]);
}

describe("checkConfig", () => {
  test("accepts the samples", () => {
    assert.deepEqual(checkConfig(sample), []);
  });

  test("rejects values that would break or bend a build file", () => {
    const cases = [
      [(c) => (c.app.androidPackage = "com.example\napplicationId=evil"), "app.androidPackage"],
      [(c) => (c.app.androidPackage = "Com.Example.App"), "app.androidPackage"],
      [(c) => (c.app.androidPackage = "example"), "app.androidPackage"],
      [(c) => (c.app.versionName = "1.0"), "app.versionName"],
      [(c) => (c.app.launcherName = "Shop\u0007"), "app.launcherName"],
      [(c) => (c.app.launcherName = "   "), "app.launcherName"],
      [(c) => (c.app.name = "x".repeat(31)), "app.name"],
      [(c) => (c.app.startUrl = "http://en.m.wikipedia.org/"), "app.startUrl"],
      [(c) => (c.branding.themeColor = "#FFF"), "branding.themeColor"],
      [(c) => (c.branding.themeColor = "#ffffff</color>"), "branding.themeColor"],
      [(c) => (c.branding.iconUrl = "javascript:alert(1)"), "branding.iconUrl"],
      [(c) => (c.navigation.internalHosts = []), "navigation.internalHosts"],
      [(c) => (c.navigation.internalHosts = ["https://example.com"]), "navigation.internalHosts"],
      [(c) => c.navigation.tabs.push(...c.navigation.tabs), "navigation.tabs"],
      [(c) => (c.navigation.tabs[0].url = "http://example.com/"), "navigation.tabs.0"],
      [(c) => (c.offline.message = ""), "offline.message"],
      [(c) => (c.webview.userAgentSuffix = "App\"; rm -rf"), "webview.userAgentSuffix"],
      [(c) => (c.webview.injectJs = "x".repeat(20_001)), "webview.injectJs"],
      [(c) => (c.webview.location = "yes"), "webview.location"],
      [(c) => (c.schemaVersion = 2), "schemaVersion"],
    ];
    for (const [mutate, field] of cases) {
      const config = clone();
      mutate(config);
      const problems = checkConfig(config);
      assert.ok(problems.some((problem) => problem.startsWith(`${field}:`)), `${field} should be rejected, got ${JSON.stringify(problems)}`);
    }
  });

  test("names a missing section instead of throwing", () => {
    const config = clone();
    delete config.webview;
    assert.deepEqual(checkConfig(config), ["webview: is missing"]);
    assert.deepEqual(checkConfig(null), ["The config is not a JSON object."]);
  });
});

describe("androidString", () => {
  test("escapes what aapt and XML treat specially", () => {
    assert.equal(androidString(`Joe's "Best" <Shop> & Co \\ 100%`), `Joe\\'s \\"Best\\" &lt;Shop&gt; &amp; Co \\\\ 100%`);
  });

  test("keeps a leading @ or ? from reading as a reference", () => {
    assert.equal(androidString("@home"), "\\@home");
    assert.equal(androidString("?shop"), "\\?shop");
    assert.equal(androidString("Shop @ home"), "Shop @ home");
  });
});

describe("isPrivateHost", () => {
  test("blocks loopback, private, link-local and metadata addresses", () => {
    for (const host of ["localhost", "a.localhost", "127.0.0.1", "10.1.2.3", "172.16.0.1", "192.168.1.1", "169.254.169.254", "100.64.0.1", "[::1]", "fd00::1", "fe80::1", "metadata.google.internal", "0.0.0.0"]) {
      assert.ok(isPrivateHost(host), host);
    }
  });

  test("allows public hosts", () => {
    for (const host of ["en.wikipedia.org", "8.8.8.8", "172.32.0.1", "[2606:4700::1111]"]) assert.ok(!isPrivateHost(host), host);
  });
});

describe("icons", () => {
  test("unwrapIco returns the largest PNG entry", async () => {
    const small = await png({ size: 32 });
    const large = await png({ size: 256 });
    const found = unwrapIco(ico([{ width: 32, data: small }, { width: 256, data: large }]));
    assert.ok(found.equals(large));
    assert.equal(unwrapIco(await png()), null);
  });

  test("a square icon with opaque corners fills the visible area on its own corner colour", async () => {
    const plan = await planIcon(clone(), { fetcher: async () => png({ color: "#1d3557" }) });
    assert.equal(plan.source, "site");
    assert.equal(plan.shape, "square");
    assert.equal(plan.background, "#1d3557");
  });

  test("a tile with rounded corners is drawn larger so the corners fall outside the mask", async () => {
    const plan = await planIcon(clone(), { fetcher: async () => png({ color: "#0d0c0b", round: 60 }) });
    assert.equal(plan.shape, "tile");
    assert.equal(plan.background, "#0d0c0b");
  });

  test("a logo on a transparent ground sits on a colour it stands out on", async () => {
    const config = clone(); // background #ffffff, theme #eaecf0
    const white = await planIcon(config, { fetcher: async () => logoPng("#ffffff") });
    assert.equal(white.shape, "logo");
    assert.equal(white.background, "#1a1a1a", "a white logo cannot sit on white or pale grey");
    const navy = await planIcon(config, { fetcher: async () => logoPng("#1d3557") });
    assert.equal(navy.background, "#ffffff");
  });

  test("logoBackground prefers the app's colours", () => {
    assert.equal(logoBackground("#000000", { backgroundColor: "#ffffff", themeColor: "#ff0000" }), "#ffffff");
    assert.equal(logoBackground("#ffffff", { backgroundColor: "#ffffff", themeColor: "#1d3557" }), "#1d3557");
  });

  test("an SVG icon is drawn at full size, not enlarged from 72 dpi", async () => {
    const svg = Buffer.from('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24"><rect width="24" height="24" fill="#2a9d8f"/></svg>');
    const master = await masterFrom(svg);
    assert.equal(analyse(master).shape, "square");
  });

  test("falls back to a letter icon, with the reason, when the artwork cannot be used", async () => {
    const config = clone();
    const tiny = await planIcon(config, { fetcher: async () => png({ size: 48 }) });
    assert.equal(tiny.source, "letter");
    assert.match(tiny.reason, /only 48 px/);
    assert.equal(tiny.background, config.branding.themeColor);

    const junk = await planIcon(config, { fetcher: async () => Buffer.from("<html>not an image</html>") });
    assert.equal(junk.source, "letter");
    assert.match(junk.reason, /not an image/);

    const failed = await planIcon(config, {
      fetcher: async () => {
        throw new IconError("the icon address answered HTTP 404");
      },
    });
    assert.match(failed.reason, /HTTP 404/);

    config.branding.iconUrl = null;
    assert.match((await planIcon(config)).reason, /no icon/);
  });

  test("an unexpected error is not hidden behind a letter icon", async () => {
    await assert.rejects(planIcon(clone(), { fetcher: async () => { throw new TypeError("bug"); } }), TypeError);
  });
});

describe("fetchIcon", () => {
  let server;
  let base;
  before(async () => {
    const image = await png();
    server = createServer((request, response) => {
      // The client hangs up on purpose in the size test.
      response.on("error", () => {});
      if (request.url === "/icon.png") {
        response.writeHead(200, { "content-type": "image/png" });
        response.end(image);
      } else if (request.url === "/huge") {
        response.writeHead(200, { "content-type": "image/png" });
        const chunk = Buffer.alloc(1024 * 1024);
        let sent = 0;
        const pump = () => {
          while (sent < 6 && response.write(chunk)) sent++;
          if (sent < 6) response.once("drain", pump);
          else response.end();
        };
        pump();
      } else {
        response.writeHead(404);
        response.end();
      }
    });
    await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
    base = `http://127.0.0.1:${server.address().port}`;
  });
  after(() => {
    server.closeAllConnections();
    return new Promise((resolve) => server.close(resolve));
  });

  test("downloads an icon", async () => {
    const body = await fetchIcon(`${base}/icon.png`, { allowPrivate: true });
    assert.equal((await sharp(body).metadata()).format, "png");
  });

  test("refuses private addresses unless allowed", async () => {
    await assert.rejects(fetchIcon(`${base}/icon.png`), /private network/);
  });

  test("reports HTTP errors and oversized bodies as icon problems", async () => {
    await assert.rejects(fetchIcon(`${base}/missing`, { allowPrivate: true }), (error) => error instanceof IconError && /HTTP 404/.test(error.message));
    await assert.rejects(fetchIcon(`${base}/huge`, { allowPrivate: true }), (error) => error instanceof IconError && /5 MB/.test(error.message));
  });
});

describe("applyConfig", () => {
  let dir;
  before(async () => {
    dir = await mkdtemp(path.join(os.tmpdir(), "shell-apply-"));
  });
  after(() => rm(dir, { recursive: true, force: true }));

  test("writes every generated file", async () => {
    const config = clone();
    config.app.launcherName = `Joe's <Wiki>`;
    const summary = await applyConfig(config, { versionCode: 7, androidDir: dir, fetcher: async () => png({ color: "#ffffff" }) });
    assert.equal(summary.applicationId, "app.site2app.smoke.wikipedia");
    assert.equal(summary.versionCode, 7);
    assert.deepEqual(summary.warnings, []);

    const properties = await readFile(path.join(dir, "shell.properties"), "utf8");
    assert.match(properties, /^applicationId=app\.site2app\.smoke\.wikipedia$/m);
    assert.match(properties, /^versionCode=7$/m);

    const values = await readFile(path.join(dir, "app/src/main/res/values/shell.xml"), "utf8");
    assert.match(values, /<string name="app_name" translatable="false" formatted="false">Joe\\'s &lt;Wiki&gt;<\/string>/);
    assert.match(values, /<color name="shell_theme">#eaecf0<\/color>/);

    const asset = JSON.parse(await readFile(path.join(dir, "app/src/main/assets/app-config.json"), "utf8"));
    assert.equal(asset.app.launcherName, `Joe's <Wiki>`);

    const sizes = { mdpi: 108, hdpi: 162, xhdpi: 216, xxhdpi: 324, xxxhdpi: 432 };
    for (const [density, size] of Object.entries(sizes)) {
      const meta = await sharp(path.join(dir, `app/src/main/res/mipmap-${density}/ic_launcher_foreground.png`)).metadata();
      assert.equal(meta.width, size, density);
      assert.equal(meta.height, size, density);
    }
    const preview = await sharp(path.join(dir, "build/launcher-icon.png")).metadata();
    assert.equal(preview.width, 192);

    // A config from before the location setting declares nothing extra.
    assert.equal(summary.location, false);
    const manifest = await readFile(path.join(dir, "app/src/shell/AndroidManifest.xml"), "utf8");
    assert.doesNotMatch(manifest, /uses-permission/);
  });

  test("declares location only for an app whose config allows it", async () => {
    // Learned from nandos.co.za, whose order page finds the nearest restaurant.
    const config = clone();
    config.webview.location = true;
    const summary = await applyConfig(config, { androidDir: dir, fetcher: async () => png() });
    assert.equal(summary.location, true);
    const manifest = await readFile(path.join(dir, "app/src/shell/AndroidManifest.xml"), "utf8");
    assert.match(manifest, /android\.permission\.ACCESS_COARSE_LOCATION/);
    assert.match(manifest, /android\.permission\.ACCESS_FINE_LOCATION/);
    assert.match(manifest, /android\.hardware\.location\.gps" android:required="false"/);

    config.webview.location = false;
    await applyConfig(config, { androidDir: dir, fetcher: async () => png() });
    assert.doesNotMatch(await readFile(path.join(dir, "app/src/shell/AndroidManifest.xml"), "utf8"), /uses-permission/);
  });

  test("warns about an unknown tab icon and push it cannot build", async () => {
    const config = clone();
    config.navigation.tabs[0].icon = "rocket";
    config.push.enabled = true;
    const { warnings } = await applyConfig(config, { androidDir: dir, fetcher: async () => png() });
    assert.equal(warnings.length, 2);
    assert.match(warnings[0], /"rocket"/);
    assert.match(warnings[1], /Firebase/);
  });

  test("refuses an invalid config or version code before writing anything", async () => {
    const config = clone();
    config.app.androidPackage = "bad package";
    await assert.rejects(applyConfig(config, { androidDir: path.join(dir, "never") }), ConfigError);
    await assert.rejects(applyConfig(clone(), { versionCode: 0, androidDir: path.join(dir, "never") }), /versionCode/);
  });
});
