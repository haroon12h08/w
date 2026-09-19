package com.wbank.platform.money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * A currency as the ledger understands it: a code plus the scale that defines
 * what one minor unit is worth.
 *
 * <p>This is not {@link java.util.Currency}. The bank's supported currencies and
 * their scales are reference data owned by the database (see {@code V1__reference_data.sql}),
 * because the ledger must behave identically regardless of the JDK's locale data.
 *
 * @param code       ISO-4217 alphabetic code, e.g. {@code USD}
 * @param minorUnit  number of decimal places, e.g. {@code 2} for USD, {@code 0} for JPY
 */
public record CurrencyUnit(String code, int minorUnit) {

    public CurrencyUnit {
        Objects.requireNonNull(code, "currency code");
        if (!code.matches("^[A-Z]{3}$")) {
            throw new IllegalArgumentException("Currency code must be three uppercase letters: " + code);
        }
        if (minorUnit < 0 || minorUnit > 4) {
            throw new IllegalArgumentException("Unsupported minor unit scale: " + minorUnit);
        }
    }

    /** 10^minorUnit — the number of minor units in one major unit. */
    public BigDecimal minorUnitsPerMajor() {
        return BigDecimal.TEN.pow(minorUnit);
    }

    /**
     * Converts a major-unit decimal to minor units, refusing any amount that carries
     * more precision than the currency can represent. Rounding is never applied
     * silently: an amount the bank cannot book is an invalid amount.
     */
    public long toMinorUnits(BigDecimal majorAmount) {
        Objects.requireNonNull(majorAmount, "amount");
        BigDecimal scaled;
        try {
            scaled = majorAmount.setScale(minorUnit, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(
                    "Amount %s cannot be represented in %s, which has %d decimal place(s)"
                            .formatted(majorAmount.toPlainString(), code, minorUnit));
        }
        try {
            return scaled.movePointRight(minorUnit).longValueExact();
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(
                    "Amount %s %s exceeds the representable range".formatted(majorAmount.toPlainString(), code));
        }
    }

    public BigDecimal toMajorUnits(long minorUnits) {
        return BigDecimal.valueOf(minorUnits, minorUnit);
    }

    @Override
    public String toString() {
        return code;
    }
}
