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
