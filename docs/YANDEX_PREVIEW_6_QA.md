# Yandex preview 6: stability and source navigation

Player 0.8.4-yandex.6 (47), AFP Online 0.2.0-yandex.8 (11). Both APKs must be updated. Experimental branch only.

- Separate catalogue/playback client lanes: catalogue HTTP calls cannot hold the stream lock. Each lane owns its mutable caches and revalidates a changed token.
- Cancel the pending future; generation checks skip cancelled queued operations before IPC and discard obsolete results. A running Binder/HTTP call may still finish within network timeouts, but cannot replace the current UI or queue.
- Freeze row, checkbox, toolbar and stale popup actions during preparation/queue handoff. Cancellation is disabled during handoff; it is restored on acknowledgement. Errors remain inline with retry, without modal stacking.
- Bound artwork queue to 24 tasks; skip obsolete-source cover work.
- Preserve the actual online dialog (playlist, filter, sort and scroll) while the player is open. Down/back returns there; a shared NowPlayingStrip opens the player again. A local track launch resets the return source. On process recreation, the online source reopens at the collection.
- Reuse local library icon toolbar, colors, row surfaces and the shared mini-player. Account/network controls remain specific to online content.

Regression coverage: 30 rapid play/playlist taps during a pending acknowledgement dispatch only one queue; 30 cancellations queue only the latest catalogue request; hide/show preserves the list without reloading; blocked catalogue request does not delay the independent playback lane. Existing playlist/125-track/batch/restore tests remain required.

Real-account device QA remains pending: rapidly navigate, cancel and reopen playlists, launch songs, minimize down, reopen from mini-player, then launch a local song and check local return. These tests use controlled API responses and do not establish actual server availability.

## Final evidence

Source: `8882c1378f37f78ff171bcacd8ffde06cd0294d0`.
CI: https://github.com/MetoteM/aFolderPlayer/actions/runs/38964196197 — app 212, online 29, Python 8 tests, lint passed.
Both APK signatures, original certificate fingerprint, zipalign and ZIP CRC checks passed. Saved install ZIP and both inner APK hashes/sizes were verified after upload.
Install archive SHA-256: `42684e727318bb00800eb6b35154937fbe2fb03b9b349b4a7732125417ab30b0`.
Real-account device QA is pending.
