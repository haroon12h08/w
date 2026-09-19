package com.wbank.information;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** A financial observation as the pure selection and calculation code sees it. */
public record FactView(UUID id, long seq, UUID seriesId, String kind, String type, String status, Long amountMinor,
                       String currency, String frequency, Long outstandingMinor, LocalDate employmentStartDate,
                       LocalDate appliesFrom, String provenance, String source, String evidenceReference,
                       UUID verifiesFactId, Instant effectiveAt, Instant recordedAt) {}
