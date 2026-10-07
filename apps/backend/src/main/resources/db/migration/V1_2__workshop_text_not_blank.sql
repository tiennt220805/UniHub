-- NOT NULL does not stop '' or whitespace-only text, but the Workshop aggregate rejects both
-- (InvalidWorkshopException). Add the matching CHECKs so the database refuses what the domain
-- refuses (architecture.md §6.1: constraints hold even if application logic has a bug).
-- `~ '\S'` = "contains at least one non-whitespace character" (space, tab, newline, CR, FF, VT).
-- Known gap: Postgres \S treats Unicode spaces such as U+00A0 per the database locale, while Java's
-- String.isBlank() uses Character.isWhitespace; edge cases beyond ASCII whitespace may differ.
-- Tightening only: the domain never wrote such rows, so a rolling deploy is safe either way.
ALTER TABLE workshops
    ADD CONSTRAINT chk_workshop_title_not_blank        CHECK (title ~ '\S'),
    ADD CONSTRAINT chk_workshop_speaker_name_not_blank CHECK (speaker_name ~ '\S'),
    ADD CONSTRAINT chk_workshop_location_not_blank     CHECK (location ~ '\S');
