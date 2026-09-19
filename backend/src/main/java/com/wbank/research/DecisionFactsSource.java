package com.wbank.research;

import com.wbank.obligation.history.DecisionFacts;

/**
 * Where the facts for a (counterfactual) policy evaluation come from. The production source
 * reads the decision snapshot and nothing else ({@link SnapshotDecisionFacts}). The interface
 * exists so that tests can substitute deliberately leaky sources and prove that the leakage
 * tests detect them.
 */
public interface DecisionFactsSource {

    DecisionFacts facts(SourceDecision source);
}
