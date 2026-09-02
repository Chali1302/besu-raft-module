package org.hyperledger.besu.consensus.raft.engine;


import java.util.List;

public record AppendEntriesRequest(long term, String leaderId, long prevLogIndex, long prevLogTerm,
                                    List<LogEntry> entries, long leaderCommit) {
}
