package org.hyperledger.besu.consensus.raft.engine;

public interface RaftScheduler {

    Cancellable scheduleOnce(Runnable task, long delayMs);

    Cancellable scheduleAtFixedRate(Runnable task, long initialDelayMs, long periodMs);

    void shutdown();

    @FunctionalInterface
    interface Cancellable {
        void cancel();
    }
}
