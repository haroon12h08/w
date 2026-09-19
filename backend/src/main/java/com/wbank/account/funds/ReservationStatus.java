package com.wbank.account.funds;

/** ACTIVE holds reduce available funds. CONSUMED: the held money was actually paid. RELEASED: freed unused. */
public enum ReservationStatus {
    ACTIVE,
    CONSUMED,
    RELEASED
}
