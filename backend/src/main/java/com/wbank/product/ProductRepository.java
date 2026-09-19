package com.wbank.product;

import com.wbank.product.domain.Product;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductRepository extends JpaRepository<Product, String> {

    List<Product> findAllByOrderByCodeAsc();
}
