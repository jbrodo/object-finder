#!/usr/bin/env python3
"""Send review candidates as one Supabase draft using an end-user access token."""
import argparse
import base64
import ipaddress
import json
import os
import sys
import uuid
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import HTTPRedirectHandler, ProxyHandler, Request, build_opener

MAX_JSON = 4 * 1024 * 1024


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def open_request(request, timeout):
    return build_opener(ProxyHandler({}), NoRedirect()).open(request, timeout=timeout)


def endpoint(base):
    url = urlsplit(base)
    if url.username is not None or url.password is not None or url.query or url.fragment or url.path not in ('', '/'):
        raise ValueError('URL Supabase non valida')
    try:
        host = url.hostname or ''
        local = host == 'localhost' or ipaddress.ip_address(host).is_loopback
    except ValueError:
        local = False
    if not host or (url.scheme != 'https' and not (url.scheme == 'http' and local)):
        raise ValueError('Serve HTTPS o un indirizzo HTTP locale')
    try:
        url.port
    except ValueError:
        raise ValueError('Porta non valida') from None
    return base.rstrip('/') + '/rest/v1/rpc/stage_water_candidates'


def build_payload(data, project, building, import_id, source_name, source_type):
    try:
        project, building, import_id = (str(uuid.UUID(value)) for value in (project, building, import_id))
    except (ValueError, TypeError, AttributeError):
        raise ValueError('Identificativo UUID non valido') from None
    if not isinstance(data, dict) or not isinstance(data.get('rows'), list) or not 1 <= len(data['rows']) <= 1000:
        raise ValueError('Servono da 1 a 1000 righe candidate')
    if data.get('warnings') != []:
        raise ValueError('Sono presenti avvisi su tabelle o fogli non importati: controllare prima')
    if not isinstance(source_name, str) or not 1 <= len(source_name.strip()) <= 255 or source_type not in ('xlsx', 'photo', 'manual'):
        raise ValueError('Fonte non valida')
    if any(not isinstance(row, dict) for row in data['rows']):
        raise ValueError('Riga candidata non valida')
    return {'p_import_id': import_id, 'p_project_id': project, 'p_building_id': building,
            'p_source_name': source_name, 'p_source_type': source_type, 'p_rows': data['rows']}


def reject_privileged_key(key):
    if key.startswith('sb_secret_'):
        raise ValueError('Usare una chiave publishable o anon, non una chiave segreta')
    try:
        parts = key.split('.')
        claims = json.loads(base64.urlsafe_b64decode(parts[1] + '=' * (-len(parts[1]) % 4))) if len(parts) == 3 else {}
    except (ValueError, UnicodeError, json.JSONDecodeError):
        claims = {}
    if isinstance(claims, dict) and claims.get('role') == 'service_role':
        raise ValueError('Non usare una chiave service_role')


def submit(base, api_key, user_token, payload):
    if not api_key or not user_token:
        raise ValueError('Chiave pubblica e token utente sono obbligatori')
    reject_privileged_key(api_key)
    reject_privileged_key(user_token)
    target = endpoint(base)
    body = json.dumps(payload, ensure_ascii=False, allow_nan=False).encode('utf-8')
    if len(body) > MAX_JSON:
        raise ValueError('Candidati troppo grandi')
    request = Request(target, data=body, headers={'Content-Type': 'application/json',
        'apikey': api_key, 'Authorization': 'Bearer ' + user_token}, method='POST')
    with open_request(request, timeout=60) as response:
        answer = response.read(1025)
    if len(answer) > 1024 or json.loads(answer) != payload['p_import_id']:
        raise ValueError('Risposta inattesa dal server')
    return payload['p_import_id']


def main():
    parser = argparse.ArgumentParser(description='Invia candidati come bozza, senza confermare i consumi.')
    parser.add_argument('candidates', type=Path)
    parser.add_argument('--project', required=True)
    parser.add_argument('--building', required=True)
    parser.add_argument('--source-name', required=True)
    parser.add_argument('--source-type', required=True, choices=('xlsx', 'photo', 'manual'))
    parser.add_argument('--import-id', default=None, help='UUID della bozza; generato se omesso')
    args = parser.parse_args()
    try:
        with args.candidates.open('rb') as file:
            data = file.read(MAX_JSON + 1)
        if len(data) > MAX_JSON:
            raise ValueError('File candidati troppo grande')
        payload = build_payload(json.loads(data), args.project, args.building,
            args.import_id or str(uuid.uuid4()), args.source_name, args.source_type)
        print('ID bozza:', payload['p_import_id'], file=sys.stderr, flush=True)
        result = submit(os.environ.get('SUPABASE_URL', ''), os.environ.get('SUPABASE_ANON_KEY', ''),
                        os.environ.get('WATER_USER_ACCESS_TOKEN', ''), payload)
        print(result)
    except (OSError, URLError, HTTPError, UnicodeError, ValueError, json.JSONDecodeError) as exc:
        parser.exit(1, f'Invio non riuscito ({type(exc).__name__}). Se la risposta è stata interrotta, verificare l’ID bozza prima di riprovare.\n')


if __name__ == '__main__':
    main()
