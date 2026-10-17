# AFP Torrent prototype 0.9.0.1

Experimental branch: experimental/0.9.0.1-torrent, based on stable 0.9.0.
Production main/releases/catalogues are not changed. Player package
`dev.alex.folderplayer.torrentlab` (53), extension `dev.alex.afptorrent.lab` (1).
Both APKs must use the existing signing key. Stable apps remain installed and
keep their private data; choose library roots again in the isolated prototype.

## Scope

Open magnet / user-picked .torrent, fetch metadata, select audio files and SAF
output tree, download selected files, verify pieces, flush native disk cache,
then export complete files into an AFP-<task> subfolder. Import the folder into
AFP; subsequent completed files in an already imported root trigger a rescan.
Existing destination files are never overwritten. Cancelling/removing a task
only removes private staging, not exported library files.

The engine is libtorrent4j 2.1.0-39 (MIT, bundled third-party licences). IPC API 1
is signature protected, with capability/snapshot/detail/command/callback methods.
Metadata/native/disk export work stay outside UI and player. Snapshots contain
summaries; file details are bounded per task (200 files, 2 MiB metadata).
Trackers/search connectors are deliberately not required for this first phase:
search is advertised as unsupported; input is magnet or .torrent. Future catalogue
connectors ship compiled in extension updates, not remote executable scripts.

## Strict no-upload decision

User explicitly prohibited seeding AND uploading file pieces while downloading.
There is no upload/ratio/seeding preference. The native session starts with fixed
choking and zero upload slots, allowed-fast set disabled. The default LAN bypass
is removed by applying only the global peer class for IPv4/IPv6/socket types.
The same policy applies to magnet metadata sessions. Payload upload counters are
checked; violation stops the engine. Completion pauses and closes the swarm before copying to SAF.
BitTorrent protocol/metadata/ACK traffic is still outbound; this is not zero
outbound traffic or IP anonymity. No ratio spoofing, and private tracker rules
are not bypassed; a source may refuse such a client.

## Runtime and storage

Default: unmetered Wi-Fi/Ethernet, no roaming, configurable download cap and global staging budget. One payload transfer/export at a time. Sockets are pinned to the allowed network so Wi-Fi loss cannot silently reroute them through cellular. Unknown VPN transports are treated conservatively by the Wi-Fi-only policy.
Android foreground dataSync service has visible progress/pause notification.
No boot receiver. Android timeout pauses persistently and stops foreground work;
resume is user initiated. No wakelock while waiting for permitted networking.
Task/selection/output receipts survive process restart; partial content is
rechecked on resume. Storage requires temporary plus exported copies. Completed
payload staging is removed after successful export; cancelled/failed staging can
be removed through the task menu. OS may kill a service;
manual continuation reuses partial data. SAF permission loss/export failure
keeps staging for retry. Downloading does not depend on keeping AFP open.

## Verification / phone checklist

CI builds/tests/lints the release variants of player and extension. Native Linux
JNI test creates owned data, seeds on loopback, downloads a selected file,
restarts with partial content, verifies bytes, fetches magnet metadata and exposes
the client to an interested third peer beyond the optimistic unchoke interval:
zero payload upload is mandatory. Rule tests cover traversal, network policy,
selection budgets and persistent receipts. This is not physical Android QA.

Phone: install both APKs; Settings → Торренты; choose .torrent or magnet; get file
list; choose music, destination and start. Check Wi-Fi/mobile transitions, pause,
screen off, close AFP, resume after restart, completed-folder import, cancellation,
notification pause and removal of temporary files. Check upload counters remain
zero; existing local/online playback must keep working. Real battery consumption
and Android JNI/device behavior require the phone run before any stable merge.
