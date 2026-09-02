package org.hyperledger.besu.consensus.raft.engine;

public record AppendEntriesResponse(long term, boolean success, long matchIndex) {
}
