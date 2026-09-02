package org.hyperledger.besu.consensus.raft.engine;

public record LogEntry(long term, long index, byte[] command) {
}
