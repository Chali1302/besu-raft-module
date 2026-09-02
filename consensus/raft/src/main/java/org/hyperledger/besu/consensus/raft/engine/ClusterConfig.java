package org.hyperledger.besu.consensus.raft.engine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

public record ClusterConfig(String selfId, Map<String, NodeAddress> peers) {

    public record NodeAddress(String host, int port) {
    }

    public NodeAddress selfAddress() {
        return peers.get(selfId);
    }

    public Set<String> peerIdsExcludingSelf() {
        Set<String> ids = new TreeSet<>(peers.keySet());
        ids.remove(selfId);
        return ids;
    }

    public int clusterSize() {
        return peers.size();
    }

    /**
     * Fichero de texto con una linea por nodo: {@code id,host,puerto}.
     * Lineas vacias o que empiecen por # se ignoran.
     */
    public static ClusterConfig loadFromFile(String selfId, Path configFile) throws IOException {
        Map<String, NodeAddress> peers = loadPeersFromFile(configFile);
        if (!peers.containsKey(selfId)) {
            throw new IllegalArgumentException("selfId '" + selfId + "' no aparece en " + configFile);
        }
        return new ClusterConfig(selfId, peers);
    }

    /** Igual que {@link #loadFromFile}, pero sin exigir un selfId (para clientes). */
    public static Map<String, NodeAddress> loadPeersFromFile(Path configFile) throws IOException {
        Map<String, NodeAddress> peers = new LinkedHashMap<>();
        for (String line : Files.readAllLines(configFile)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            String[] parts = trimmed.split(",");
            if (parts.length != 3) {
                throw new IllegalArgumentException("Linea de configuracion invalida: " + line);
            }
            peers.put(parts[0].trim(), new NodeAddress(parts[1].trim(), Integer.parseInt(parts[2].trim())));
        }
        return peers;
    }
}
