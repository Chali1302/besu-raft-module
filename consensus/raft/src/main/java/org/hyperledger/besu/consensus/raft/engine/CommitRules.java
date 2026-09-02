package org.hyperledger.besu.consensus.raft.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

/**
 * Regla de commit del lider (paper S5.4.2): el lider solo puede avanzar
 * commitIndex a un indice N si N esta replicado en la mayoria del cluster
 * (incluyendose a si mismo) Y ademas log[N].term == currentTerm del lider.
 *
 * La segunda condicion es la que casi siempre se olvida y produce
 * corrupciones sutiles: replicar una entrada de un term antiguo en mayoria
 * NO basta para comitearla directamente (ver Figura 8 del paper); solo se
 * comitea de forma indirecta cuando una entrada del term actual alcanza
 * mayoria por delante de ella.
 */
public final class CommitRules {

    private CommitRules() {
    }

    public static OptionalLong majorityMatchIndex(Map<String, Long> matchIndexByPeer, long selfMatchIndex,
                                                    int clusterSize, RaftLog log, long currentTerm) {
        List<Long> indices = new ArrayList<>(matchIndexByPeer.values());
        indices.add(selfMatchIndex);
        indices.sort(Collections.reverseOrder());

        int majority = clusterSize / 2 + 1;
        if (indices.size() < majority) {
            return OptionalLong.empty();
        }
        long candidateN = indices.get(majority - 1);
        if (candidateN <= 0) {
            return OptionalLong.empty();
        }
        LogEntry entry = log.getEntry(candidateN);
        if (entry == null || entry.term() != currentTerm) {
            return OptionalLong.empty();
        }
        return OptionalLong.of(candidateN);
    }
}
