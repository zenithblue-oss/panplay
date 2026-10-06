<p align="center"><img src="docs/panplay-logo-512.png" width="128" alt="PanPlay logo"></p>

# PanPlay

Windows game launcher for Android: Wine (Proton arm64ec) + FEX + DXVK + a built-in X server, with the
[PanVK Kbase driver](https://github.com/zenithblue-oss/panvk-kbase-android) bundled so Mali CSF GPUs
(tested: Mali-G615) can run Direct3D 9/10/11 games through Vulkan.

Package `dev.zenithblue.panvklauncher`, minSdk 28.

## Install

Download the APK from [Releases](https://github.com/zenithblue-oss/panplay/releases). Sibling tool:
[PanProbe](https://github.com/zenithblue-oss/panprobe) (Vulkan info and driver tests).

## Build

```sh
export ANDROID_HOME=/path/to/android-sdk     # NDK 29.0.14206865, CMake 3.22.1
./gradlew -Dorg.gradle.jvmargs=-Xmx3g :app:assembleDebug
```

- The PanVK driver `.so` is not committed. `app/src/main/assets/bundled-driver.json` pins a driver
  release in the driver repo; the build downloads it, verifies sha256, and caches it in
  `~/.cache/panvk-launcher/`. Use `-PpanvkSo=/path/to/libvulkan_panfrost.so` to bundle your own build.
- Bundled Windows components (DXVK, FEX, Proton) are built or fetched by `scripts/` using pins from
  `scripts/components.env` and `app/bundled-components.json`.
- `tools/launch-shortcut.py` and `tests/` are developer helpers used with the driver repo's test setup.

## History

Split from `apps/panvk-launcher` of
[zenithblue-oss/panvk-kbase-android](https://github.com/zenithblue-oss/panvk-kbase-android) with full
history (releases up to 1.2.2). Old release links in that repo point here.

## License

MIT for this project's own code (`LICENSE`). The built-in X server under
`app/src/main/java/com/winlator/` and `app/src/main/cpp/winlator/` is derived from Winlator and stays
LGPL-2.1 (`third_party/winlator/LICENSE`). See `NOTICE.md` and `app/src/main/assets/components-NOTICE.txt`.
