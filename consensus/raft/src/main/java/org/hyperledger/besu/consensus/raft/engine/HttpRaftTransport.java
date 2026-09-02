package org.hyperledger.besu.consensus.raft.engine;


import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

public final class HttpRaftTransport implements RaftTransport {

    private final ClusterConfig config;
    private final HttpClient client;
    private final Duration timeout;

    public HttpRaftTransport(ClusterConfig config, Duration timeout) {
        this.config = config;
        this.timeout = timeout;
        this.client = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .build();
    }

    @Override
    public CompletableFuture<RequestVoteResponse> sendRequestVote(String peerId, RequestVoteRequest request) {
        return send(peerId, "/raft/requestVote", JsonCodec.toJson(request))
                .thenApply(JsonCodec::requestVoteResponseFromJson);
    }

    @Override
    public CompletableFuture<AppendEntriesResponse> sendAppendEntries(String peerId, AppendEntriesRequest request) {
        return send(peerId, "/raft/appendEntries", JsonCodec.toJson(request))
                .thenApply(JsonCodec::appendEntriesResponseFromJson);
    }

    private CompletableFuture<String> send(String peerId, String path, String body) {
        ClusterConfig.NodeAddress addr = config.peers().get(peerId);
        if (addr == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("peer desconocido: " + peerId));
        }
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://" + addr.host() + ":" + addr.port() + path))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        return client.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenApply(HttpResponse::body);
    }
}
