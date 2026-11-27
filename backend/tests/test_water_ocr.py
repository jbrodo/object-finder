import importlib.util
import json
import unittest
from io import BytesIO
from pathlib import Path
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location('water_ocr', Path(__file__).parents[1] / 'scripts/water_ocr.py')
water_ocr = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(water_ocr)


class Response(BytesIO):
    status = 200


class WaterOcrTest(unittest.TestCase):
    def test_request_and_stream(self):
        captured = []

        def open_request(request, timeout):
            captured.append(json.loads(request.data))
            self.assertEqual(timeout, 30)
            return Response(b'data: {"choices":[{"delta":{"content":"<PAGE>hello"}}]}\n\ndata: {"choices":[{"delta":{"content":" world"}}]}\n\ndata: [DONE]\n\n')

        with patch.object(water_ocr, 'open_request', side_effect=open_request):
            result = water_ocr.recognize(b'\x89PNG\r\n\x1a\nexample', 'http://127.0.0.1:10000', timeout=30)
        self.assertEqual(result, '<PAGE>hello world')
        self.assertEqual(captured[0]['model'], 'Unlimited-OCR-2')
        self.assertTrue(captured[0]['stream'])
        self.assertIn('<PAGE><image>Multi page parsing.', str(captured[0]['messages']))
        self.assertIn('data:image/png;base64,', str(captured[0]['messages']))

    def test_refuses_nonlocal_endpoint_and_bad_image(self):
        with self.assertRaisesRegex(ValueError, 'locale'):
            water_ocr.recognize(b'\xff\xd8\xfftest', 'https://example.org')
        with self.assertRaisesRegex(ValueError, 'JPEG o PNG'):
            water_ocr.recognize(b'invalid', 'http://127.0.0.1:10000')

    def test_incomplete_stream_is_not_returned_as_success(self):
        with patch.object(water_ocr, 'open_request', return_value=Response(b'data: {"choices":[{"delta":{"content":"partial"}}]}\n')):
            with self.assertRaisesRegex(ValueError, 'incompleta'):
                water_ocr.recognize(b'\xff\xd8\xfftest', 'http://localhost:10000')

    def test_truncated_generation_is_rejected(self):
        message = b'data: {"choices":[{"delta":{"content":"partial"},"finish_reason":"length"}]}\n\ndata: [DONE]\n'
        with patch.object(water_ocr, 'open_request', return_value=Response(message)):
            with self.assertRaisesRegex(ValueError, 'incompleta'):
                water_ocr.recognize(b'\xff\xd8\xfftest', 'http://localhost:10000')

    def test_remote_url_cannot_bypass_loopback_check(self):
        for endpoint in ('http://localhost.evil.example', 'http://127.0.0.1@evil.example', 'http://[::ffff:192.0.2.1]:10000'):
            with self.subTest(endpoint=endpoint), self.assertRaises(ValueError):
                water_ocr.check_endpoint(endpoint)


if __name__ == '__main__':
    unittest.main()
