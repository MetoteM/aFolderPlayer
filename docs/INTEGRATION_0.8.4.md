# 0.8.4 integration candidate

## Immutable inputs

- main: e7b63d691a6dab87279809a9e3cfdac0780032ac (stable 0.8.3, code 41).
- prerelease/yandex-spike: 8a9e39cf25178505c815750ea33595e3e40edcee (player 0.8.4-yandex.9, code 50).
- common ancestor: ec3d92d740d19e93dd56fd1f58990852d2bdb39b.
- integration branch: integration/0.8.4-yandex; main and experimental branch remain unchanged.

## Preservation map

| Stable main behavior | Integration evidence |
|---|---|
| Local hidden tracks remain visible but do not play; can be returned | Library.kt and LibraryVisibilityTest unchanged from main; full tests |
| Local exclusion survives rescanning, retains playlist membership; physical files stay | Library.kt unchanged; exclusion confirmation and source guards retained in MainActivity |
| Multi-select replaces recognition controls with playlist/hide/exclude actions | MainActivity merge retains identifyButton/hideTracksButton/deleteTracksButton and removes the standalone refresh button |
| Local queue/reorder/lyric timing respect hidden items | Main source guards preserved; source-aware eligibility added to mixed queue cleanup |
| Translator, lyrics, EQ, headset/watch/background playback | translator tree unchanged; full release tests/lint; physical-device regression pending |
| Existing app data and SAF roots/playlists/preferences | Same production package and original signing certificate; no destructive schema migration; IntegrationCompatibilityTest verifies old library preferences survive online session setup |
| Safe updates and extension installation | Existing signature/hash/identity checks retained; online discovery re-enabled; incompatible old Online marked required; installed RC can discover matching stable release without accepting prerelease catalogues |

## Online scope retained

Browser login, collection, own/followed playlists, full paginated queue, selection, next/append, restore with duration/cover metadata, filters/sorting/search, local online favorites/hidden, source-return navigation, network/quality policy, share links, experimental MP3 export with embedded ID3 tags/cover, local crash diagnostics. Exact device hidden-list crash remains unconfirmed; keep the diagnostic path available.

No Zvuk production connector is enabled. Research code/history/tests remain; YandexLabActivity and ZvukLabActivity are registered only in debug. Release retains the working Yandex account UI and signature-protected service. No new broad storage permissions or background download service.

## Candidate identities and migration

- Player release: dev.alex.folderplayer, 0.8.4-rc.1, code 51.
- Online release: dev.alex.afponline, 0.2.1-rc.1, code 12, IPC API 3.
- Debug remains .yandexlab for isolated experiments. Production release contains no lab package query/permission.
- Production candidate updates stable 0.8.3 in place, signed with the existing key. Existing stable folders, playlists and settings must remain.
- .yandexlab applications have separate private storage: this candidate does not copy their OAuth token or metadata. Production Online may require browser login once; lab applications can remain installed.
- Eventual stable releases must have version codes greater than 51 / 12; matching numeric versions 0.8.4 / 0.2.1 are supported by RC-to-stable update checks.
- No release manifest/catalog/tag is published by this integration. Stable 0.8.3 deployment assets are unchanged. Release and actual main merge remain separate from candidate QA.

## Verification

Integration debug CI builds/tests/lints player and Online and runs both probe suites. Pull-request Android checks build/test/lint release variants of player, translator and Online, run release staging/checkpoint and redirect-policy checks. Translation model checks follow the existing unchanged-source policy.

New cross-source tests: local hide/exclusion keeps online IDs and duplicates; mixed reorder/restart retains online metadata/current position while removing unavailable local items; online account setup preserves stable local preferences; old Online protocols cannot be treated as current; RC-to-stable updates still enforce signature/identity/version rules.

Pending device gate before main merge: upgrade stable app without uninstall; verify folders/playlists/hidden/excluded/preferences; local playback without Online/offline; Yandex browser login, full playlist boundary 50→51, mixed queue and restart, local hide during online playback, source navigation, MP3 tags/cover, lyrics/translation/EQ/headset/watch/background. No claim of physical-device PASS without user results.
