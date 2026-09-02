package org.hyperledger.besu.consensus.raft.engine;

import java.util.concurrent.CompletableFuture;

public interface RaftTransport {
    CompletableFuture<RequestVoteResponse> sendRequestVote(String peerId, RequestVoteRequest request);

    CompletableFuture<AppendEntriesResponse> sendAppendEntries(String peerId, AppendEntriesRequest request);
}
