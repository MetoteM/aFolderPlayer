# Preview 9: embedded cover/tags and hidden-list stability

Player 0.8.4-yandex.9 (50); AFP Online 0.2.0-yandex.8 unchanged. Experimental branch only.

Export snapshots the cover URL together with the selected track ID/title/artist. Current-song metadata is matched by media ID; the playing metadata snapshot supplies a fallback. The existing bounded cover cache is reused, otherwise one cover request obeys the current network policy. The image is embedded as ID3 APIC front cover; Unicode title/artist are written to TIT2/TPE1. Audio frames are copied unchanged. Ordinary uncompressed ID3v2.3/v2.4 metadata is preserved; v2.4 text is converted to v2.3 Unicode. Unsupported old tag flags are not preserved. Existing APIC is retained if a new cover is unavailable; failed cover retrieval is reported instead of silently claiming success. No batch rewriting of already exported files.

Hidden-list browsing now reads private metadata on the worker thread and handles errors inline. The adapter tolerates stale positions during an OEM ListView dataset switch; previous code directly indexed the replaced list. Decision preference reads tolerate obsolete value types. Browsing does not issue playback commands or change hidden flags. Tests cover switching a 50-row collection to hidden while a real playback service retains queue, position and playWhenReady. This is a found crash-prone path, not proof of the exact device crash without its stack trace.

A local diagnostic stores exception class names and stack code locations only, excluding exception messages, account data, track titles and URLs. No automatic transfer. Settings or online source menu → Диагностика последнего сбоя → Копировать. It can diagnose a repeat crash; it cannot reconstruct an old crash from before this version. Android native kills/OOM kills outside managed exception handling may not produce a record.

QA: real generated tone MP3 + JPEG fixtures; ID3 Unicode/APIC parsed in tests, byte-for-byte audio comparison, retagging without duplicate tags, v2.4 album preservation and cancellation cleanup. Downloaded CI tagged fixture must also be inspected independently with ffprobe. Real-account export + offline cover display and device hidden-list reproduction remain user QA.

ID3 reference: https://id3.org/id3v2.3.0 .
