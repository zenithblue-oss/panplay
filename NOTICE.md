# NOTICE

This repository mixes license regimes. Do not treat the tree as single-license MIT.
Split from `apps/panvk-launcher` of https://github.com/zenithblue-oss/panvk-kbase-android
(see that repository's `NOTICE.md` for the full driver-side notice).

## 1. MIT: PanPlay's own code

Launcher UI, scripts, tests, packaging and docs authored for this project are MIT-licensed
unless a file header states otherwise. See `LICENSE`.

## 2. Winlator X server: vendored, LGPL-2.1

The built-in X server is copied and modified from https://github.com/brunodev85/winlator at commit
`db6d7aa446b5607b48ac2ee36dc4333799ff3dc7` (brunodev85 and Winlator contributors), licensed
**LGPL-2.1**; license text in `third_party/winlator/LICENSE`.

Vendored locations:

- `app/src/main/java/com/winlator/`
- `app/src/main/cpp/winlator/`
- `third_party/winlator/` (license and README with the file list and modifications; changes are
  marked `panvk-launcher` in the sources)

These files stay LGPL-2.1 and are not relicensed. Full source is in this repository so recipients can
modify the code and rebuild the APK.

## 3. Bundled components (not vendored in git)

Proton/Wine, imagefs, FEX, DXVK and the PanVK driver are bundled into release APKs with their own
licenses. Sources, versions and hashes: `app/src/main/assets/components-NOTICE.txt`,
`scripts/components.env`, `app/bundled-components.json`, `app/src/main/assets/bundled-driver.json`.
The PanVK driver is Mesa-derived (MIT); its source and notices are in
https://github.com/zenithblue-oss/panvk-kbase-android.
