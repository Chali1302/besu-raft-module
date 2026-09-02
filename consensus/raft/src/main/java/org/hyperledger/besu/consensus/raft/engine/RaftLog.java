package org.hyperledger.besu.consensus.raft.engine;

import java.util.ArrayList;
import java.util.List;

/**
 * Log replicado, indexado desde 1. La posicion 0 es un centinela
 * (term=0, index=0) que representa "ausencia de entrada previa".
 */
public final class RaftLog {

    private final List<LogEntry> entries = new ArrayList<>();

    public RaftLog() {
        entries.add(new LogEntry(0, 0, new byte[0]));
    }

    public synchronized long lastIndex() {
        return entries.get(entries.size() - 1).index();
    }

    public synchronized long lastTerm() {
        return entries.get(entries.size() - 1).term();
    }

    public synchronized LogEntry getEntry(long index) {
        if (index <= 0 || index >= entries.size()) {
            return null;
        }
        return entries.get((int) index);
    }

    public synchronized boolean matchesAt(long index, long term) {
        if (index == 0) {
            return term == 0;
        }
        LogEntry e = getEntry(index);
        return e != null && e.term() == term;
    }

    public synchronized void appendNew(List<LogEntry> newEntries) {
        entries.addAll(newEntries);
    }

    public synchronized void truncateFrom(long index) {
        if (index < entries.size()) {
            entries.subList((int) index, entries.size()).clear();
        }
    }

    public synchronized List<LogEntry> entriesFrom(long index) {
        if (index >= entries.size()) {
            return List.of();
        }
        return new ArrayList<>(entries.subList((int) index, entries.size()));
    }
}
