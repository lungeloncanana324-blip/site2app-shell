# Site2App shell

The Android app every [Site2App](https://site2app-iota.vercel.app) customer gets: their website in a WebView, plus the native parts a website lacks. It is the same code for every customer. One app config (the JSON document the customer edits on the Site2App site) is the only difference between their apps.

The Site2App site starts builds here, and this repository is public so the builds run on GitHub's free Actions minutes. A build's config and log are therefore public too, and the site says so before anyone builds.

## What the shell does

| From the config | In the app |
| --- | --- |
| `app.startUrl` | The page the app opens on |
| `app.launcherName`, `app.androidPackage`, `app.versionName` | Home-screen label, application ID, version |
| `branding.*` | Launch screen colour, status bar colour and icon style, launcher icon from the site's artwork (a letter icon if there is none usable) |
| `navigation.internalHosts` | Links to these hosts stay in the app. A link to another host that someone taps opens outside it (`externalLinks`: a custom tab or the phone's browser). Redirects and script navigations stay, so checkouts reach payment pages and come back |
| `navigation.tabs` | A native bottom tab bar, up to five tabs, icons from `icons/tab-icons.json` |
| `navigation.pullToRefresh` | Pull down to reload |
| `offline.message` | The screen shown when a page fails with no network. It retries by itself when the network returns |
| `webview.userAgentSuffix`, `injectCss`, `injectJs` | Added to the user agent; run in every page of the internal hosts |

Always on: file uploads, downloads (handed to the browser), fullscreen video, `tel:`, `mailto:`, `intent:` and other app links when tapped, back navigation through the page history, cookies kept across launches.

Not in the shell yet: push notifications (needs each app's own Firebase project), location, camera and microphone for pages (the app holds none of those permissions), deep links into the app, and an iOS shell.

## Layout

```
android/                       The Gradle project (one app module)
  shell.properties             applicationId, versionName, versionCode   (generated)
  app/src/main/assets/app-config.json                                    (generated)
  app/src/main/res/values/shell.xml        app name and colours          (generated)
  app/src/main/res/mipmap-*/ic_launcher_foreground.png                   (generated)
scripts/apply-config.mjs       Writes the generated files from a config
scripts/make-tab-icons.mjs     Writes the tab icon drawables and TabIcons.kt from icons/tab-icons.json
scripts/samples/               site2app.json (the committed sample), wikipedia-tabs.json (the emulator test)
scripts/smoke.sh               Installs an APK on an emulator and photographs it
.github/workflows/build.yml    One customer build, started by the Site2App site
.github/workflows/check.yml    Tests, lint and an emulator run on every push
.github/workflows/cleanup.yml  Deletes build releases after 14 days
```

## Build an app locally

Needs Node 22+, JDK 21 and the Android SDK.

```bash
cd scripts && npm ci && cd ..
node scripts/apply-config.mjs path/to/app-config.json
cd android && ./gradlew :app:assembleRelease
```

Without the test key the release build is signed with your debug key, so it installs on your own phone. Run `node scripts/apply-config.mjs scripts/samples/site2app.json` afterwards to put the committed sample back.

## How a build runs

The site sends `workflow_dispatch` to `build.yml` with three inputs: a build ID, the config (JSON, gzipped, base64) and a version code. The workflow checks the inputs, applies the config, compiles and signs a release APK, and publishes it with a picture of the launcher icon as the pre-release `build-<id>`. The site polls the run and links to the APK when it is ready.

Test builds are signed with one shared Site2App test key so a newer build installs over an older one. It is held in these repository secrets: `SHELL_TEST_KEYSTORE_BASE64`, `SHELL_TEST_KEYSTORE_PASSWORD`, `SHELL_TEST_KEY_ALIAS`, `SHELL_TEST_KEY_PASSWORD`. It is not an upload key and must never sign anything sent to Google Play.

## Licences

Tab icons are [Material Symbols](https://github.com/google/material-design-icons) (Apache 2.0), from `@material-symbols/svg-400` 0.48.0.
