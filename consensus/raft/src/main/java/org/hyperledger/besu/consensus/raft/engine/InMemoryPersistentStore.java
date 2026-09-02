package org.hyperledger.besu.consensus.raft.engine;

import java.util.List;

/**
 * Implementacion sin persistencia real en disco. Usada mientras no se
 * implemente FilePersistentStore (Dia 3); un nodo con este store pierde
 * su estado si el proceso muere.
 */
public final class InMemoryPersistentStore implements PersistentStore {

    private long currentTerm = 0;
    private String votedFor = null;

    @Override
    public synchronized void saveTermAndVote(long currentTerm, String votedFor) {
        this.currentTerm = currentTerm;
        this.votedFor = votedFor;
    }

    @Override
    public synchronized TermAndVote loadTermAndVote() {
        return new TermAndVote(currentTerm, votedFor);
    }

    @Override
    public void appendToLog(List<LogEntry> newEntries) {
        // no-op: el log solo vive en memoria en RaftLog
    }

    @Override
    public void truncateLogFrom(long index) {
        // no-op
    }

    @Override
    public List<LogEntry> loadLog() {
        return List.of();
    }
}
