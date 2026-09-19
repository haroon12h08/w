package com.wbank.information;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Point-in-time selection of financial observations. Pure.
 *
 * <p>An observation is visible at (asOf, knownAt) iff {@code effectiveAt <= asOf} AND
 * {@code recordedAt <= knownAt}: it had become true AND the bank knew it. For each series the
 * current observation is the visible one with the latest effective time, then the latest
 * record time (a later correction of the same moment wins once it is known), then the
 * database insertion order.
 */
public final class FactSelection {

    static final Comparator<FactView> LATEST_LAST = Comparator.comparing(FactView::effectiveAt)
            .thenComparing(FactView::recordedAt).thenComparingLong(FactView::seq);

    private FactSelection() {}

    public static boolean visible(FactView f, Instant asOf, Instant knownAt) {
        return !f.effectiveAt().isAfter(asOf) && !f.recordedAt().isAfter(knownAt);
    }

    public static List<FactView> visible(List<FactView> all, Instant asOf, Instant knownAt) {
        return all.stream().filter(f -> visible(f, asOf, knownAt)).sorted(Comparator.comparingLong(FactView::seq)).toList();
    }

    /** The current observation of every series visible at (asOf, knownAt), in series-creation order. */
    public static List<FactView> currentPerSeries(List<FactView> all, Instant asOf, Instant knownAt) {
        Map<UUID, FactView> current = new LinkedHashMap<>();
        for (FactView f : visible(all, asOf, knownAt)) {
            current.merge(f.seriesId(), f, (a, b) -> LATEST_LAST.compare(a, b) >= 0 ? a : b);
        }
        return List.copyOf(current.values());
    }
}
