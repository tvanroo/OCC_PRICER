package com.cardpricer.cloud.catalog;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class CatalogRepository {
    private static final String COLUMNS =
            "id, name, set_code, set_name, collector_number, rarity, lang, usd, usd_foil, usd_etched, image_small";
    private static final RowMapper<CardRow> ROW = (rs, i) -> new CardRow(rs.getObject(1, UUID.class), rs.getString(2),
            rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7),
            rs.getBigDecimal(8), rs.getBigDecimal(9), rs.getBigDecimal(10), rs.getString(11));

    private final JdbcTemplate jdbc;

    public CatalogRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Name search: exact and prefix matches first, then substring, newest printings first.
     * An optional set code narrows the result, e.g. "bolt 2x2" is not parsed; set is its own field.
     */
    public List<CardRow> search(String query, String set, int limit) {
        String q = query.trim().toLowerCase();
        String like = "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        return jdbc.query("SELECT " + COLUMNS + " FROM cards WHERE lower(name) LIKE ? AND (? = '' OR set_code = ?)"
                        + " AND (usd IS NOT NULL OR usd_foil IS NOT NULL OR usd_etched IS NOT NULL)"
                        + " ORDER BY (lower(name) = ?) DESC, (lower(name) LIKE ?) DESC, released_at DESC NULLS LAST, name LIMIT ?",
                ROW, like, set, set, q, q.replace("%", "") + "%", limit);
    }

    public Optional<CardRow> find(UUID id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM cards WHERE id = ?", ROW, id).stream().findFirst();
    }

    public Optional<Instant> lastImport() {
        return jdbc.query("SELECT max(finished_at) FROM catalog_imports WHERE error IS NULL",
                (rs, i) -> rs.getTimestamp(1)).stream().filter(java.util.Objects::nonNull).map(java.sql.Timestamp::toInstant).findFirst();
    }

    public static BigDecimal scale(BigDecimal value) {
        return value == null ? null : value.setScale(2, java.math.RoundingMode.HALF_UP);
    }
}
