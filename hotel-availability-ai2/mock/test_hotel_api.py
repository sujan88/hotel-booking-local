"""SQLite idempotency contract tests; no external services or packages."""
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from hotel_api import HotelState, canonical_request

BOOKING = {'hotelId': 'h1', 'roomId': 'r1', 'checkIn': '2027-01-01', 'checkOut': '2027-01-02',
           'guests': 2, 'guestName': 'Alex Guest', 'guestEmail': 'alex@example.com'}
WORKER = '''import json,sys
from hotel_api import HotelState,canonical_request
state=HotelState(sys.argv[1], 'success')
print(json.dumps(state.reserve(sys.argv[2],canonical_request(json.loads(sys.argv[3])))))
'''


class IdempotencyTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.database = str(Path(self.directory.name) / 'reservations.sqlite3')
        self.state = HotelState(self.database, 'success')

    def worker(self, key, booking):
        return subprocess.Popen([sys.executable, '-c', WORKER, self.database, key, json.dumps(booking)],
                                cwd=Path(__file__).parent, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)

    def result(self, worker):
        output, error = worker.communicate(timeout=10)
        self.assertEqual(worker.returncode, 0, error)
        return json.loads(output)

    def test_same_key_and_body_replay_original_result(self):
        first = self.state.reserve('booking-key-001', canonical_request(BOOKING))
        second = self.state.reserve('booking-key-001', canonical_request(BOOKING))
        self.assertTrue(first[2])
        self.assertFalse(second[2])
        self.assertEqual(first[1], second[1])
        self.assertEqual(self.state.stats()['reservationCount'], 1)

    def test_same_key_changed_payload_conflicts(self):
        self.state.reserve('booking-key-001', canonical_request(BOOKING))
        status, response, created = self.state.reserve('booking-key-001', canonical_request({**BOOKING, 'roomId': 'r2'}))
        self.assertEqual(status, 409)
        self.assertEqual(response['errorCode'], 'IDEMPOTENCY_CONFLICT')
        self.assertFalse(created)
        self.assertEqual(self.state.stats()['reservationCount'], 1)

    def test_new_key_represents_a_new_booking_operation(self):
        first = self.state.reserve('booking-key-001', canonical_request(BOOKING))
        second = self.state.reserve('booking-key-002', canonical_request(BOOKING))
        self.assertNotEqual(first[1]['reservationId'], second[1]['reservationId'])
        self.assertEqual(self.state.stats()['reservationCount'], 2)

    def test_replay_survives_new_provider_process(self):
        first = self.state.reserve('booking-key-001', canonical_request(BOOKING))
        status, response, created = self.result(self.worker('booking-key-001', BOOKING))
        self.assertEqual(status, 200)
        self.assertEqual(response, first[1])
        self.assertFalse(created)
        self.assertEqual(self.state.stats()['reservationCount'], 1)

    def test_concurrent_provider_processes_commit_once(self):
        first = self.worker('booking-key-001', BOOKING)
        second = self.worker('booking-key-001', BOOKING)
        results = [self.result(first), self.result(second)]
        self.assertEqual(sum(int(result[2]) for result in results), 1)
        self.assertEqual(results[0][1], results[1][1])
        self.assertEqual(self.state.stats()['reservationCount'], 1)

    def test_semantic_normalization(self):
        booking = canonical_request({**BOOKING, 'guests': 2.0, 'guestName': ' Alex   Guest ', 'guestEmail': ' alex@example.com '})
        self.assertEqual(booking, BOOKING)
        with self.assertRaises(ValueError):
            canonical_request({**BOOKING, 'guests': 2.5})


if __name__ == '__main__':
    unittest.main()
