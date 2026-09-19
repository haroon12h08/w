package com.wbank.platform.money;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * A monetary amount: an exact integer count of minor units, tagged with its currency.
 *
 * <p><b>Why not BigDecimal alone?</b> A decimal without a currency is not money, and
 * scale drift ({@code 1.5} vs {@code 1.50}) makes equality and summation ambiguous.
 * Storing minor units as a {@code long} makes addition exact, comparison total, and
 * database representation trivial ({@code BIGINT}).
 *
 * <p><b>Why not double?</b> Binary floating point cannot represent 0.10. It is
 * categorically unusable for money.
 *
 * <p>Arithmetic uses {@code Math.*Exact} so that overflow throws instead of silently
 * wrapping. A {@code long} of minor units covers ~92 quadrillion cents, which is
 * beyond any realistic single balance, but the ledger should still fail loudly.
 */
public record Money(long minorUnits, CurrencyUnit currency) implements Comparable<Money> {

    public Money {
        Objects.requireNonNull(currency, "currency");
    }

    public static Money ofMinorUnits(long minorUnits, CurrencyUnit currency) {
        return new Money(minorUnits, currency);
    }

    public static Money ofMajorUnits(BigDecimal majorAmount, CurrencyUnit currency) {
        Objects.requireNonNull(currency, "currency");
        return new Money(currency.toMinorUnits(majorAmount), currency);
    }

    public static Money zero(CurrencyUnit currency) {
        return new Money(0L, currency);
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.addExact(minorUnits, other.minorUnits), currency);
    }

    public Money minus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.subtractExact(minorUnits, other.minorUnits), currency);
    }

    public Money negated() {
        return new Money(Math.negateExact(minorUnits), currency);
    }

    public Money abs() {
        return minorUnits < 0 ? negated() : this;
    }

    public boolean isZero() {
        return minorUnits == 0L;
    }

    public boolean isPositive() {
        return minorUnits > 0L;
    }

    public boolean isNegative() {
        return minorUnits < 0L;
    }

    public boolean isGreaterThanOrEqualTo(Money other) {
        requireSameCurrency(other);
        return minorUnits >= other.minorUnits;
    }

    public boolean isLessThan(Money other) {
        requireSameCurrency(other);
        return minorUnits < other.minorUnits;
    }

    public BigDecimal toDecimal() {
        return currency.toMajorUnits(minorUnits);
    }

    public boolean isSameCurrencyAs(Money other) {
        return currency.code().equals(other.currency.code());
    }

    private void requireSameCurrency(Money other) {
        Objects.requireNonNull(other, "other");
        if (!isSameCurrencyAs(other)) {
            throw new IllegalArgumentException(
                    "Cannot combine %s and %s: mixed-currency arithmetic is not defined"
                            .formatted(currency.code(), other.currency.code()));
        }
    }

    @Override
    public int compareTo(Money other) {
        requireSameCurrency(other);
        return Long.compare(minorUnits, other.minorUnits);
    }

    @Override
    public String toString() {
        return toDecimal().toPlainString() + " " + currency.code();
    }
}
