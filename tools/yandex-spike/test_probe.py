import json
import unittest
from unittest.mock import patch
import urllib.error
import probe


class ProbeTest(unittest.TestCase):
    def test_token_and_account_data_never_enter_report(self):
        calls = []
        def transport(url, headers):
            calls.append((url, headers))
            if '/account/status' in url:
                data = {'account': {'uid': 987654321, 'login': 'private-login'}}
            elif '/search?' in url:
                data = {'tracks': {'results': [{'title': 'private-title'}]}}
            elif '/likes/tracks' in url:
                data = {'library': {'tracks': [{'id': 'private-id'}]}}
            else:
                data = [{'codec': 'mp3', 'downloadInfoUrl': 'https://cdn.example/?signed=private-url'}]
            return json.dumps({'result': data}).encode()
        result = probe.run(probe.Probe('local-secret', transport), track_id='123')
        report = json.dumps(result)
        for private in ['local-secret', '987654321', 'private-login', 'private-title', 'private-id', 'private-url']:
            self.assertNotIn(private, report)
        self.assertEqual('authorized_account_confirmed', result['session'])
        self.assertEqual('not_tested', result['playback'])
        self.assertEqual(1, result['stream_info']['mp3_descriptors'])
        self.assertTrue(all(url.startswith(probe.API + '/') for url, _ in calls))
        self.assertTrue(all(h['Authorization'] == 'OAuth local-secret' for _, h in calls))

    def test_search_does_not_establish_session(self):
        p = probe.Probe(transport=lambda *a: b'{"result":{"tracks":{"results":[{}]}}}')
        result = probe.run(p, anonymous=True)
        self.assertEqual({'results_count': 1}, result['search'])
        self.assertEqual('not_tested', result['session'])
        self.assertEqual('not_tested', result['collection'])

    def test_missing_or_anonymous_account_does_not_establish_session(self):
        for account in [{}, {'uid': 0}, {'uid': True}, {'uid': '../private'}]:
            p = probe.Probe('secret', lambda *a: json.dumps({'result': {'account': account}}).encode())
            with self.assertRaisesRegex(probe.Failure, 'authorized_account_not_confirmed'):
                p.session()
            self.assertIsNone(p.uid)

    def test_session_failure_does_not_call_private_endpoints(self):
        calls = []
        def transport(url, headers):
            calls.append(url)
            if '/account/' in url:
                raise probe.Failure('authorization_rejected')
            return b'{"result":{}}'
        result = probe.run(probe.Probe('secret', transport), track_id='123')
        self.assertEqual(2, len(calls))
        self.assertEqual('authorization_rejected', result['session'])
        self.assertEqual('not_tested', result['stream_info'])

    def test_token_and_path_injection_are_rejected_before_transport(self):
        with self.assertRaisesRegex(probe.Failure, 'invalid_token_format'):
            probe.Probe('secret\r\nInjected: yes')
        p = probe.Probe(transport=lambda *a: self.fail('unexpected network'))
        for path in ['//evil.example/account/status', '/account/status#secret', '/users/../likes/tracks']:
            with self.assertRaisesRegex(probe.Failure, 'invalid_api_path'):
                p.api(path)
        with self.assertRaisesRegex(probe.Failure, 'authorized_account_required'):
            p.collection()

    def test_html_error_and_large_payload_are_sanitized(self):
        for raw, message in [(b'<html>private details</html>', 'non_json_or_protection'),
                             (b'{"error":{"message":"private details"}}', 'api_rejected'),
                             (b'x' * (probe.LIMIT + 1), 'response_too_large')]:
            with self.assertRaisesRegex(probe.Failure, message):
                probe.Probe(transport=lambda *a: raw).api('/account/status')

    def test_http_restriction_has_no_body_or_automatic_retry(self):
        error = urllib.error.HTTPError('https://example/?secret', 451, 'private details', {}, None)
        with patch.object(probe.urllib.request, 'build_opener') as factory:
            factory.return_value.open.side_effect = error
            with self.assertRaisesRegex(probe.Failure, '^service_restriction_http_451$'):
                probe.fetch(probe.API + '/search', {})
            self.assertEqual(1, factory.return_value.open.call_count)

    def test_audio_descriptors_do_not_trigger_cdn_requests(self):
        p = probe.Probe('secret', lambda *a: b'{"result":[{"codec":"aac","downloadInfoUrl":"https://evil.example/"}]}')
        p.uid = '1'
        result = p.stream_info('123:456')
        self.assertEqual(0, result['mp3_descriptors'])
        self.assertEqual('not_tested', result['playback'])
        self.assertEqual('not_assessed', result['subscription'])
        with self.assertRaisesRegex(probe.Failure, 'invalid_track_id'):
            p.stream_info('../secret')


if __name__ == '__main__':
    unittest.main()
