import importlib.util
import unittest
from pathlib import Path

SPEC = importlib.util.spec_from_file_location('water_ocr_table', Path(__file__).parents[1] / 'scripts/water_ocr_table.py')
ocr_table = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(ocr_table)


class OcrTableTest(unittest.TestCase):
    def test_raw_model_output_multiple_years(self):
        raw = '''<PAGE><BLOCK>table[1,2,3,4]<FORK><table>
        <tr><th>APPARTAMENTO</th><th>AFS 2024</th><th>AFS 2023</th><th>ACS 2024</th></tr>
        <tr><td>A1</td><td>12,5</td><td>13</td><td>7</td></tr></table>'''
        result = ocr_table.extract(raw)
        self.assertEqual([(r['kind'], r['period_label'], r['amount']) for r in result['rows']],
                         [('cold', '2024', '12.5'), ('cold', '2023', '13'), ('hot', '2024', '7')])
        self.assertTrue(all(r['period_start'] is None and 'ocr_requires_review' in r['warnings'] for r in result['rows']))
        self.assertEqual(result['rows'][0]['source_row'], 'tabella 1, riga 2, colonna 2')

    def test_invalid_values_remain_for_review(self):
        raw = '<table><tr><th>Appartamento</th><th>AFS LETTURA</th><th>ANNO</th></tr><tr><td>A2</td><td>l2,O</td><td>21-22</td></tr></table>'
        row = ocr_table.extract(raw)['rows'][0]
        self.assertIsNone(row['amount'])
        self.assertEqual(row['raw_amount'], 'l2,O')
        self.assertIn('invalid_or_missing_amount', row['warnings'])

    def test_merged_cells_are_not_guessed(self):
        raw = '<table><tr><th rowspan="2">Appartamento</th><th>AFS 2024</th></tr><tr><td>A1</td><td>12</td></tr></table>'
        result = ocr_table.extract(raw)
        self.assertEqual(result['rows'], [])
        self.assertEqual(result['warnings'][0]['reason'], 'complex_table')

    def test_misaligned_rows_are_skipped(self):
        raw = '<table><tr><th>Appartamento</th><th>AFS 2024</th></tr><tr><td>A1</td></tr></table>'
        result = ocr_table.extract(raw)
        self.assertEqual(result['rows'], [])
        self.assertEqual(result['warnings'][0]['reason'], 'misaligned_row')

    def test_unrecognized_output_is_explicit(self):
        self.assertEqual(ocr_table.extract('<PAGE>no table')['warnings'][0]['reason'], 'no_tables')

    def test_conflicting_period_labels_are_flagged(self):
        raw = '<table><tr><th>Appartamento</th><th>AFS 2024</th><th>ANNO</th></tr><tr><td>A1</td><td>1</td><td>2023</td></tr></table>'
        self.assertIn('conflicting_period_labels', ocr_table.extract(raw)['rows'][0]['warnings'])


if __name__ == '__main__':
    unittest.main()
