package org.hyperledger.besu.consensus.raft.engine;

@FunctionalInterface
public interface StateMachine {
    byte[] apply(byte[] command);
}
