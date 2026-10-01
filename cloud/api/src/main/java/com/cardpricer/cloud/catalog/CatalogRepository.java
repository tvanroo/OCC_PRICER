package com.cardpricer.cloud.catalog;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Repository
public class CatalogRepository {
    private static final String COLUMNS =
            "id, name, set_code, set_name, collector_number, rarity, lang, usd, usd_foil, usd_etched, image_small";
    private static final RowMapper<CardRow> ROW = (rs, i) -> new CardRow(rs.getObject(1, UUID.class), rs.getString(2),
            rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7),
            rs.getBigDecimal(8), rs.getBigDecimal(9), rs.getBigDecimal(10), rs.getString(11));

    private static final int MAX_WORDS = 6;

    private final JdbcTemplate jdbc;

    public CatalogRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Free-text search over a printing's name, set code and collector number. Every word must match one of them,
     * so "bolt", "391", "DMU 391", "dmu #391" and "lightning bolt 2x2" all work. Exact and prefix name matches
     * come first, then newest printings. An optional set code narrows the result further.
     */
    public List<CardRow> search(String query, String set, int limit) {
        String q = query.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        List<String> words = Arrays.stream(q.split(" "))
                .map(w -> w.startsWith("#") ? w.substring(1) : w)
                .filter(w -> !w.isEmpty()).limit(MAX_WORDS).toList();
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM cards WHERE (? = '' OR set_code = ?)");
        List<Object> args = new ArrayList<>(List.of(set, set));
        for (String word : words) {
            sql.append(" AND (lower(name) LIKE ? OR set_code = ? OR collector_number = ?)");
            args.add("%" + escapeLike(word) + "%");
            args.add(word.toUpperCase(Locale.ROOT));
            args.add(word);
        }
        sql.append(" AND (usd IS NOT NULL OR usd_foil IS NOT NULL OR usd_etched IS NOT NULL)"
                + " ORDER BY (lower(name) = ?) DESC, (lower(name) LIKE ?) DESC, released_at DESC NULLS LAST, name, collector_number LIMIT ?");
        args.add(q);
        args.add(escapeLike(q) + "%");
        args.add(limit);
        return jdbc.query(sql.toString(), ROW, args.toArray());
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
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
