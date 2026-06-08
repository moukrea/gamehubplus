# GameHub+ Patcher

An **on-device patcher** for XiaoJi **GameHub** (`com.xiaoji.egggame`, 6.0.8). Install this app, point it at your own copy of the stock GameHub APK, and it produces a patched **GameHub+** build right on your phone — no PC, no ReVanced Manager.

> ⚠️ Use at your own risk. This patches a third-party closed-source app you must supply yourself; nothing from XiaoJi is redistributed here. Built with AI assistance; expect rough edges.

## What the patch set does

Applied to your GameHub APK as **GameHub+** (`com.xiaoji.egggameplus`, installs alongside the stock app):

- **Bypass login** — use the library without signing in.
- **PC-accurate vibration** (+ a Vibration settings activity / menu row).
- **GOG** integration (menu + activities).
- **File manager access** to GameHub's data dir.
- **Disable Firebase Crashlytics**, **mute UI sounds**.
- **Show Game ID** and **PC Game Settings** rows.
- **Export / Import PC config** — dump a title's entire PC-Engine config tree to JSON (`Download/GameHubPlus/configs/<id>.json`) and import it back.
- **EmuReady compatibility** — pull community compatibility data for your device's SoC (and equivalent SoCs) from [EmuReady](https://www.emuready.com), shown per title with a grade, one-tap **Apply** of a listing's settings, and a **grade badge on game tiles**.
- Stored under a `ghp_*` namespace (not `bh_*`) so it never collides with other mods.

## Use it

1. Install **GameHub+ Patcher** from the [latest release](https://github.com/moukrea/gamehubplus/releases/latest).
2. Allow it to *install unknown apps* (needed for both the patched output and self-update).
3. Open it → **Select GameHub APK** (your stock `com.xiaoji.egggame` APK) → **Patch & Install**.
4. The app self-updates: on launch it checks this repo's latest release and offers to download + install a newer build.

## How it works

The app embeds the [ReVanced patcher](https://github.com/ReVanced/revanced-patcher) runtime + a bundled patch set (`app/src/main/assets/patches.rvp`) and a per-ABI `aapt2`, and runs the patch + sign pipeline entirely on-device.

## Build

CI (`.github/workflows/build.yml`) builds a signed release APK and publishes it on tag push (`v*`). Locally:

```
GPR_USER=<gh-user> GPR_TOKEN=<PAT with read:packages> ./gradlew assembleRelease
```

(The ReVanced patcher/library artifacts live on the ReVanced GitHub Packages registry and need a GitHub token with `read:packages`.)

## Credits & license

This builds directly on the work of others — please support them:

- **[ReVanced](https://revanced.app)** — the patcher/library engine.
- **[BannerHub v6 (The412Banner)](https://github.com/The412Banner/bannerhub-revanced)** — the GameHub 6.0.x patch set + extensions this bundle is derived from (login bypass, vibration, GOG, etc.), rebranded `bh_`→`ghp_`.
- **[EmuReady (Producdevity)](https://www.emuready.com)** — the compatibility database/API.

Licensed under **GPL-3.0** (see `LICENSE`), consistent with the ReVanced/BannerHub upstreams. Not affiliated with XiaoJi, BannerHub, ReVanced, or EmuReady.
