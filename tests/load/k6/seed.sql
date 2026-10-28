-- Seeds Scenario B (performance-load.md §2.2, §2.3): one PUBLISHED free workshop with `remaining` seats
-- left, the seats already gone held by REAL registrations (so the invariant in verify.sql, "rows
-- holding a seat = seats_taken", holds over the whole table), and `contenders` users with no seat.
-- Straight into the load-test Master, not through the API, so no measured request is spent on setup.
--
--   psql -v ON_ERROR_STOP=1 -v run_tag=ab12 -v workshop_id=<uuid> -v capacity=100 -v remaining=10 \
--        -v contenders=500 -f seed.sql
BEGIN;

INSERT INTO workshops (id, title, speaker_name, location, start_time, end_time,
                       capacity, seats_taken, is_paid, status)
VALUES (:'workshop_id', 'K6 adversarial ' || :'run_tag', 'K6 Speaker', 'Load Test Hall',
        now() + interval '1 day', now() + interval '2 days',
        :capacity, :capacity - :remaining, false, 'PUBLISHED');

-- the students who already hold a seat
INSERT INTO users (email, full_name)
SELECT 'k6-' || :'run_tag' || '-h' || n || '@loadtest.local', 'K6 Holder ' || n
FROM generate_series(1, :capacity - :remaining) AS n;

INSERT INTO registrations (workshop_id, user_id, ticket_type, status)
SELECT :'workshop_id', u.id, 'FREE', 'CONFIRMED'
FROM users u
WHERE u.email LIKE 'k6-' || :'run_tag' || '-h%@loadtest.local';

-- the contenders; the order of their ids is the order of the VUs
INSERT INTO users (email, full_name)
SELECT 'k6-' || :'run_tag' || '-c' || lpad(n::text, 4, '0') || '@loadtest.local', 'K6 Contender ' || n
FROM generate_series(1, :contenders) AS n;

COMMIT;
