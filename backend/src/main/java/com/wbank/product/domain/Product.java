package com.wbank.product.domain;

import com.wbank.platform.error.InvalidStateTransitionException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;

/**
 * A contract type the bank offers: the template from which accounts are instantiated.
 *
 * <p>A product has no owner and no money. It answers "what kind of promise is this?",
 * never "how much is owed?". The definition (code, family, overdraft permission) is
 * immutable, because changing it would silently change the contract of every existing
 * account opened under it; a new offering is a new product.
 */
@Entity
@Table(name = "product")
public class Product {

    @Id
    @Column(name = "code", nullable = false, updatable = false)
    private String code;

    @Column(name = "name", nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "family", nullable = false, updatable = false)
    private ProductFamily family;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ProductStatus status;

    @Column(name = "allows_overdraft", nullable = false, updatable = false)
    private boolean allowsOverdraft;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected Product() {
        // for JPA
    }

    public void withdraw(Instant now) {
        InvalidStateTransitionException.require(status == ProductStatus.ACTIVE, "product", code, status,
                ProductStatus.WITHDRAWN);
        this.status = ProductStatus.WITHDRAWN;
        this.updatedAt = now;
    }

    public boolean isOnSale() {
        return status == ProductStatus.ACTIVE;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public ProductFamily getFamily() {
        return family;
    }

    public ProductStatus getStatus() {
        return status;
    }

    public boolean isAllowsOverdraft() {
        return allowsOverdraft;
    }
}
