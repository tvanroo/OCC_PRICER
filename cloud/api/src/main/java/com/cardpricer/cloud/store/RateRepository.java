package com.cardpricer.cloud.store;

import com.cardpricer.model.BuyRateRule;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Repository
public class RateRepository {
    private final JdbcTemplate jdbc;

    public RateRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Ascending by threshold, for display. */
    public List<BuyRateRule> rules(UUID tenant) {
        return jdbc.query("SELECT threshold_min, credit_rate, check_rate FROM buy_rate_rules WHERE tenant_id = ? ORDER BY threshold_min",
                (rs, i) -> new BuyRateRule(rs.getBigDecimal(1), rs.getBigDecimal(2), rs.getBigDecimal(3)), tenant);
    }

    @Transactional
    public void replace(UUID tenant, List<BuyRateRule> rules) {
        jdbc.update("DELETE FROM buy_rate_rules WHERE tenant_id = ?", tenant);
        jdbc.batchUpdate("INSERT INTO buy_rate_rules (tenant_id, threshold_min, credit_rate, check_rate) VALUES (?, ?, ?, ?)",
                rules.stream().map(r -> new Object[]{tenant, r.thresholdMin, r.creditRate, r.checkRate}).toList());
    }

    /** Same lookup as the desktop BuyRateService: the highest matching threshold wins. */
    public static BuyRateRule match(List<BuyRateRule> ascending, BigDecimal value) {
        for (int i = ascending.size() - 1; i >= 0; i--) {
            if (ascending.get(i).matches(value)) return ascending.get(i);
        }
        return new BuyRateRule(BigDecimal.ZERO, new BigDecimal("0.50"), new BigDecimal("0.40"));
    }
}
