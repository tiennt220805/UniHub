-- The pass condition (performance-load.md §4): the storage-layer invariant, run on the Master.
-- Both queries must return no rows.
\echo '(a) seats_taken > capacity:'
SELECT id FROM workshops WHERE seats_taken > capacity;
\echo '(b) held-or-sold above capacity, or the counter disagreeing with the rows:'
SELECT w.id, w.capacity, w.seats_taken, COUNT(r.id) AS holding_rows
FROM workshops w
LEFT JOIN registrations r ON r.workshop_id = w.id AND r.status IN ('RESERVED', 'CONFIRMED')
GROUP BY w.id, w.capacity, w.seats_taken
HAVING COUNT(r.id) > w.capacity OR COUNT(r.id) <> w.seats_taken;
\echo 'the workshop under test:'
SELECT w.capacity, w.seats_taken, COUNT(r.id) AS holding_rows
FROM workshops w
LEFT JOIN registrations r ON r.workshop_id = w.id AND r.status IN ('RESERVED', 'CONFIRMED')
WHERE w.id = :'workshop_id'
GROUP BY w.capacity, w.seats_taken;
