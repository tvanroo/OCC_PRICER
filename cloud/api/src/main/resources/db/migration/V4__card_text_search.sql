-- Wider card search: the rest of the card (type line, rules text, flavor text, artist) becomes searchable,
-- set names match by substring, and collector numbers match with or without leading zeros ("0116" finds 116).
ALTER TABLE cards ADD COLUMN type_line text;
ALTER TABLE cards ADD COLUMN oracle_text text;
ALTER TABLE cards ADD COLUMN flavor_text text;
ALTER TABLE cards ADD COLUMN artist text;
ALTER TABLE cards ADD COLUMN card_text text GENERATED ALWAYS AS (lower(
    coalesce(type_line, '') || ' ' || coalesce(oracle_text, '') || ' ' || coalesce(flavor_text, '') || ' ' || coalesce(artist, ''))) STORED;
CREATE INDEX cards_text_trgm ON cards USING gin (card_text gin_trgm_ops);
CREATE INDEX cards_set_name_trgm ON cards USING gin (lower(set_name) gin_trgm_ops);

DROP INDEX cards_collector_number;
CREATE INDEX cards_collector_number_unpadded ON cards (ltrim(collector_number, '0'));
