package org.hyperledger.besu.consensus.raft.engine;

/**
 * Reglas puras de la restriccion de eleccion del paper de Raft (S5.4.1):
 * un votante solo concede su voto si el log del candidato esta al menos
 * tan actualizado como el suyo propio.
 */
public final class ElectionRules {

    private ElectionRules() {
    }

    public static boolean isLogUpToDate(long candidateLastLogTerm, long candidateLastLogIndex,
                                         long voterLastLogTerm, long voterLastLogIndex) {
        if (candidateLastLogTerm != voterLastLogTerm) {
            return candidateLastLogTerm > voterLastLogTerm;
        }
        return candidateLastLogIndex >= voterLastLogIndex;
    }
}
