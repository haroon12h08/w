package com.wbank.platform.api;

import com.wbank.platform.audit.AuditEvent;
import com.wbank.platform.audit.AuditEventRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/audit")
public class AuditController {

    private final AuditEventRepository auditEventRepository;

    public AuditController(AuditEventRepository auditEventRepository) {
        this.auditEventRepository = auditEventRepository;
    }

    @GetMapping("/events")
    public ResponseEntity<List<AuditEvent>> getEvents(
            @RequestParam(required = false) String aggregateType,
            @RequestParam(required = false) UUID aggregateId,
            @RequestParam(required = false) UUID correlationId) {

        if (aggregateType != null && aggregateId != null) {
            return ResponseEntity.ok(auditEventRepository.findByAggregateTypeAndAggregateIdOrderBySequenceNoAsc(aggregateType, aggregateId));
        }
        if (correlationId != null) {
            return ResponseEntity.ok(auditEventRepository.findByCorrelationIdOrderBySequenceNoAsc(correlationId));
        }
        return ResponseEntity.ok(auditEventRepository.findAll());
    }
}
