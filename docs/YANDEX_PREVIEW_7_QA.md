# Yandex preview 7: share public track links

Player 0.8.4-yandex.7 (48); extension remains 0.2.0-yandex.8 (11).

Player song menu → Поделиться треком. Online library: select one song → Действия → Поделиться треком. Android chooser sends text/plain containing only https://music.yandex.ru/track/{numeric_id}; title is the share subject. No audio file, temporary CDN URL, credentials or tracking query parameters. Building the link does not contact Yandex. Local song menu does not show this online-only action.

Primary reference for the public /track/ URL form: https://yandex.ru/support2/guide-alice-answer/ru/muzyka .

Regression: actual song-menu click launches the Android chooser with the exact public link; local and malformed IDs are rejected. On-device recipient-app handoff remains to be tested by the user.
