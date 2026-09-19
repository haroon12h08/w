package com.wbank.product;

import com.wbank.platform.audit.AuditTrail;
import com.wbank.platform.context.RequestContext;
import com.wbank.platform.error.NotFoundException;
import com.wbank.product.domain.Product;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The product catalogue. Products are reference data seeded by migration; the only
 * lifecycle action is withdrawing one from sale.
 */
@Service
public class ProductService {

    private final ProductRepository products;
    private final AuditTrail auditTrail;
    private final Clock clock;

    public ProductService(ProductRepository products, AuditTrail auditTrail, Clock clock) {
        this.products = products;
        this.auditTrail = auditTrail;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<Product> catalogue() {
        return products.findAllByOrderByCodeAsc();
    }

    @Transactional(readOnly = true)
    public Product require(String code) {
        return products.findById(code == null ? "" : code.strip().toUpperCase())
                .orElseThrow(() -> new NotFoundException("product.not_found", "Unknown product: " + code));
    }

    @Transactional
    public Product withdraw(String code) {
        Product product = require(code);
        product.withdraw(clock.instant());
        products.save(product);
        auditTrail.record("product.withdrawn", "product", UUID.nameUUIDFromBytes(code.getBytes()),
                RequestContext.forOperation("product.withdraw"), Map.of("productCode", product.getCode()));
        return product;
    }
}
