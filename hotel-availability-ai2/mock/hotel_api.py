#!/usr/bin/env python3
"""Local test hotel API. Reservation side effects and idempotency records share a SQLite transaction."""
import argparse
from contextlib import closing
import hashlib
import json
from datetime import date
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import re
import sqlite3
from threading import Lock
import time
import uuid
from urllib.parse import urlparse

MODES = {'success', 'retry', 'failure', 'bad-request', 'timeout', 'commit-then-fail', 'commit-then-timeout'}
FIELDS = {'hotelId', 'roomId', 'checkIn', 'checkOut', 'guests', 'guestName', 'guestEmail'}


def unique_object(pairs):
    value = {}
    for key, item in pairs:
        if key in value:
            raise ValueError('Duplicate JSON field')
        value[key] = item
    return value


def canonical_request(payload):
    if not isinstance(payload, dict) or set(payload) != FIELDS:
        raise ValueError('Provide all seven booking fields')
    result = dict(payload)
    for field in FIELDS - {'guests'}:
        if not isinstance(result[field], str):
            raise ValueError('Expected text fields')
    for field in ('hotelId', 'roomId', 'guestEmail'):
        result[field] = result[field].strip()
    result['guestName'] = re.sub(r'\s+', ' ', result['guestName'].strip(), flags=re.ASCII)
    for field in ('hotelId', 'roomId'):
        if not re.fullmatch(r'[A-Za-z0-9_-]{1,80}', result[field]):
            raise ValueError('Invalid identifier')
    first, last = date.fromisoformat(result['checkIn']), date.fromisoformat(result['checkOut'])
    if first.isoformat() != result['checkIn'] or last.isoformat() != result['checkOut'] or last <= first:
        raise ValueError('Invalid date range')
    guests = result['guests']
    if isinstance(guests, bool) or not isinstance(guests, (int, float)) or int(guests) != guests or not 1 <= guests <= 20:
        raise ValueError('Invalid guest count')
    result['guests'] = int(guests)
    if not result['guestName'] or len(result['guestName']) > 120 or len(result['guestEmail']) > 254 or not re.fullmatch(r'[^@\s]+@[^@\s]+\.[^@\s]+', result['guestEmail']):
        raise ValueError('Invalid guest details')
    return result


class HotelState:
    def __init__(self, database, mode):
        self.database = str(database)
        Path(self.database).parent.mkdir(parents=True, exist_ok=True)
        self.mode, self.read_attempts, self.write_attempts = mode, 0, 0
        self.lock = Lock()
        with closing(sqlite3.connect(self.database)) as connection:
            connection.execute('''CREATE TABLE IF NOT EXISTS reservations (
                idempotency_key TEXT PRIMARY KEY,
                request_hash TEXT NOT NULL,
                request_json TEXT NOT NULL,
                response_json TEXT NOT NULL,
                reservation_id TEXT UNIQUE NOT NULL,
                created_at TEXT DEFAULT CURRENT_TIMESTAMP)''')
            connection.commit()

    def reserve(self, key, payload):
        canonical = json.dumps(payload, sort_keys=True, separators=(',', ':'), ensure_ascii=False)
        fingerprint = hashlib.sha256(canonical.encode('utf-8')).hexdigest()
        # BEGIN IMMEDIATE serializes concurrent requests, including separate processes using this DB.
        with closing(sqlite3.connect(self.database, timeout=10, isolation_level=None)) as connection:
            connection.execute('BEGIN IMMEDIATE')
            row = connection.execute('SELECT request_hash, response_json FROM reservations WHERE idempotency_key=?', (key,)).fetchone()
            if row:
                connection.commit()
                if row[0] != fingerprint:
                    return 409, {'errorCode': 'IDEMPOTENCY_CONFLICT', 'message': 'Key is already bound to different booking details'}, False
                return 200, json.loads(row[1]), False
            response = {'reservationId': 'res-' + uuid.uuid4().hex, 'status': 'CONFIRMED'}
            connection.execute('INSERT INTO reservations (idempotency_key,request_hash,request_json,response_json,reservation_id) VALUES (?,?,?,?,?)',
                               (key, fingerprint, canonical, json.dumps(response), response['reservationId']))
            connection.commit()
            return 201, response, True

    def stats(self):
        with closing(sqlite3.connect(self.database)) as connection:
            ids = [row[0] for row in connection.execute('SELECT reservation_id FROM reservations ORDER BY reservation_id')]
        with self.lock:
            return {'reservationCount': len(ids), 'reservationIds': ids, 'writeAttempts': self.write_attempts}


def create_server(port=9090, mode='success', database='mock/reservations.sqlite3'):
    state = HotelState(database, mode)

    class Handler(BaseHTTPRequestHandler):
        def read_json(self):
            if self.headers.get('Transfer-Encoding', '').lower() == 'chunked':
                payload = bytearray()
                while True:
                    line = self.rfile.readline(128)
                    size = int(line.split(b';', 1)[0].strip(), 16)
                    if size < 0 or len(payload) + size > 16384:
                        raise ValueError('Invalid request size')
                    if size == 0:
                        # Consume the terminating CRLF (trailers are not used by these clients).
                        if self.rfile.readline(128) != b'\r\n':
                            raise ValueError('Unexpected chunk trailers')
                        break
                    chunk = self.rfile.read(size)
                    if len(chunk) != size or self.rfile.read(2) != b'\r\n':
                        raise ValueError('Invalid chunk framing')
                    payload.extend(chunk)
            else:
                size = int(self.headers.get('Content-Length', '0'))
                if not 0 < size <= 16384:
                    raise ValueError('Invalid request size')
                payload = self.rfile.read(size)
            return json.loads(payload, object_pairs_hook=unique_object)

        def do_GET(self):
            path = urlparse(self.path).path
            if path == '/admin/reservations':
                self.respond(200, state.stats())
                return
            if not path.startswith('/hotels/') or not path.endswith('/availability'):
                self.respond(404, {'error': 'Unknown endpoint'})
                return
            with state.lock:
                state.read_attempts += 1
                attempt, mode = state.read_attempts, state.mode
            print(f'Hotel availability attempt={attempt} mode={mode}', flush=True)
            if mode == 'timeout':
                time.sleep(3)
            if mode == 'bad-request':
                self.respond(400, {'error': 'Invalid search'})
            elif mode == 'failure' or (mode == 'retry' and attempt <= 2):
                self.respond(503, {'error': 'Temporarily unavailable'})
            else:
                self.respond(200, [{'roomId': 'r1', 'roomType': 'King', 'price': 120.00, 'currency': 'USD'}])

        def do_POST(self):
            path = urlparse(self.path).path
            # Local-only test controls. Never implement these admin endpoints in a public API.
            if path == '/admin/mode':
                try:
                    mode = self.read_json()['mode']
                    if mode not in MODES:
                        raise ValueError()
                    with state.lock:
                        state.mode, state.read_attempts, state.write_attempts = mode, 0, 0
                    self.respond(200, {'mode': mode})
                except (ValueError, KeyError, TypeError):
                    self.respond(400, {'error': 'Invalid mode'})
                return
            if path != '/reservations':
                self.respond(404, {'error': 'Unknown endpoint'})
                return
            key = self.headers.get('Idempotency-Key', '')
            if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._:-]{7,127}', key):
                self.respond(400, {'error': 'A valid Idempotency-Key is required'})
                return
            try:
                payload = canonical_request(self.read_json())
            except (ValueError, TypeError, OverflowError):
                self.respond(400, {'error': 'Invalid reservation details'})
                return
            with state.lock:
                state.write_attempts += 1
                attempt, mode = state.write_attempts, state.mode
            print(f'Hotel reservation POST attempt={attempt} mode={mode}', flush=True)
            if mode == 'bad-request':
                self.respond(400, {'error': 'Reservation rejected'})
                return
            if mode == 'failure' or (mode == 'retry' and attempt <= 2):
                self.respond(503, {'error': 'Temporarily unavailable'})
                return
            if mode == 'timeout':
                time.sleep(3)
            status, response, created = state.reserve(key, payload)
            if created:
                print(f'Reservation side effect committed: {response["reservationId"]}', flush=True)
                if mode == 'commit-then-fail':
                    self.respond(503, {'error': 'Response lost AFTER commit; retry the same key'})
                    return
                if mode == 'commit-then-timeout':
                    time.sleep(3)
            self.respond(status, response)

        def respond(self, status, body):
            payload = json.dumps(body).encode('utf-8')
            try:
                self.send_response(status)
                self.send_header('Content-Type', 'application/json')
                self.send_header('Content-Length', str(len(payload)))
                self.end_headers()
                self.wfile.write(payload)
            except (BrokenPipeError, ConnectionResetError):
                pass

    server = ThreadingHTTPServer(('127.0.0.1', port), Handler)
    server.state = state
    return server


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--mode', choices=sorted(MODES), default='success')
    parser.add_argument('--port', type=int, default=9090)
    parser.add_argument('--db', default='mock/reservations.sqlite3', help='Persistent local reservation/idempotency database')
    args = parser.parse_args()
    server = create_server(args.port, args.mode, args.db)
    print(f'Mock hotel API: http://127.0.0.1:{server.server_port}', flush=True)
    print(f'mode={args.mode}; database={args.db}; Ctrl+C stops the server.', flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
