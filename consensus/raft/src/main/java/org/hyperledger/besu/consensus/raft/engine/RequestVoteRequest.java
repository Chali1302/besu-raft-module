package org.hyperledger.besu.consensus.raft.engine;

public record RequestVoteRequest(long term, String candidateId, long lastLogIndex, long lastLogTerm) {
}
