package com.wbank.platform.money;

import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MoneyTest {

    private CurrencyUnit usd;
    private CurrencyUnit jpy;
    private CurrencyUnit eur;

    @BeforeEach
    void setUp() {
        usd = new CurrencyUnit("USD", 2);
        jpy = new CurrencyUnit("JPY", 0);
        eur = new CurrencyUnit("EUR", 2);
    }

    @Test
    @DisplayName("Creates money from minor units and major units correctly")
    void testCreation() {
        Money m1 = Money.ofMinorUnits(1050, usd); // $10.50
        assertEquals(1050, m1.minorUnits());
        assertEquals(new BigDecimal("10.50"), m1.toDecimal());

        Money m2 = Money.ofMajorUnits(new BigDecimal("10.50"), usd);
        assertEquals(m1, m2);

        Money yen = Money.ofMajorUnits(new BigDecimal("1000"), jpy); // ¥1000
        assertEquals(1000, yen.minorUnits());
        assertEquals(new BigDecimal("1000"), yen.toDecimal());
    }

    @Test
    @DisplayName("Addition and subtraction are exact integer arithmetic")
    void testArithmetic() {
        Money m1 = Money.ofMinorUnits(500, usd); // $5.00
        Money m2 = Money.ofMinorUnits(250, usd); // $2.50

        assertEquals(Money.ofMinorUnits(750, usd), m1.plus(m2));
        assertEquals(Money.ofMinorUnits(250, usd), m1.minus(m2));
        assertEquals(Money.ofMinorUnits(-500, usd), m1.negated());
    }

    @Test
    @DisplayName("Overflow throws ArithmeticException")
    void testOverflow() {
        Money max = Money.ofMinorUnits(Long.MAX_VALUE, usd);
        Money one = Money.ofMinorUnits(1, usd);

        assertThrows(ArithmeticException.class, () -> max.plus(one));
    }

    @Test
    @DisplayName("Mixing currencies in arithmetic throws IllegalArgumentException")
    void testCurrencyMismatch() {
        Money usdMoney = Money.ofMinorUnits(100, usd);
        Money eurMoney = Money.ofMinorUnits(100, eur);

        assertThrows(IllegalArgumentException.class, () -> usdMoney.plus(eurMoney));
        assertThrows(IllegalArgumentException.class, () -> usdMoney.minus(eurMoney));
        assertThrows(IllegalArgumentException.class, () -> usdMoney.compareTo(eurMoney));
    }

    @Test
    @DisplayName("Verifies predicate methods for positive, negative, and zero amounts")
    void testPredicates() {
        Money pos = Money.ofMinorUnits(100, usd);
        Money zero = Money.ofMinorUnits(0, usd);
        Money neg = Money.ofMinorUnits(-100, usd);

        assertTrue(pos.isPositive());
        assertFalse(pos.isNegative());
        assertFalse(pos.isZero());

        assertTrue(zero.isZero());
        assertFalse(zero.isPositive());
        assertFalse(zero.isNegative());

        assertTrue(neg.isNegative());
        assertFalse(neg.isPositive());
        assertFalse(neg.isZero());
    }
}
