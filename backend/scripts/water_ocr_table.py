#!/usr/bin/env python3
"""Extract review-only water candidates from simple OCR HTML tables."""
import argparse
import json
import re
import sys
from decimal import Decimal, InvalidOperation
from html.parser import HTMLParser
from pathlib import Path

MAX_TEXT = 4 * 1024 * 1024
MAX_ROWS = 50000


class Tables(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.tables = []
        self.current = None
        self.row = None
        self.cell = None
        self.depth = 0

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if tag == 'table':
            self.depth += 1
            if self.depth == 1:
                self.current = {'rows': [], 'complex': False, 'complete': False}
                self.tables.append(self.current)
            else:
                self.current['complex'] = True
        elif self.current is not None and self.depth:
            if tag == 'tr':
                if self.row is not None:
                    self.current['complex'] = True
                self.row = []
            elif tag in ('td', 'th'):
                if self.row is None or self.cell is not None:
                    self.current['complex'] = True
                self.cell = []
                if any(attrs.get(key, '1') != '1' for key in ('rowspan', 'colspan')):
                    self.current['complex'] = True

    def handle_data(self, data):
        if self.cell is not None:
            self.cell.append(data)

    def handle_endtag(self, tag):
        if self.current is None:
            return
        if tag in ('td', 'th') and self.cell is not None:
            if self.row is not None:
                self.row.append(' '.join(''.join(self.cell).split()))
            self.cell = None
        elif tag == 'tr' and self.row is not None:
            if self.cell is not None:
                self.current['complex'] = True
                self.cell = None
            self.current['rows'].append(self.row)
            self.row = None
        elif tag == 'table':
            self.depth -= 1
            if self.depth == 0:
                self.current['complete'] = True
                if self.row is not None or self.cell is not None:
                    self.current['complex'] = True
                self.current = self.row = self.cell = None


def parse_amount(value):
    value = value.strip()
    if not re.fullmatch(r'\+?[0-9]+(?:[.,][0-9]{1,3})?', value):
        return None
    try:
        amount = Decimal(value.replace(',', '.'))
        return format(amount, 'f') if amount < 1000000000 else None
    except InvalidOperation:
        return None


def extract(raw):
    if len(raw.encode('utf-8')) > MAX_TEXT:
        raise ValueError('Testo OCR troppo grande')
    parser = Tables()
    parser.feed(raw)
    parser.close()
    result = {'rows': [], 'warnings': []}
    if not parser.tables:
        result['warnings'].append({'reason': 'no_tables'})
    row_count = 0
    for index, table in enumerate(parser.tables, start=1):
        if table['complex'] or not table['complete']:
            result['warnings'].append({'table': index, 'reason': 'complex_table'})
            continue
        rows = table['rows']
        row_count += len(rows)
        if row_count > MAX_ROWS:
            raise ValueError('Troppe righe nel testo OCR')
        if not rows:
            result['warnings'].append({'table': index, 'reason': 'empty_table'})
            continue
        headers = [cell.upper().strip() for cell in rows[0]]
        units = [i for i, header in enumerate(headers) if header == 'APPARTAMENTO']
        periods = [i for i, header in enumerate(headers) if header == 'ANNO']
        measures = []
        for i, header in enumerate(headers):
            match = re.fullmatch(r'(AFS|ACS)(?:\s+LETTURA(?:\s*\(MC\))?)?(?:\s+((?:19|20)[0-9]{2}))?', header)
            if match:
                measures.append((i, 'cold' if match.group(1) == 'AFS' else 'hot', match.group(2)))
        if len(units) != 1 or len(periods) > 1 or not measures:
            result['warnings'].append({'table': index, 'reason': 'unrecognized_headers'})
            continue
        for row_number, cells in enumerate(rows[1:], start=2):
            if len(cells) != len(headers):
                result['warnings'].append({'table': index, 'row': row_number, 'reason': 'misaligned_row'})
                continue
            unit = cells[units[0]].strip()
            if not unit or unit.upper() == 'APPARTAMENTO':
                result['warnings'].append({'table': index, 'row': row_number, 'reason': 'missing_or_repeated_unit'})
                continue
            for col, kind, year in measures:
                raw_amount = cells[col]
                amount = parse_amount(raw_amount)
                warnings = ['ocr_requires_review']
                if amount is None:
                    warnings.append('invalid_or_missing_amount')
                if unit.upper() == 'Z':
                    warnings.append('possible_aggregate_or_placeholder')
                period = year or (cells[periods[0]] if periods else '')
                if year and periods and cells[periods[0]] and cells[periods[0]] != year:
                    warnings.append('conflicting_period_labels')
                if not period:
                    warnings.append('missing_period')
                result['rows'].append({'source_row': f'tabella {index}, riga {row_number}, colonna {col + 1}',
                    'unit_label': unit, 'kind': kind, 'period_label': period, 'period_start': None,
                    'period_end': None, 'raw_amount': raw_amount, 'amount': amount, 'warnings': warnings})
    return result


def main():
    parser = argparse.ArgumentParser(description='Estrae candidati da tabelle HTML nel testo OCR per la revisione.')
    parser.add_argument('file', type=Path)
    args = parser.parse_args()
    try:
        with args.file.open('rb') as source:
            raw = source.read(MAX_TEXT + 1)
        if len(raw) > MAX_TEXT:
            raise ValueError('Testo OCR troppo grande')
        json.dump(extract(raw.decode('utf-8')), sys.stdout, ensure_ascii=False, indent=2)
        print()
    except (OSError, UnicodeError, ValueError) as exc:
        parser.exit(1, f'Impossibile elaborare OCR: {type(exc).__name__}\n')


if __name__ == '__main__':
    main()
