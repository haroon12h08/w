package com.wbank;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Bank 4.0 research platform — core financial system.
 *
 * <p>Phase 1 scope: the ledger is the authoritative representation of financial state.
 * Deliberately excluded: agents, ML, simulation, knowledge graph, event bus. See README.
 */
@SpringBootApplication
public class WbankApplication {

    public static void main(String[] args) {
        SpringApplication.run(WbankApplication.class, args);
    }
}
