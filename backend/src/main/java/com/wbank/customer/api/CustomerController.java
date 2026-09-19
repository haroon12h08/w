package com.wbank.customer.api;

import com.wbank.customer.CustomerService;
import com.wbank.customer.domain.Customer;
import com.wbank.customer.domain.CustomerStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Customer relationships. Lifecycle is changed only through named actions. */
@RestController
@RequestMapping("/api/v1/customers")
public class CustomerController {

    private final CustomerService customerService;

    public CustomerController(CustomerService customerService) {
        this.customerService = customerService;
    }

    public record OpenCustomerRequest(@NotNull UUID partyId, @Email String email) {}

    public record CustomerResponse(UUID id, String customerNumber, UUID partyId, String email,
                                   CustomerStatus status, Instant onboardedAt, Instant activatedAt,
                                   Instant closedAt) {
        public static CustomerResponse from(Customer c) {
            return new CustomerResponse(c.getId(), c.getCustomerNumber(), c.getPartyId(), c.getEmail(),
                    c.getStatus(), c.getOnboardedAt(), c.getActivatedAt(), c.getClosedAt());
        }
    }

    @PostMapping
    public ResponseEntity<CustomerResponse> open(@Valid @RequestBody OpenCustomerRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(CustomerResponse.from(customerService.open(req.partyId(), req.email())));
    }

    @GetMapping("/{id}")
    public CustomerResponse get(@PathVariable UUID id) {
        return CustomerResponse.from(customerService.require(id));
    }

    @GetMapping
    public Page<CustomerResponse> list(@PageableDefault(size = 20) Pageable pageable) {
        return customerService.list(pageable).map(CustomerResponse::from);
    }

    @PostMapping("/{id}/activate")
    public CustomerResponse activate(@PathVariable UUID id) {
        return CustomerResponse.from(customerService.activate(id));
    }

    @PostMapping("/{id}/suspend")
    public CustomerResponse suspend(@PathVariable UUID id) {
        return CustomerResponse.from(customerService.suspend(id));
    }

    @PostMapping("/{id}/reactivate")
    public CustomerResponse reactivate(@PathVariable UUID id) {
        return CustomerResponse.from(customerService.reactivate(id));
    }

    @PostMapping("/{id}/close")
    public CustomerResponse close(@PathVariable UUID id) {
        return CustomerResponse.from(customerService.close(id));
    }
}
