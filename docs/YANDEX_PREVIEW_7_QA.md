# Yandex preview 7: share public track links

Player 0.8.4-yandex.7 (48); extension remains 0.2.0-yandex.8 (11).

Player song menu → Поделиться треком. Online library: select one song → Действия → Поделиться треком. Android chooser sends text/plain containing only https://music.yandex.ru/track/{numeric_id}; title is the share subject. No audio file, temporary CDN URL, credentials or tracking query parameters. Building the link does not contact Yandex. Local song menu does not show this online-only action.

Primary reference for the public /track/ URL form: https://yandex.ru/support2/guide-alice-answer/ru/muzyka .

Regression: actual song-menu click launches the Android chooser with the exact public link; local and malformed IDs are rejected. On-device recipient-app handoff remains to be tested by the user.

## MP3 export feasibility (inspection only)

YandexClient.stream filters download-info to codec=mp3 and preview=false. ResolvingDataSource sends the resulting short-lived HTTPS URL to the audio loader without transcoding. For this unencrypted path, writing the full response to a user-selected file is technically possible. No runtime download with a real account was performed in this check, and no export feature is included in preview 7. A complete save started mid-song may need a separate full download; streaming requests remain visible to the service. Local file writing itself needs no additional Yandex API notification.

The service's documented offline feature uses encrypted files restricted to its own application: https://www.yandex.ru/support/music/ru/listening/listening-offline . This inspection does not establish permission to export a track or availability of a full MP3 for every account/track.

## Final build evidence

Source: `38613d68dcc65238c8d09ac838f24f0b0bee4fc0`.
CI: https://github.com/MetoteM/aFolderPlayer/actions/runs/38979173773 — 214 app + 29 extension + 8 Python tests; lint passed. APK signatures match the existing certificate; zipalign and ZIP integrity passed. Saved archive and both APK hashes were checked after upload.
Install ZIP SHA-256: `91c49c5590a4f04eac6a4acc8d1a942124c2bea57405df6537876c5010e57dea`.
