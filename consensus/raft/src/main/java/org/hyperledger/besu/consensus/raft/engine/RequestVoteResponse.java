package org.hyperledger.besu.consensus.raft.engine;

public record RequestVoteResponse(long term, boolean voteGranted) {
}
