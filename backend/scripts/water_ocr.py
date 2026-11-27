#!/usr/bin/env python3
"""Experimental local Unlimited-OCR-2 client. Output is unverified document text."""
import argparse
import base64
import ipaddress
import json
import sys
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import HTTPRedirectHandler, ProxyHandler, Request, build_opener

MAX_IMAGE = 10 * 1024 * 1024
MAX_OUTPUT = 4 * 1024 * 1024
MAX_LINE = 1024 * 1024


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def open_request(request, timeout):
    # Ignore system proxies and redirects: document contents must stay on the local machine.
    return build_opener(ProxyHandler({}), NoRedirect()).open(request, timeout=timeout)


def check_endpoint(endpoint):
    url = urlsplit(endpoint)
    if url.scheme != 'http' or url.username is not None or url.password is not None or url.query or url.fragment:
        raise ValueError('Serve un endpoint HTTP locale senza credenziali o parametri')
    try:
        host = url.hostname or ''
        local = host == 'localhost' or ipaddress.ip_address(host).is_loopback
        port = url.port
    except ValueError:
        raise ValueError('Serve un endpoint HTTP locale valido') from None
    if not local or url.path not in ('', '/') or (port is not None and not 1 <= port <= 65535):
        raise ValueError('Serve un endpoint HTTP locale valido')
    return endpoint.rstrip('/') + '/v1/chat/completions'


def recognize(image, endpoint, timeout=180):
    target = check_endpoint(endpoint)
    if not image or len(image) > MAX_IMAGE:
        raise ValueError('Immagine vuota o superiore a 10 MiB')
    if image.startswith(b'\xff\xd8\xff'):
        mime = 'image/jpeg'
    elif image.startswith(b'\x89PNG\r\n\x1a\n'):
        mime = 'image/png'
    else:
        raise ValueError('Sono supportate solo immagini JPEG o PNG')
    encoded = base64.b64encode(image).decode('ascii')
    payload = {'model': 'Unlimited-OCR-2', 'messages': [{'role': 'user', 'content': [
        {'type': 'image_url', 'image_url': {'url': f'data:{mime};base64,{encoded}'}},
        {'type': 'text', 'text': '<PAGE><image>Multi page parsing.'},
    ]}], 'max_tokens': 8000, 'temperature': 0, 'skip_special_tokens': False,
        'images_config': {'crop_mode': True}, 'stream': True}
    request = Request(target, data=json.dumps(payload).encode('utf-8'),
                      headers={'Content-Type': 'application/json'}, method='POST')
    chunks = []
    total = 0
    done = False
    with open_request(request, timeout=timeout) as response:
        while True:
            raw = response.readline(MAX_LINE + 1)
            if not raw:
                break
            if len(raw) > MAX_LINE:
                raise ValueError('Risposta OCR troppo grande')
            line = raw.decode('utf-8').strip()
            if not line.startswith('data:'):
                continue
            data = line[5:].strip()
            if data == '[DONE]':
                done = True
                break
            event = json.loads(data)
            if event.get('error'):
                raise ValueError('Il servizio OCR ha restituito un errore')
            for choice in event.get('choices', []):
                if choice.get('finish_reason') == 'length':
                    raise ValueError('Risposta OCR incompleta: limite dei token raggiunto')
                part = (choice.get('delta') or {}).get('content') or ''
                if not isinstance(part, str):
                    raise ValueError('Formato della risposta OCR non valido')
                total += len(part.encode('utf-8'))
                if total > MAX_OUTPUT:
                    raise ValueError('Risposta OCR troppo grande')
                chunks.append(part)
    if not done or not chunks:
        raise ValueError('Risposta OCR incompleta o vuota')
    return ''.join(chunks)


def main():
    parser = argparse.ArgumentParser(description='Prova Unlimited-OCR-2 su un server locale; nessun dato viene salvato automaticamente.')
    parser.add_argument('image', type=Path)
    parser.add_argument('--endpoint', default='http://127.0.0.1:10000')
    parser.add_argument('--timeout', type=int, default=180)
    args = parser.parse_args()
    if args.timeout < 1 or args.timeout > 3600:
        parser.error('timeout deve essere compreso tra 1 e 3600 secondi')
    try:
        with args.image.open('rb') as image_file:
            data = image_file.read(MAX_IMAGE + 1)
        print(recognize(data, args.endpoint, args.timeout))
    except (OSError, URLError, HTTPError, UnicodeError, ValueError, json.JSONDecodeError) as exc:
        parser.exit(1, f'OCR non riuscito: {type(exc).__name__}\n')


if __name__ == '__main__':
    main()
