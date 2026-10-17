#!/usr/bin/env python3
"""Read-only Yandex Music feasibility probe. No credentials or account data in reports."""
import argparse
import getpass
import json
import re
import sys
import urllib.error
import urllib.parse
import urllib.request

API = 'https://api.music.yandex.net'
LIMIT = 2_000_000


class Failure(Exception):
    pass


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def fetch(url, headers):
    request = urllib.request.Request(url, headers=headers)
    try:
        with urllib.request.build_opener(NoRedirect()).open(request, timeout=15) as response:
            raw = response.read(LIMIT + 1)
            if len(raw) > LIMIT:
                raise Failure('response_too_large')
            return raw
    except urllib.error.HTTPError as error:
        labels = {401: 'authorization_rejected', 403: 'access_denied',
                  418: 'access_protection', 429: 'rate_limited', 451: 'service_restriction_http_451'}
        raise Failure(labels.get(error.code, 'http_' + str(error.code))) from None
    except (urllib.error.URLError, OSError, TimeoutError):
        raise Failure('network_error') from None


class Probe:
    def __init__(self, token='', transport=fetch):
        if token and (len(token) > 8192 or any(ord(c) < 33 or ord(c) > 126 for c in token)):
            raise Failure('invalid_token_format')
        self.token = token
        self.transport = transport
        self.uid = None

    def api(self, path, params=None):
        allowed = path in ('/account/status', '/search') or re.fullmatch(
            r'/users/[1-9][0-9]*/likes/tracks|/tracks/[0-9]+(?::[0-9]+)?/download-info', path)
        if not allowed:
            raise Failure('invalid_api_path')
        headers = {'Accept': 'application/json', 'User-Agent': 'aFolderPlayer-Online-Research/0.1'}
        if self.token:
            headers['Authorization'] = 'OAuth ' + self.token
        url = API + path + ('?' + urllib.parse.urlencode(params) if params else '')
        raw = self.transport(url, headers)
        if len(raw) > LIMIT:
            raise Failure('response_too_large')
        try:
            data = json.loads(raw)
        except (ValueError, UnicodeError):
            raise Failure('non_json_or_protection') from None
        if not isinstance(data, dict):
            raise Failure('invalid_response')
        if data.get('error') or data.get('errors'):
            raise Failure('api_rejected')
        if 'result' not in data:
            raise Failure('invalid_response')
        return data['result']

    def session(self):
        self.uid = None
        if not self.token:
            raise Failure('token_required')
        result = self.api('/account/status')
        account = result.get('account') if isinstance(result, dict) else None
        uid = str(account.get('uid', '')) if isinstance(account, dict) else ''
        if not re.fullmatch(r'[1-9][0-9]*', uid):
            raise Failure('authorized_account_not_confirmed')
        self.uid = uid
        # A recognized account does not establish playback rights or a paid subscription.
        return 'authorized_account_confirmed'

    def search(self, query):
        if not query.strip() or len(query) > 200:
            raise Failure('invalid_search_query')
        result = self.api('/search', {'text': query, 'type': 'track', 'page': 0})
        tracks = result.get('tracks') if isinstance(result, dict) else None
        rows = tracks.get('results') if isinstance(tracks, dict) else None
        if rows is None and isinstance(result, dict) and tracks is None:
            return 0
        if not isinstance(rows, list):
            raise Failure('invalid_search_response')
        return len(rows)

    def collection(self):
        if not self.uid:
            raise Failure('authorized_account_required')
        result = self.api('/users/' + self.uid + '/likes/tracks')
        library = result.get('library') if isinstance(result, dict) else None
        rows = library.get('tracks') if isinstance(library, dict) else None
        if not isinstance(rows, list):
            raise Failure('invalid_collection_response')
        return len(rows)

    def stream_info(self, track_id):
        if not self.uid:
            raise Failure('authorized_account_required')
        if not re.fullmatch(r'[0-9]+(?::[0-9]+)?', track_id):
            raise Failure('invalid_track_id')
        rows = self.api('/tracks/' + track_id + '/download-info')
        if not isinstance(rows, list):
            raise Failure('invalid_stream_response')
        # Do not resolve signed links, decrypt media or silently select a preview.
        mp3 = [r for r in rows if isinstance(r, dict) and r.get('codec') == 'mp3'
               and isinstance(r.get('downloadInfoUrl'), str) and r['downloadInfoUrl']]
        return {'variants': len(rows), 'mp3_descriptors': len(mp3),
                'playback': 'not_tested', 'subscription': 'not_assessed'}


def run(probe, query='Metallica', track_id=None, anonymous=False):
    report = {'service': 'yandex_music', 'session': 'not_tested', 'search': 'not_tested',
              'collection': 'not_tested', 'stream_info': 'not_tested', 'playback': 'not_tested'}
    if not anonymous:
        try:
            report['session'] = probe.session()
        except Failure as error:
            report['session'] = str(error)
    try:
        report['search'] = {'results_count': probe.search(query)}
    except Failure as error:
        report['search'] = str(error)
    if report['session'] == 'authorized_account_confirmed':
        try:
            report['collection'] = {'tracks_count': probe.collection()}
        except Failure as error:
            report['collection'] = str(error)
        if track_id:
            try:
                report['stream_info'] = probe.stream_info(track_id)
            except Failure as error:
                report['stream_info'] = str(error)
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--anonymous', action='store_true')
    parser.add_argument('--query', default='Metallica')
    parser.add_argument('--track-id')
    args = parser.parse_args()
    try:
        if not args.anonymous and not sys.stdin.isatty():
            raise Failure('local_terminal_required')
        token = '' if args.anonymous else getpass.getpass('Own Yandex Music OAuth token (local only): ')
        result = run(Probe(token), args.query, args.track_id, args.anonymous)
        print(json.dumps(result, ensure_ascii=False, indent=2))
    except Failure as error:
        print(json.dumps({'error': str(error)}))
    except (EOFError, KeyboardInterrupt):
        print('{"error":"cancelled"}')


if __name__ == '__main__':
    main()
