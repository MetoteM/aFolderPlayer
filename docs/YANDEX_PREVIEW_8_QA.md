# Yandex preview 8: experimental MP3 export

Player 0.8.4-yandex.8 (49), extension unchanged 0.2.0-yandex.8 (11). Experimental branch only.

Player song menu → Сохранить MP3…; online library → select one song → Действия → Сохранить MP3…. Android ACTION_CREATE_DOCUMENT asks for destination and suggested artist/title filename before any export network operation. No broad storage permissions or automatic saves. Picker cancellation finishes without downloading. User may choose any writable document provider; the app cannot guarantee that a cloud-backed provider stores the file only locally.

A private temporary file receives the full, unencrypted MP3 selected by the existing signed extension at the configured network quality. No transcoding or extra Yandex save notification. Both the resolver and download obey current Wi-Fi/mobile policy. The server sees the audio requests; saving may require a separate complete download even if playback is already buffered.

Export checks HTTP 200, declared content length, 250 MB cap, ID3 size and MPEG Layer III header. HTML/errors/preview/DRM are not exported. Temp files are deleted on success, failure and cancellation. Only after successful download validation are bytes copied to the chosen destination. Failures/cancellation attempt to delete the unfinished document. Provider refusal, process death and storage failures can leave an empty/partial document; no automatic local-library import occurs. If the selected folder is already in the library, refresh it from the menu after saving.

The export has its own visible activity, progress and cancel button. Keep it open while saving. Activity destruction cancels work; normal orientation changes do not restart it. Process recreation with an unfinished destination attempts cleanup and exits. No background service/resumable jobs in this experimental iteration. Saved bytes retain the server-provided metadata; filename includes artist/title, no separate artwork requests or ID3 rewriting.

Automated QA: exact bytes including ID3, progress, HTML/truncation/size rejection, cancellation/network-policy failure cleanup, Android destination picker MIME/name/cancel. Existing playback, rapid-tap and sharing tests must remain green. Real account download + playback of the resulting file on the Honor device remain pending user validation.
