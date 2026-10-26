# Build and stage aFolderPlayer 0.7.6 on the trusted Windows signing computer

Use the existing private certificate under `signing/`. **Do not upload or share it.**

1. Check out the exact source commit that passed GitHub Actions.
2. Run `build-release.cmd` (it picks up the existing personal signing config).
3. The resulting `app/build/outputs/apk/release/app-release.apk` must be signed
   with the same key as 0.7.5. The staging command refuses other certificates.
4. From the project root (adjust Android SDK location if necessary):

```bat
python tools\stage-player-release.py --apk app\build\outputs\apk\release\app-release.apk --apksigner "%LOCALAPPDATA%\Android\Sdk\build-tools\36.0.0\apksigner.bat" --aapt "%LOCALAPPDATA%\Android\Sdk\build-tools\36.0.0\aapt.exe"
python tools\release-checkpoint.py prepare 0.7.6
```

The stage helper refuses overwriting previously staged signed parts.
Store the output files and upload plan on a dedicated Git release staging
branch in small committed batches; `release-checkpoint.py status 0.7.6`
verifies them by actual branch membership. Continue from the last committed
batch if a process/chat is interrupted.

An unsigned APK from GitHub Actions is **not** installable as an update.
Read [RELEASE_0.7.6.md](RELEASE_0.7.6.md) for the release gate.
