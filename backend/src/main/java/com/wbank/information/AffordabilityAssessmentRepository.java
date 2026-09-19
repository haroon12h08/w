package com.wbank.information;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AffordabilityAssessmentRepository extends JpaRepository<AffordabilityAssessment, UUID> {
}
