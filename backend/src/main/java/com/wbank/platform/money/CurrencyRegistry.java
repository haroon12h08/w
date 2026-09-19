package com.wbank.platform.money;

import com.wbank.platform.error.NotFoundException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * In-memory view of the {@code currency} reference table.
 *
 * <p>Currencies change on the timescale of geopolitics, not of requests, so they are
 * loaded once. The database remains the owner of the data; this is a cache, not a
 * second source of truth.
 */
@Component
public class CurrencyRegistry {

    private final Map<String, CurrencyUnit> currencies;

    public CurrencyRegistry(JdbcTemplate jdbcTemplate) {
        Map<String, CurrencyUnit> loaded = new LinkedHashMap<>();
        jdbcTemplate.query(
                "SELECT code, minor_unit FROM currency WHERE active = TRUE ORDER BY code",
                rs -> {
                    String code = rs.getString("code").trim();
                    loaded.put(code, new CurrencyUnit(code, rs.getShort("minor_unit")));
                });
        if (loaded.isEmpty()) {
            throw new IllegalStateException(
                    "No active currencies found. Reference data migration must run before startup.");
        }
        this.currencies = Map.copyOf(loaded);
    }

    public CurrencyUnit require(String code) {
        if (code == null) {
            throw new NotFoundException("currency.unknown", "Currency code is required");
        }
        CurrencyUnit unit = currencies.get(code.trim().toUpperCase());
        if (unit == null) {
            throw new NotFoundException("currency.unknown", "Unsupported currency: " + code);
        }
        return unit;
    }

    public Map<String, CurrencyUnit> all() {
        return currencies;
    }
}
