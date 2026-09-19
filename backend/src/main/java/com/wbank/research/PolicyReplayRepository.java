package com.wbank.research;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PolicyReplayRepository extends JpaRepository<PolicyReplay, UUID> {
    
}
