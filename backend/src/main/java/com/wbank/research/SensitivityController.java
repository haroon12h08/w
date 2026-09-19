package com.wbank.research;

import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Research sensitivity: how descriptive outcome results change with the horizon, the knowledge
 * cutoff and the population, and why. Every operation records a reproducible artefact.
 * Knowledge cutoffs are always explicit timestamps.
 */
@RestController
@RequestMapping("/api/v1/research/sensitivity")
public class SensitivityController {

    private final SensitivityService sensitivity;

    public SensitivityController(SensitivityService sensitivity) {
        this.sensitivity = sensitivity;
    }

    @PostMapping("/horizons")
    public ResponseEntity<Map<String, Object>> horizons(@RequestBody SensitivityService.HorizonRequest r) {
        return ResponseEntity.status(HttpStatus.CREATED).body(sensitivity.horizons(r));
    }

    @PostMapping("/knowledge-cutoffs")
    public ResponseEntity<Map<String, Object>> knowledge(@RequestBody SensitivityService.KnowledgeRequest r) {
        return ResponseEntity.status(HttpStatus.CREATED).body(sensitivity.knowledge(r));
    }

    @PostMapping("/comparisons")
    public ResponseEntity<Map<String, Object>> compare(@RequestBody SensitivityService.ComparisonRequest r) {
        return ResponseEntity.status(HttpStatus.CREATED).body(sensitivity.compare(r));
    }

    /** Events near the decision time, outcome cutoff and knowledge cutoff, within an explicit window. */
    @PostMapping("/boundaries")
    public ResponseEntity<Map<String, Object>> boundaries(@RequestBody SensitivityService.BoundaryRequest r) {
        return ResponseEntity.status(HttpStatus.CREATED).body(sensitivity.boundaries(r));
    }

    /** Approval-selection boundary, censoring and cross-tabulations for one configuration. */
    @PostMapping("/population")
    public ResponseEntity<Map<String, Object>> population(@RequestBody SensitivityService.Configuration r) {
        return ResponseEntity.status(HttpStatus.CREATED).body(sensitivity.population(r));
    }

    @GetMapping("/reports/{id}")
    public Map<String, Object> report(@PathVariable UUID id) {
        return sensitivity.get(id);
    }

    @GetMapping("/reports/{id}/reproduction")
    public SensitivityService.Reproduction reproduce(@PathVariable UUID id) {
        return sensitivity.reproduce(id);
    }
}
