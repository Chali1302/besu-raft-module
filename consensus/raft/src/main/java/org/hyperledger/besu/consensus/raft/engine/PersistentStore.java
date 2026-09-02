package org.hyperledger.besu.consensus.raft.engine;

import java.util.List;

public interface PersistentStore {

    void saveTermAndVote(long currentTerm, String votedFor);

    TermAndVote loadTermAndVote();

    void appendToLog(List<LogEntry> newEntries);

    void truncateLogFrom(long index);

    List<LogEntry> loadLog();

    record TermAndVote(long currentTerm, String votedFor) {
    }
}
