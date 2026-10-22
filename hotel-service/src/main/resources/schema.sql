CREATE TABLE IF NOT EXISTS rooms (
 hotel_id VARCHAR(80) NOT NULL, room_id VARCHAR(80) NOT NULL,
 nightly_cents BIGINT NOT NULL CHECK (nightly_cents > 0),
 PRIMARY KEY(hotel_id, room_id)
);
INSERT INTO rooms(hotel_id,room_id,nightly_cents)
 SELECT 'h1','r1',12000 WHERE NOT EXISTS (SELECT 1 FROM rooms WHERE hotel_id='h1' AND room_id='r1');
INSERT INTO rooms(hotel_id,room_id,nightly_cents)
 SELECT 'h1','r2',15000 WHERE NOT EXISTS (SELECT 1 FROM rooms WHERE hotel_id='h1' AND room_id='r2');
CREATE TABLE IF NOT EXISTS reservations (
 id VARCHAR(36) PRIMARY KEY, idempotency_key VARCHAR(128) NOT NULL UNIQUE,
 request_hash VARCHAR(64) NOT NULL, hotel_id VARCHAR(80) NOT NULL, room_id VARCHAR(80) NOT NULL,
 check_in DATE NOT NULL, check_out DATE NOT NULL, guests INTEGER NOT NULL,
 guest_name VARCHAR(120) NOT NULL, guest_email VARCHAR(254) NOT NULL,
 payment_token VARCHAR(100) NOT NULL, payment_key VARCHAR(80) NOT NULL UNIQUE,
 amount_cents BIGINT NOT NULL, status VARCHAR(30) NOT NULL, held BOOLEAN NOT NULL,
 created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL,
 FOREIGN KEY(hotel_id,room_id) REFERENCES rooms(hotel_id,room_id)
);
CREATE INDEX IF NOT EXISTS inventory_lookup ON reservations(hotel_id,room_id,held,check_in,check_out);
CREATE TABLE IF NOT EXISTS outbox (
 id VARCHAR(36) PRIMARY KEY, reservation_id VARCHAR(36) NOT NULL REFERENCES reservations(id),
 event_type VARCHAR(40) NOT NULL, state VARCHAR(12) NOT NULL,
 attempts INTEGER NOT NULL DEFAULT 0, available_at BIGINT NOT NULL,
 lease_until BIGINT NOT NULL DEFAULT 0, owner VARCHAR(36), last_error VARCHAR(80),
 UNIQUE(reservation_id,event_type)
);
CREATE INDEX IF NOT EXISTS outbox_ready ON outbox(state,available_at,lease_until);
-- Simulated consumers: effect and inbox marker share one local transaction.
CREATE TABLE IF NOT EXISTS notification_inbox (
 event_id VARCHAR(36) NOT NULL, channel VARCHAR(12) NOT NULL,
 reservation_id VARCHAR(36) NOT NULL, processed_at BIGINT NOT NULL,
 PRIMARY KEY(event_id,channel)
);
-- Provider-owned simulation state, accessed through HTTP, not the reservation transaction.
CREATE TABLE IF NOT EXISTS payment_methods (token VARCHAR(100) PRIMARY KEY);
CREATE TABLE IF NOT EXISTS simulated_payments (
 payment_key VARCHAR(80) PRIMARY KEY, request_hash VARCHAR(64) NOT NULL,
 status VARCHAR(12) NOT NULL, amount_cents BIGINT NOT NULL
);
CREATE TABLE IF NOT EXISTS simulator_settings (id INTEGER PRIMARY KEY, mode VARCHAR(30) NOT NULL);
INSERT INTO simulator_settings SELECT 1,'SUCCESS' WHERE NOT EXISTS(SELECT 1 FROM simulator_settings WHERE id=1);

CREATE TABLE IF NOT EXISTS notification_deliveries (
 event_id VARCHAR(36) NOT NULL, channel VARCHAR(12) NOT NULL,
 reservation_id VARCHAR(36) NOT NULL, state VARCHAR(12) NOT NULL,
 PRIMARY KEY(event_id,channel)
);
