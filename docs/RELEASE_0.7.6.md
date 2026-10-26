# aFolderPlayer 0.7.6 — release gate

Source branch: `development/0.7.6`; PR #1. Previous public release: 0.7.5.
Never publish an unsigned or differently signed APK as an upgrade.

## Source validation

- Final commit must pass GitHub Actions Android checks (APK builds, unit tests,
  lint, German/Chinese inference and HTTPS redirect policy).
- Record exact passing source commit SHA as `source_commit` in the release manifest.
- Player APK versionName `0.7.6`; versionCode `32`.

## Trusted signing machine only

- Use the existing locally stored `signing/folder-player.p12` and
  `signing/signing.properties`. Never send private signing keys to GitHub.
- Rebuild and sign `aFolderPlayer_0.7.6.apk` with the **same signing certificate** as 0.7.5.
  Check signature with Android build-tools' `apksigner verify --print-certs`.
- Previous certificate SHA-256 fingerprint (from release manifest):
  `36efde603967282debd976200c92acf46b68144b2704d61930ebdd9c171302cd`.
- Package must be `dev.alex.folderplayer` with versionCode 32. The unsigned CI
  artifact is only for QA and cannot replace the installed APK.

## Staging and recovery

- Prepare `deployment/releases/0.7.6/manifest.json` with signed APK size,
  SHA-256 digest, certificate fingerprint and passing source commit.
  Split the gzip-compressed signed APK into content-addressed, retrievable
  Git parts, as in 0.7.5.
- Run `python3 tools/release-checkpoint.py prepare 0.7.6`, checkpoint
  the generated upload plan in Git.
- After each upload batch, commit files to `release/0.7.6-staging`.
  Run `python3 tools/release-checkpoint.py status 0.7.6`; the exit code
  remains nonzero until every file exists on that **branch** with exact blob SHA.
- If interrupted, do not rebuild/re-sign the APK: resume the recorded parts.
- Reuse already verified AFP Translate 0.3.1 and English, Chinese, German models;
  never pretend unsigned artifacts or an unverified catalog are release ready.
- Adapt the existing 0.7.5 release workflow only after staging is complete.
  Verify signature, checksums, source CI and catalog before publishing.
- Final public smoke test: downloaded APK, engine, three ZIPs, catalog and
  SHA256SUMS are present and hash-correct.
- Device smoke test: upgrade from 0.7.5, cancel/retry model downloads,
  select per-text language, check history, playback and background controls.
