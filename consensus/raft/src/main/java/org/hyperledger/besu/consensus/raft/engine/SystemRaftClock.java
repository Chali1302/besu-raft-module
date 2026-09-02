package org.hyperledger.besu.consensus.raft.engine;

public final class SystemRaftClock implements RaftClock {
    @Override
    public long nowMillis() {
        return System.currentTimeMillis();
    }
}
