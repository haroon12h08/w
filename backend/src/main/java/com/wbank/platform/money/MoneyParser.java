package com.wbank.platform.money;

import java.math.BigDecimal;
import java.util.regex.Pattern;

/**
 * Parses amounts received over HTTP.
 *
 * <p>Amounts travel as decimal strings in major units ("125.50"), never as JSON numbers:
 * a JSON number may be parsed into a binary double by an intermediary before it ever
 * reaches us. No sign, no exponent; precision beyond the currency's scale is rejected
 * (see {@link CurrencyUnit#toMinorUnits}).
 */
public final class MoneyParser {

    private static final Pattern DECIMAL_AMOUNT = Pattern.compile("^[0-9]{1,19}(\\.[0-9]{1,19})?$");

    private MoneyParser() {}

    public static Money parse(String raw, CurrencyUnit currency) {
        if (raw == null) {
            throw new IllegalArgumentException("Amount is required");
        }
        String trimmed = raw.strip();
        if (!DECIMAL_AMOUNT.matcher(trimmed).matches()) {
            throw new IllegalArgumentException(
                    "Amount must be an unsigned decimal string in major units, e.g. \"125.50\": " + raw);
        }
        return Money.ofMajorUnits(new BigDecimal(trimmed), currency);
    }

    /** Parses and additionally requires the amount to be strictly positive. */
    public static Money parsePositive(String raw, CurrencyUnit currency) {
        Money money = parse(raw, currency);
        if (!money.isPositive()) {
            throw new IllegalArgumentException("Amount must be greater than zero: " + raw);
        }
        return money;
    }
}
