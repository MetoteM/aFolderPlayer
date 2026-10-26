#!/usr/bin/env python3
"""Read-only local experiment. Never emits tokens, user IDs, titles or signed URLs."""
import argparse
import getpass
import ipaddress
import json
import re
import shutil
import subprocess
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

API = "https://zvuk.com"
LIMIT = 2_000_000


class Failure(Exception):
    pass


def secure_host(url):
    try:
        u = urllib.parse.urlsplit(url)
        host = u.hostname or ""
        if u.scheme != "https" or u.username or u.password or u.fragment or u.port not in (None, 443):
            raise ValueError()
        if not re.fullmatch(r"[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?", host) or "." not in host:
            raise ValueError()
        try:
            ipaddress.ip_address(host)
        except ValueError:
            return host
        raise ValueError()
    except ValueError:
        raise Failure("unsafe_stream_address") from None


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def fetch(url, headers, body=None, limit=LIMIT):
    """No retries, cookies, implicit redirects or remote error text in exceptions."""
    req = urllib.request.Request(url, data=body, headers=headers)
    opener = urllib.request.build_opener(NoRedirect())
    try:
        with opener.open(req, timeout=15) as response:
            data = response.read(limit + 1)
            if len(data) > limit:
                raise Failure("response_too_large")
            return data
    except urllib.error.HTTPError as e:
        names = {401: "session_rejected", 403: "access_denied_or_protection", 429: "rate_limited"}
        raise Failure(names.get(e.code, "http_" + str(e.code))) from None
    except (urllib.error.URLError, TimeoutError, OSError):
        raise Failure("network_error") from None


class Probe:
    def __init__(self, token, transport=fetch):
        if token and (len(token) > 8192 or any(ord(c) < 33 or ord(c) > 126 for c in token)):
            raise Failure("invalid_token_format")
        self.token = token
        self.transport = transport

    def api(self, path, payload=None):
        if not path.startswith("/api/") or ".." in path or "#" in path:
            raise Failure("invalid_api_path")
        headers = {"Accept": "application/json", "User-Agent": "aFolderPlayer-Online-Spike/0.1"}
        if self.token:
            headers["X-Auth-Token"] = self.token
        body = None
        if payload is not None:
            body = json.dumps(payload).encode()
            headers["Content-Type"] = "application/json"
        raw = self.transport(API + path, headers, body, LIMIT)
        try:
            data = json.loads(raw)
            if not isinstance(data, dict):
                raise ValueError()
        except (ValueError, UnicodeError):
            raise Failure("non_json_or_protection") from None
        if data.get("errors") or data.get("error"):
            raise Failure("api_rejected")
        return data

    def session(self):
        profile = self.api("/api/tiny/profile").get("result", {})
        if not isinstance(profile, dict) or profile.get("is_anonymous") is not False:
            raise Failure("session_not_confirmed")
        return "authorized_profile_confirmed"

    def collection(self):
        result = self.api("/api/v1/graphql", {
            "operationName": "userCollection",
            "query": "query userCollection { collection { tracks { id } } }",
            "variables": {},
        })
        data = result.get("data", {})
        collection = data.get("collection") if isinstance(data, dict) else None
        tracks = collection.get("tracks") if isinstance(collection, dict) else None
        if not isinstance(tracks, list):
            raise Failure("collection_schema_changed")
        return "collection_readable"  # Do not include count or IDs in diagnostics.

    def stream(self, track_id, quality):
        if not re.fullmatch(r"[0-9]{1,20}", track_id) or quality not in ("mid", "high", "flac"):
            raise Failure("invalid_track_or_quality")
        params = urllib.parse.urlencode({"id": track_id, "quality": quality})
        result = self.api("/api/tiny/track/stream?" + params).get("result", {})
        url = result.get("stream") if isinstance(result, dict) else None
        if not isinstance(url, str) or len(url) > 8192:
            raise Failure("stream_not_available")
        secure_host(url)
        return url

    def sample(self, url, allowed_hosts):
        host = secure_host(url)
        if host not in allowed_hosts:
            raise Failure("cdn_host_requires_local_approval")
        # Deliberately separate transport: the permanent API token never reaches a CDN.
        # No redirects. A different CDN host requires review, not automatic token forwarding.
        headers = {"Accept": "audio/*", "Range": "bytes=0-2097151",
                   "User-Agent": "aFolderPlayer-Online-Spike/0.1"}
        return self.transport(url, headers, None, 2_097_152)


def decode_sample(data):
    ffmpeg = shutil.which("ffmpeg")
    if not ffmpeg:
        raise Failure("ffmpeg_not_installed")
    try:
        r = subprocess.run([ffmpeg, "-v", "error", "-protocol_whitelist", "pipe",
                            "-i", "pipe:0", "-t", "5", "-f", "null", "-"],
                           input=data, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, timeout=20)
    except (OSError, subprocess.TimeoutExpired):
        raise Failure("decoder_unavailable_or_timeout") from None
    if r.returncode:
        raise Failure("sample_decode_failed_or_protected")
    return "sample_decoded_up_to_5_seconds_not_full_playback"


def run(probe, track=None, quality="mid", allowed_hosts=(), decode=False):
    report = {"session": "not_run", "collection": "not_run", "stream": "not_run", "decode": "not_run"}
    for name, operation in [("session", probe.session), ("collection", probe.collection)]:
        try:
            report[name] = operation()
        except Failure as e:
            report[name] = str(e)
            return report
    if track:
        try:
            url = probe.stream(track, quality)
            report["stream"] = "resolved_not_yet_playable"
            report["cdn_host"] = secure_host(url)
            if decode:
                report["decode"] = decode_sample(probe.sample(url, allowed_hosts))
        except Failure as e:
            key = "decode" if report["stream"] == "resolved_not_yet_playable" else "stream"
            report[key] = str(e)
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--token-file", type=Path, help="Local secret file outside Git; otherwise hidden terminal input")
    parser.add_argument("--anonymous", action="store_true", help="Only unauthenticated profile endpoint reachability; no music")
    parser.add_argument("--track", help="Track ID accessible to this account")
    parser.add_argument("--quality", choices=["mid", "high", "flac"], default="mid")
    parser.add_argument("--cdn-host", action="append", default=[], help="Explicit reviewed CDN hostname allowed for sampling")
    parser.add_argument("--decode-sample", action="store_true", help="Bounded download and local FFmpeg decode, not full playback")
    args = parser.parse_args()
    try:
        if args.anonymous:
            Probe("").api("/api/tiny/profile")
            report = {"anonymous_endpoint": "reachable", "authenticated_tests": "not_run"}
        else:
            token = args.token_file.read_text().strip() if args.token_file else getpass.getpass("Zvuk session token (local only): ").strip()
            if not token:
                raise Failure("token_missing")
            report = run(Probe(token), args.track, args.quality, args.cdn_host, args.decode_sample)
    except Failure as e:
        report = {"status": str(e)}
    except (OSError, EOFError):
        report = {"status": "local_secret_unavailable"}
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
