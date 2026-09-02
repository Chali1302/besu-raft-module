package org.hyperledger.besu.consensus.raft.engine;

public interface RaftClock {
    long nowMillis();
}
