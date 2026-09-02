package org.hyperledger.besu.consensus.raft.engine;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

/**
 * Servidor HTTP del canal lateral de Raft embebido en un nodo Besu: expone
 * solo las RPCs de consenso (RequestVote/AppendEntries) y un endpoint de
 * diagnostico, sin nada relativo al cliente KV de la demo standalone.
 */
public final class RaftRpcServer {

    private final HttpServer httpServer;

    public RaftRpcServer(ClusterConfig.NodeAddress bindAddress, RaftNode raftNode) throws IOException {
        this.httpServer = HttpServer.create(new InetSocketAddress(bindAddress.port()), 0);
        httpServer.setExecutor(Executors.newFixedThreadPool(4));
        httpServer.createContext("/raft/requestVote", exchange -> {
            RequestVoteRequest req = JsonCodec.requestVoteRequestFromJson(readBody(exchange));
            respond(exchange, 200, JsonCodec.toJson(raftNode.handleRequestVote(req)));
        });
        httpServer.createContext("/raft/appendEntries", exchange -> {
            AppendEntriesRequest req = JsonCodec.appendEntriesRequestFromJson(readBody(exchange));
            respond(exchange, 200, JsonCodec.toJson(raftNode.handleAppendEntries(req)));
        });
        httpServer.createContext("/admin/status", exchange -> respond(exchange, 200, raftNode.statusJson()));
    }

    public void start() {
        httpServer.start();
    }

    public void stop() {
        httpServer.stop(0);
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
