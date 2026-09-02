package org.hyperledger.besu.consensus.raft.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Random;
import java.util.Set;

/**
 * Orquestador central de Raft: mantiene el estado (rol, term, log,
 * commitIndex...) y aplica exactamente las reglas de la Figura 2 del paper
 * "In Search of an Understandable Consensus Algorithm".
 *
 * Todos los puntos de entrada externos (arranque, timers, handlers HTTP,
 * respuestas asincronas de RPC salientes) son metodos {@code synchronized}
 * sobre esta misma instancia: el estado de Raft se muta siempre bajo un
 * unico lock, evitando condiciones de carrera entre el hilo del scheduler,
 * los hilos del servidor HTTP y los callbacks del cliente HTTP asincrono.
 */
public final class RaftNode {

    private static final long ELECTION_TIMEOUT_MIN_MS = 150;
    private static final long ELECTION_TIMEOUT_MAX_MS = 300;
    private static final long HEARTBEAT_INTERVAL_MS = 50;

    private final String selfId;
    private final ClusterConfig config;
    private final RaftLog log;
    private final PersistentStore store;
    private final RaftTransport transport;
    private final RaftScheduler scheduler;
    private final EventLogger eventLogger;
    private final StateMachine stateMachine;
    private final Random random = new Random();

    private long currentTerm = 0;
    private String votedFor = null;
    private NodeRole role = NodeRole.FOLLOWER;
    private long commitIndex = 0;
    private long lastApplied = 0;
    private String currentLeaderId = null;

    private final Map<String, Long> nextIndex = new HashMap<>();
    private final Map<String, Long> matchIndex = new HashMap<>();
    private Set<String> votesGranted = new HashSet<>();

    private RaftScheduler.Cancellable electionTimer;
    private RaftScheduler.Cancellable heartbeatTimer;

    public RaftNode(String selfId, ClusterConfig config, RaftLog log, PersistentStore store,
                     RaftTransport transport, RaftScheduler scheduler,
                     EventLogger eventLogger, StateMachine stateMachine) {
        this.selfId = selfId;
        this.config = config;
        this.log = log;
        this.store = store;
        this.transport = transport;
        this.scheduler = scheduler;
        this.eventLogger = eventLogger;
        this.stateMachine = stateMachine;
    }

    // ---- Ciclo de vida ----

    public synchronized void start() {
        PersistentStore.TermAndVote tv = store.loadTermAndVote();
        this.currentTerm = tv.currentTerm();
        this.votedFor = tv.votedFor();
        List<LogEntry> persisted = store.loadLog();
        if (!persisted.isEmpty()) {
            log.appendNew(persisted);
        }
        this.role = NodeRole.FOLLOWER;
        logEvent("NODE_STARTED", null);
        resetElectionTimer();
    }

    public synchronized void stop() {
        if (electionTimer != null) {
            electionTimer.cancel();
        }
        if (heartbeatTimer != null) {
            heartbeatTimer.cancel();
        }
        scheduler.shutdown();
    }

    // ---- Timers ----

    private void resetElectionTimer() {
        if (electionTimer != null) {
            electionTimer.cancel();
        }
        long span = ELECTION_TIMEOUT_MAX_MS - ELECTION_TIMEOUT_MIN_MS + 1;
        long timeout = ELECTION_TIMEOUT_MIN_MS + random.nextLong(span);
        electionTimer = scheduler.scheduleOnce(this::onElectionTimeout, timeout);
    }

    private synchronized void onElectionTimeout() {
        if (role == NodeRole.LEADER) {
            return;
        }
        logEvent("ELECTION_TIMEOUT", null);
        becomeCandidate();
    }

    // ---- Transiciones de rol ----

    private void becomeCandidate() {
        currentTerm++;
        role = NodeRole.CANDIDATE;
        votedFor = selfId;
        store.saveTermAndVote(currentTerm, votedFor);
        votesGranted = new HashSet<>();
        votesGranted.add(selfId);
        currentLeaderId = null;
        logEvent("BECAME_CANDIDATE", null);
        resetElectionTimer();

        if (votesGranted.size() >= majority()) {
            // Cluster de tamano 1: el propio voto ya es mayoria. Sin este
            // atajo el nodo se quedaria en CANDIDATE para siempre, porque el
            // resto de la logica de mayoria solo se evalua al recibir
            // respuestas de otros nodos (que aqui nunca llegan).
            becomeLeader();
            return;
        }

        long lastLogIndex = log.lastIndex();
        long lastLogTerm = log.lastTerm();
        RequestVoteRequest req = new RequestVoteRequest(currentTerm, selfId, lastLogIndex, lastLogTerm);
        long termAtSend = currentTerm;

        for (String peerId : config.peerIdsExcludingSelf()) {
            transport.sendRequestVote(peerId, req).whenComplete((resp, err) -> {
                if (err != null || resp == null) {
                    return; // peer inalcanzable: se reintentara en la siguiente eleccion
                }
                handleRequestVoteResponse(peerId, termAtSend, resp);
            });
        }
    }

    private void becomeLeader() {
        role = NodeRole.LEADER;
        currentLeaderId = selfId;
        if (electionTimer != null) {
            electionTimer.cancel();
            electionTimer = null;
        }
        long nextIdx = log.lastIndex() + 1;
        nextIndex.clear();
        matchIndex.clear();
        for (String peerId : config.peerIdsExcludingSelf()) {
            nextIndex.put(peerId, nextIdx);
            matchIndex.put(peerId, 0L);
        }
        logEvent("BECAME_LEADER", null);
        if (heartbeatTimer != null) {
            heartbeatTimer.cancel();
        }
        heartbeatTimer = scheduler.scheduleAtFixedRate(this::broadcastAppendEntries, 0, HEARTBEAT_INTERVAL_MS);
    }

    private void becomeFollowerDueToHigherTerm(long newTerm) {
        currentTerm = newTerm;
        votedFor = null;
        store.saveTermAndVote(currentTerm, votedFor);
        stepDownToFollower();
    }

    private void stepDownToFollower() {
        boolean wasLeader = role == NodeRole.LEADER;
        role = NodeRole.FOLLOWER;
        if (heartbeatTimer != null) {
            heartbeatTimer.cancel();
            heartbeatTimer = null;
        }
        logEvent(wasLeader ? "STEP_DOWN_HIGHER_TERM" : "BECAME_FOLLOWER", null);
        resetElectionTimer();
    }

    // ---- Handlers RPC (lado receptor) ----

    public synchronized RequestVoteResponse handleRequestVote(RequestVoteRequest req) {
        if (req.term() > currentTerm) {
            becomeFollowerDueToHigherTerm(req.term());
        }
        if (req.term() < currentTerm) {
            return new RequestVoteResponse(currentTerm, false);
        }
        boolean canVote = votedFor == null || votedFor.equals(req.candidateId());
        boolean logOk = ElectionRules.isLogUpToDate(req.lastLogTerm(), req.lastLogIndex(), log.lastTerm(), log.lastIndex());
        if (canVote && logOk) {
            votedFor = req.candidateId();
            store.saveTermAndVote(currentTerm, votedFor);
            resetElectionTimer();
            logEvent("VOTE_GRANTED", Map.of("candidateId", req.candidateId()));
            return new RequestVoteResponse(currentTerm, true);
        }
        logEvent("VOTE_DENIED", Map.of("candidateId", req.candidateId()));
        return new RequestVoteResponse(currentTerm, false);
    }

    public synchronized AppendEntriesResponse handleAppendEntries(AppendEntriesRequest req) {
        if (req.term() < currentTerm) {
            return new AppendEntriesResponse(currentTerm, false, log.lastIndex());
        }
        if (req.term() > currentTerm) {
            currentTerm = req.term();
            votedFor = null;
            store.saveTermAndVote(currentTerm, votedFor);
        }
        currentLeaderId = req.leaderId();
        if (role != NodeRole.FOLLOWER) {
            stepDownToFollower();
        } else {
            resetElectionTimer();
        }

        if (req.prevLogIndex() > 0 && !log.matchesAt(req.prevLogIndex(), req.prevLogTerm())) {
            return new AppendEntriesResponse(currentTerm, false, log.lastIndex());
        }

        if (!req.entries().isEmpty()) {
            replicateEntries(req.prevLogIndex(), req.entries());
        }

        if (req.leaderCommit() > commitIndex) {
            commitIndex = Math.min(req.leaderCommit(), log.lastIndex());
            applyCommitted();
        }

        return new AppendEntriesResponse(currentTerm, true, log.lastIndex());
    }

    private void replicateEntries(long prevLogIndex, List<LogEntry> entries) {
        long idx = prevLogIndex + 1;
        long truncatedFrom = -1;
        List<LogEntry> toPersist = new ArrayList<>();
        for (LogEntry e : entries) {
            LogEntry existing = log.getEntry(idx);
            if (existing != null && existing.term() != e.term()) {
                log.truncateFrom(idx);
                truncatedFrom = idx;
                existing = null;
            }
            if (existing == null) {
                log.appendNew(List.of(e));
                toPersist.add(e);
            }
            idx++;
        }
        if (truncatedFrom >= 0) {
            store.truncateLogFrom(truncatedFrom);
        }
        if (!toPersist.isEmpty()) {
            store.appendToLog(toPersist);
        }
    }

    private void applyCommitted() {
        while (lastApplied < commitIndex) {
            lastApplied++;
            LogEntry entry = log.getEntry(lastApplied);
            if (entry != null && stateMachine != null) {
                stateMachine.apply(entry.command());
            }
            logEvent("ENTRY_APPLIED", Map.of("index", lastApplied));
        }
    }

    // ---- Respuestas RPC (lado emisor) ----

    private synchronized void handleRequestVoteResponse(String peerId, long termAtSend, RequestVoteResponse resp) {
        if (currentTerm != termAtSend || role != NodeRole.CANDIDATE) {
            return; // respuesta obsoleta de una eleccion anterior
        }
        if (resp.term() > currentTerm) {
            becomeFollowerDueToHigherTerm(resp.term());
            return;
        }
        if (resp.voteGranted()) {
            votesGranted.add(peerId);
            if (votesGranted.size() >= majority()) {
                becomeLeader();
            }
        }
    }

    private synchronized void handleAppendEntriesResponse(String peerId, AppendEntriesResponse resp) {
        if (resp.term() > currentTerm) {
            becomeFollowerDueToHigherTerm(resp.term());
            return;
        }
        if (role != NodeRole.LEADER) {
            return;
        }
        if (resp.success()) {
            matchIndex.put(peerId, resp.matchIndex());
            nextIndex.put(peerId, resp.matchIndex() + 1);
            tryAdvanceCommitIndex();
        } else {
            long ni = nextIndex.getOrDefault(peerId, 1L);
            nextIndex.put(peerId, Math.max(1, ni - 1));
        }
    }

    private synchronized void broadcastAppendEntries() {
        if (role != NodeRole.LEADER) {
            return;
        }
        for (String peerId : config.peerIdsExcludingSelf()) {
            long ni = nextIndex.getOrDefault(peerId, log.lastIndex() + 1);
            long prevLogIndex = ni - 1;
            LogEntry prevEntry = prevLogIndex == 0 ? null : log.getEntry(prevLogIndex);
            long prevLogTerm = prevEntry != null ? prevEntry.term() : 0;
            List<LogEntry> entries = log.entriesFrom(ni);
            AppendEntriesRequest req = new AppendEntriesRequest(currentTerm, selfId, prevLogIndex, prevLogTerm, entries, commitIndex);
            transport.sendAppendEntries(peerId, req).whenComplete((resp, err) -> {
                if (err != null || resp == null) {
                    return;
                }
                handleAppendEntriesResponse(peerId, resp);
            });
        }
    }

    private int majority() {
        return config.clusterSize() / 2 + 1;
    }

    private void logEvent(String event, Map<String, Object> extra) {
        eventLogger.log(currentTerm, role.name(), event, extra);
    }

    private static final ObjectMapper STATUS_MAPPER = new ObjectMapper();

    public synchronized String statusJson() {
        ObjectNode o = STATUS_MAPPER.createObjectNode();
        o.put("nodeId", selfId);
        o.put("role", role.name());
        o.put("term", currentTerm);
        o.put("commitIndex", commitIndex);
        o.put("lastApplied", lastApplied);
        o.put("leaderId", currentLeaderId);
        o.put("logLength", log.lastIndex());
        return o.toString();
    }

    public String selfId() {
        return selfId;
    }

    // ---- API de cliente ----

    public synchronized SubmitResult submitCommand(byte[] command) {
        if (role != NodeRole.LEADER) {
            return SubmitResult.notLeader(currentLeaderId);
        }
        long index = log.lastIndex() + 1;
        LogEntry entry = new LogEntry(currentTerm, index, command);
        log.appendNew(List.of(entry));
        store.appendToLog(List.of(entry));
        tryAdvanceCommitIndex();
        broadcastAppendEntries();
        return SubmitResult.accepted(index);
    }

    public synchronized boolean isApplied(long index) {
        return lastApplied >= index;
    }

    public synchronized NodeRole role() {
        return role;
    }

    public synchronized String leaderId() {
        return currentLeaderId;
    }

    private void tryAdvanceCommitIndex() {
        OptionalLong n = CommitRules.majorityMatchIndex(matchIndex, log.lastIndex(), config.clusterSize(), log, currentTerm);
        if (n.isPresent() && n.getAsLong() > commitIndex) {
            commitIndex = n.getAsLong();
            applyCommitted();
        }
    }

    public record SubmitResult(boolean leader, long index, String leaderHint) {
        static SubmitResult notLeader(String leaderHint) {
            return new SubmitResult(false, -1, leaderHint);
        }

        static SubmitResult accepted(long index) {
            return new SubmitResult(true, index, null);
        }
    }
}
