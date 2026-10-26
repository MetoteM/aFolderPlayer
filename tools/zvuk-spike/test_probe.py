import json
import unittest
from unittest.mock import patch
import probe


class ProbeTest(unittest.TestCase):
    def test_api_token_never_reaches_cdn_or_report(self):
        calls = []
        def transport(url, headers, body, limit):
            calls.append((url, headers))
            if url.endswith('/profile'):
                return b'{"result":{"is_anonymous":false,"token":"private-response"}}'
            if '/graphql' in url:
                return b'{"data":{"collection":{"tracks":[{"id":"123"}]}}}'
            if '/track/stream' in url:
                return b'{"result":{"stream":"https://media.example.org/track?secret=private-url"}}'
            return b'audio-sample'
        p = probe.Probe('local-secret', transport)
        with patch.object(probe, 'decode_sample', return_value='sample_decoded'):
            result = probe.run(p, '123', allowed_hosts=['media.example.org'], decode=True)
        self.assertEqual('sample_decoded', result['decode'])
        self.assertNotIn('X-Auth-Token', calls[-1][1])
        self.assertTrue(all(h['X-Auth-Token'] == 'local-secret' for _, h in calls[:-1]))
        for secret in ['local-secret', 'private-response', 'private-url', '123']:
            self.assertNotIn(secret, json.dumps(result))

    def test_rejected_session_stops_without_collection_or_stream(self):
        calls = []
        def transport(*args):
            calls.append(args)
            raise probe.Failure('session_rejected')
        result = probe.run(probe.Probe('secret', transport), '123')
        self.assertEqual(1, len(calls))
        self.assertEqual('session_rejected', result['session'])
        self.assertEqual('not_run', result['stream'])

    def test_anonymous_or_missing_identity_is_not_authenticated(self):
        for data in [b'{"result":{"is_anonymous":true}}', b'{"result":{}}']:
            result = probe.run(probe.Probe('secret', lambda *a: data))
            self.assertEqual('session_not_confirmed', result['session'])

    def test_html_protection_does_not_leak_response(self):
        result = probe.run(probe.Probe('secret', lambda *a: b'<html>private-secret</html>'))
        self.assertEqual('non_json_or_protection', result['session'])
        self.assertNotIn('private', json.dumps(result))

    def test_graphql_error_does_not_become_empty_successful_collection(self):
        p = probe.Probe('secret', lambda *a: b'{"errors":[{"message":"secret"}]}')
        with self.assertRaisesRegex(probe.Failure, '^api_rejected$'):
            p.collection()

    def test_host_approval_prevents_unreviewed_stream_request(self):
        def forbidden(*args):
            self.fail('unexpected network request')
        p = probe.Probe('secret', forbidden)
        with self.assertRaisesRegex(probe.Failure, 'cdn_host_requires_local_approval'):
            p.sample('https://unreviewed.example.org/a', ['media.example.org'])
        for url in ['http://media.example.org/a', 'https://user:pass@media.example.org/a',
                    'file:///etc/passwd', 'https://127.0.0.1/a', 'https://localhost/a',
                    'https://media.example.org:8080/a']:
            with self.assertRaises(probe.Failure):
                probe.secure_host(url)

    def test_header_injection_and_invalid_track_rejected_before_network(self):
        with self.assertRaises(probe.Failure):
            probe.Probe('token\r\nHeader: value')
        with self.assertRaises(probe.Failure):
            probe.Probe('secret').stream('../profile', 'high')

    def test_no_implicit_redirect_forwarding(self):
        self.assertIsNone(probe.NoRedirect().redirect_request(None, None, 302, '', {}, 'https://other.example.org'))


if __name__ == '__main__':
    unittest.main()
