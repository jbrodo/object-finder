import importlib.util
import json
import unittest
from io import BytesIO
from pathlib import Path
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location('water_stage', Path(__file__).parents[1] / 'scripts/water_stage.py')
stage = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(stage)
PROJECT = '22000000-0000-0000-0000-000000000001'
BUILDING = '33000000-0000-0000-0000-000000000001'
IMPORT = '55000000-0000-0000-0000-000000000001'


class WaterStageTest(unittest.TestCase):
    def test_posts_candidates_without_confirmation(self):
        candidates = {'rows': [{'source_row': 'Foglio!E2', 'unit_label': 'A1', 'kind': 'cold',
                                'period_label': '21-22', 'amount': '12.5', 'warnings': []}], 'warnings': []}
        payload = stage.build_payload(candidates, PROJECT, BUILDING, IMPORT, 'file.xlsx', 'xlsx')
        with patch.object(stage, 'open_request', return_value=BytesIO(json.dumps(IMPORT).encode())) as call:
            result = stage.submit('https://project.supabase.co', 'public-key', 'user-token', payload)
        req = call.call_args.args[0]
        self.assertEqual(req.full_url, 'https://project.supabase.co/rest/v1/rpc/stage_water_candidates')
        self.assertEqual(req.get_header('Authorization'), 'Bearer user-token')
        self.assertEqual(result, IMPORT)
        self.assertIsNone(json.loads(req.data)['p_rows'][0].get('unit_id'))

    def test_refuses_skipped_tables_and_empty_or_large_batches(self):
        for value in ({'rows': [{}], 'warnings': [{'reason': 'complex_table'}]}, {'rows': [], 'warnings': []},
                      {'rows': [{}] * 1001, 'warnings': []}):
            with self.subTest(value=value), self.assertRaises(ValueError):
                stage.build_payload(value, PROJECT, BUILDING, IMPORT, 'file', 'photo')

    def test_rejects_insecure_or_confusing_url(self):
        for url in ('http://example.com', 'https://user:pass@example.com', 'https://example.com/?token=foo'):
            with self.subTest(url=url), self.assertRaises(ValueError):
                stage.endpoint(url)

    def test_unexpected_response_is_rejected(self):
        with patch.object(stage, 'open_request', return_value=BytesIO(b'"different"')):
            with self.assertRaises(ValueError):
                stage.submit('http://127.0.0.1:54321', 'key', 'token', {'p_import_id': IMPORT})


if __name__ == '__main__':
    unittest.main()
