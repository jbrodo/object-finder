#!/usr/bin/env python3
"""Extract review candidates from the reference-format XLSX; never writes to Supabase."""
import argparse
import json
import posixpath
import re
import sys
import zipfile
from decimal import Decimal, InvalidOperation
from pathlib import Path
from xml.etree import ElementTree as ET

NS = {'s': 'http://schemas.openxmlformats.org/spreadsheetml/2006/main',
      'r': 'http://schemas.openxmlformats.org/officeDocument/2006/relationships',
      'p': 'http://schemas.openxmlformats.org/package/2006/relationships'}
MAX_ARCHIVE = 20 * 1024 * 1024
MAX_UNCOMPRESSED = 80 * 1024 * 1024
MAX_ROWS = 50000


def cell_value(cell, shared):
    if cell is None:
        return ''
    kind = cell.get('t')
    value = cell.findtext('s:v', default='', namespaces=NS)
    if kind == 's':
        try:
            return shared[int(value)]
        except (ValueError, IndexError):
            raise ValueError('Indice stringa condivisa non valido') from None
    if kind == 'inlineStr':
        return ''.join(cell.find('s:is', NS).itertext()) if cell.find('s:is', NS) is not None else ''
    if kind == 'e':
        return ''
    return value


def column(cell):
    match = re.fullmatch(r'([A-Z]+)[1-9][0-9]*', cell.get('r', ''))
    return match.group(1) if match else None


def number(raw):
    raw = raw.strip()
    if not re.fullmatch(r'\+?\d+(?:[.,]\d{1,3})?', raw):
        return None
    try:
        value = Decimal(raw.replace(',', '.'))
        if value < 0 or value >= Decimal('1000000000'):
            return None
        return format(value, 'f')
    except InvalidOperation:
        return None


def extract(file):
    file.seek(0, 2)
    if file.tell() > MAX_ARCHIVE:
        raise ValueError('File troppo grande')
    file.seek(0)
    with zipfile.ZipFile(file) as archive:
        if sum(item.file_size for item in archive.infolist()) > MAX_UNCOMPRESSED:
            raise ValueError('Contenuto decompresso troppo grande')
        names = set(archive.namelist())
        shared = []
        if 'xl/sharedStrings.xml' in names:
            root = ET.fromstring(archive.read('xl/sharedStrings.xml'))
            shared = [''.join(si.itertext()) for si in root.findall('s:si', NS)]
        workbook = ET.fromstring(archive.read('xl/workbook.xml'))
        rels = ET.fromstring(archive.read('xl/_rels/workbook.xml.rels'))
        targets = {r.get('Id'): r.get('Target') for r in rels.findall('p:Relationship', NS)
                   if r.get('Type', '').endswith('/worksheet') and r.get('TargetMode') != 'External'}
        result = {'rows': [], 'warnings': []}
        count = 0
        for sheet in workbook.findall('s:sheets/s:sheet', NS):
            title = sheet.get('name', '')
            target = targets.get(sheet.get(f"{{{NS['r']}}}id"), '')
            path = posixpath.normpath(target.lstrip('/') if target.startswith('/') else posixpath.join('xl', target))
            if not path.startswith('xl/worksheets/') or path not in names:
                result['warnings'].append({'sheet': title, 'reason': 'foglio non disponibile'})
                continue
            root = ET.fromstring(archive.read(path))
            rows = root.findall('s:sheetData/s:row', NS)
            count += len(rows)
            if count > MAX_ROWS:
                raise ValueError('Troppi righi nel file')
            if not rows:
                result['warnings'].append({'sheet': title, 'reason': 'foglio vuoto'})
                continue
            headers = {cell_value(c, shared).strip().upper(): column(c) for c in rows[0].findall('s:c', NS)}
            choices = [('AFS LETTURA', 'cold'), ('ACS LETTURA (MC)', 'hot')]
            measures = [(headers[key], kind) for key, kind in choices if headers.get(key)]
            if not headers.get('APPARTAMENTO') or not measures:
                result['warnings'].append({'sheet': title, 'reason': 'intestazioni non riconosciute'})
                continue
            for row in rows[1:]:
                cells = {column(c): c for c in row.findall('s:c', NS)}
                unit = cell_value(cells.get(headers['APPARTAMENTO']), shared).strip()
                if not unit:
                    continue
                period = cell_value(cells.get(headers.get('ANNO')), shared).strip()
                for col, kind in measures:
                    cell = cells.get(col)
                    raw = cell_value(cell, shared)
                    amount = number(raw)
                    warnings = []
                    if unit.upper() == 'Z':
                        warnings.append('possible_aggregate_or_placeholder')
                    if amount is None:
                        warnings.append('invalid_or_missing_amount')
                    if not period:
                        warnings.append('missing_period')
                    if cell is not None and cell.find('s:f', NS) is not None:
                        warnings.append('formula_cached_value')
                    address = cell.get('r') if cell is not None else col + row.get('r', '?')
                    result['rows'].append({'source_row': f'{title}!{address}', 'unit_label': unit,
                                           'kind': kind, 'period_label': period, 'period_start': None,
                                           'period_end': None, 'amount': amount, 'warnings': warnings})
        return result


def main():
    parser = argparse.ArgumentParser(description='Estrae candidati di consumo da Excel per revisione, senza salvare dati.')
    parser.add_argument('file', type=Path)
    args = parser.parse_args()
    try:
        with args.file.open('rb') as source:
            json.dump(extract(source), sys.stdout, ensure_ascii=False, indent=2)
            print()
    except (OSError, ValueError, KeyError, zipfile.BadZipFile, ET.ParseError) as exc:
        parser.exit(1, f'Impossibile leggere il file: {exc}\n')


if __name__ == '__main__':
    main()
