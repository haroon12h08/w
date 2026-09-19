package com.wbank.product.api;

import com.wbank.product.ProductService;
import com.wbank.product.domain.Product;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/products")
public class ProductController {

    private final ProductService products;

    public ProductController(ProductService products) {
        this.products = products;
    }

    public record ProductResponse(String code, String name, String family, String status, boolean allowsOverdraft) {
        static ProductResponse from(Product p) {
            return new ProductResponse(p.getCode(), p.getName(), p.getFamily().name(), p.getStatus().name(),
                    p.isAllowsOverdraft());
        }
    }

    @GetMapping
    public List<ProductResponse> catalogue() {
        return products.catalogue().stream().map(ProductResponse::from).toList();
    }

    @PostMapping("/{code}/withdraw")
    public ProductResponse withdraw(@PathVariable String code) {
        return ProductResponse.from(products.withdraw(code));
    }
}
