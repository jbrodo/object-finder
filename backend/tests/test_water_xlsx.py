import unittest
import zipfile
from io import BytesIO
from pathlib import Path
import importlib.util

SPEC = importlib.util.spec_from_file_location('water_xlsx', Path(__file__).parents[1] / 'scripts/water_xlsx.py')
water_xlsx = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(water_xlsx)


def workbook(rows):
    stream = BytesIO()
    with zipfile.ZipFile(stream, 'w') as z:
        z.writestr('xl/workbook.xml', '''<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="21-22 - AFS" sheetId="1" r:id="rId1"/></sheets></workbook>''')
        z.writestr('xl/_rels/workbook.xml.rels', '''<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/></Relationships>''')
        z.writestr('xl/worksheets/sheet1.xml', '<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>' + rows + '</sheetData></worksheet>')
    stream.seek(0)
    return stream


HEADER = '<row r="1"><c r="B1" t="inlineStr"><is><t>APPARTAMENTO</t></is></c><c r="E1" t="inlineStr"><is><t>AFS LETTURA</t></is></c><c r="I1" t="inlineStr"><is><t>ANNO</t></is></c></row>'


class WaterXlsxTest(unittest.TestCase):
    def test_extracts_review_rows_and_preserves_unknown_period(self):
        row = '<row r="2"><c r="B2" t="inlineStr"><is><t>A1</t></is></c><c r="E2"><v>12.5</v></c><c r="I2" t="inlineStr"><is><t>21-22</t></is></c></row>'
        result = water_xlsx.extract(workbook(HEADER + row))
        candidate = result['rows'][0]
        self.assertEqual((candidate['unit_label'], candidate['kind'], candidate['amount']), ('A1', 'cold', '12.5'))
        self.assertEqual(candidate['period_label'], '21-22')
        self.assertIsNone(candidate['period_start'])
        self.assertEqual(candidate['source_row'], '21-22 - AFS!E2')

    def test_unreadable_and_formula_cells_require_review(self):
        row = '<row r="2"><c r="B2" t="inlineStr"><is><t>A1</t></is></c><c r="E2"><f>1+1</f><v>2</v></c></row>'
        result = water_xlsx.extract(workbook(HEADER + row))
        self.assertIn('formula_cached_value', result['rows'][0]['warnings'])
        self.assertIn('missing_period', result['rows'][0]['warnings'])

    def test_unrecognized_sheet_is_reported(self):
        result = water_xlsx.extract(workbook('<row r="1"><c r="A1" t="inlineStr"><is><t>Altro</t></is></c></row>'))
        self.assertEqual(len(result['rows']), 0)
        self.assertEqual(len(result['warnings']), 1)

    def test_no_private_name_is_extracted(self):
        row = '<row r="2"><c r="A2" t="inlineStr"><is><t>Persona privata</t></is></c><c r="B2" t="inlineStr"><is><t>A1</t></is></c><c r="E2"><v>1</v></c></row>'
        self.assertNotIn('Persona privata', str(water_xlsx.extract(workbook(HEADER + row))))

    def test_placeholder_unit_is_flagged(self):
        row = '<row r="2"><c r="B2" t="inlineStr"><is><t>Z</t></is></c><c r="E2"><v>0</v></c></row>'
        candidate = water_xlsx.extract(workbook(HEADER + row))['rows'][0]
        self.assertEqual(candidate['amount'], '0')
        self.assertIn('possible_aggregate_or_placeholder', candidate['warnings'])


if __name__ == '__main__':
    unittest.main()
