package com.wbank.product.domain;

public enum ProductStatus {
    /** On sale: new accounts may be opened. */
    ACTIVE,
    /** No longer sold. Existing accounts continue under their contract. Terminal. */
    WITHDRAWN
}
