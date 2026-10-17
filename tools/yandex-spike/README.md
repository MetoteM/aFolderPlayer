# Yandex Music: local diagnostic spike

Not a working player or an official SDK. Separate experiment; stable 0.8.3 remains unchanged.

Python standard library only:

```sh
python3 -m unittest discover -s tools/yandex-spike -v
python3 tools/yandex-spike/probe.py --anonymous
python3 tools/yandex-spike/probe.py
# Optional known track for download-info metadata after successful account check:
python3 tools/yandex-spike/probe.py --track-id 123
```

The authenticated command prompts in a local terminal without echo. Use only your own legitimately obtained Yandex Music OAuth token; never paste it into chat, command arguments, issues, CI, or files in the repository. The probe does not obtain tokens, store them, request passwords, read browser cookies, or embed another application's OAuth client credentials. A generic Yandex ID token is not established to grant Music access.

Stages are independent: authenticated account status, search, liked-track count, optional audio descriptor count. Report contains stage outcomes/counts only. No account UID/login, titles, OAuth tokens, remote error bodies, signed links or response dumps are emitted. Session failure prevents private collection/audio checks. Search remains a separate diagnostic, not authorization evidence.

Account identity is not proof of a subscription. Download-info descriptors are not proof of a full playable track, ad-free playback, or rights to a particular quality. The probe deliberately does not resolve CDN links, decode DRM or play previews as a substitute for full tracks. Actual playback is `not_tested`.

Network: fixed HTTPS API origin, exact endpoint allowlist, 15-second per-request timeout, bounded 2 MB response, no redirect following, no retry loop, no browser impersonation. HTTP 451 is reported without assuming its precise cause or attempting circumvention.

Next gate: service-supported Music authorization and a tester's real account/subscription in a service-supported region/network. Tokens remain local. Only then develop and validate the Android foreground playback lab, followed separately by background/queue integration. No APK with a claimed access fix is published from this spike.
