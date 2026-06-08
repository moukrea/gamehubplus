# Patch bundle provenance

The on-device patcher applies `app/src/main/assets/patches.rvp` — a ReVanced patch
bundle. It is **derived from** [The412Banner/bannerhub-revanced](https://github.com/The412Banner/bannerhub-revanced)
(GPL-3.0), with these changes for GameHub+:

- **Selected** patches only: bypass login, PC-accurate vibration (+ settings
  activity / menu row / label), GOG (menu row + activities), file manager access,
  show Game ID (row + label), show PC Game Settings row, disable Firebase
  Crashlytics, mute UI sounds (re-implemented for 6.0.8's `.m4a` assets), Banner
  Tools grid, per-game id capture. **Excluded** the BannerHub-specific Explore
  tab, catalog API redirect, launcher icon, GPU spoof, and legacy renderer.
- **Rebranded** the per-game SharedPreferences store prefix `bh_` → `ghp_`
  (GameHub+, not BannerHub).
- **Added** (authored here, in the same ReVanced project):
  - **Export/Import PC config** — reads/writes a title's whole PC-Engine config
    blob (`pc_g_setting<id>` / `game_settings_v2_unified`) to JSON.
  - **EmuReady compatibility** — queries the [EmuReady](https://www.emuready.com)
    API for the device's SoC (+ equivalents), shows graded listings, applies a
    listing's settings, and overlays a grade tag on game tiles.
- App id is set to `com.xiaoji.egggameplus` and label "GameHub+" via ReVanced's
  Change package name / Change app name patches, so it installs alongside stock
  GameHub.

The bundle is applied on-device by the embedded ReVanced patcher runtime
(`patcher-android` + `library-android`) with a bundled `aapt2`. CI builds only
the **patcher app**; no copy of XiaoJi's GameHub APK is included or redistributed —
you supply your own.
