package org.hyperledger.besu.consensus.raft.engine;

import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * (De)serializacion manual, mensaje a mensaje, para que la forma exacta de
 * cada RPC de Raft quede visible en el codigo (sin reflection generica).
 * Usa Jackson (ya presente en Besu) en vez de org.json, que no esta en la
 * lista blanca de verificacion de dependencias de Gradle de este proyecto.
 */
public final class JsonCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonCodec() {
    }

    public static String toJson(RequestVoteRequest r) {
        ObjectNode o = MAPPER.createObjectNode();
        o.put("term", r.term());
        o.put("candidateId", r.candidateId());
        o.put("lastLogIndex", r.lastLogIndex());
        o.put("lastLogTerm", r.lastLogTerm());
        return o.toString();
    }

    public static RequestVoteRequest requestVoteRequestFromJson(String json) {
        JsonNode o = readTree(json);
        return new RequestVoteRequest(o.get("term").asLong(), o.get("candidateId").asText(),
                o.get("lastLogIndex").asLong(), o.get("lastLogTerm").asLong());
    }

    public static String toJson(RequestVoteResponse r) {
        ObjectNode o = MAPPER.createObjectNode();
        o.put("term", r.term());
        o.put("voteGranted", r.voteGranted());
        return o.toString();
    }

    public static RequestVoteResponse requestVoteResponseFromJson(String json) {
        JsonNode o = readTree(json);
        return new RequestVoteResponse(o.get("term").asLong(), o.get("voteGranted").asBoolean());
    }

    public static String toJson(AppendEntriesRequest r) {
        ObjectNode o = MAPPER.createObjectNode();
        o.put("term", r.term());
        o.put("leaderId", r.leaderId());
        o.put("prevLogIndex", r.prevLogIndex());
        o.put("prevLogTerm", r.prevLogTerm());
        ArrayNode entries = o.putArray("entries");
        for (LogEntry e : r.entries()) {
            ObjectNode eo = entries.addObject();
            eo.put("term", e.term());
            eo.put("index", e.index());
            eo.put("command", Base64.getEncoder().encodeToString(e.command()));
        }
        o.put("leaderCommit", r.leaderCommit());
        return o.toString();
    }

    public static AppendEntriesRequest appendEntriesRequestFromJson(String json) {
        JsonNode o = readTree(json);
        List<LogEntry> entries = new ArrayList<>();
        for (JsonNode eo : o.get("entries")) {
            entries.add(new LogEntry(eo.get("term").asLong(), eo.get("index").asLong(),
                    Base64.getDecoder().decode(eo.get("command").asText())));
        }
        return new AppendEntriesRequest(o.get("term").asLong(), o.get("leaderId").asText(),
                o.get("prevLogIndex").asLong(), o.get("prevLogTerm").asLong(), entries,
                o.get("leaderCommit").asLong());
    }

    public static String toJson(AppendEntriesResponse r) {
        ObjectNode o = MAPPER.createObjectNode();
        o.put("term", r.term());
        o.put("success", r.success());
        o.put("matchIndex", r.matchIndex());
        return o.toString();
    }

    public static AppendEntriesResponse appendEntriesResponseFromJson(String json) {
        JsonNode o = readTree(json);
        return new AppendEntriesResponse(o.get("term").asLong(), o.get("success").asBoolean(),
                o.get("matchIndex").asLong());
    }

    private static JsonNode readTree(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }
}
