-- Card search also matches a bare collector number ("391"); set code lookups use cards_set_number.
CREATE INDEX cards_collector_number ON cards (collector_number);
