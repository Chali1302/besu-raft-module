#!/bin/bash
# Dia 6: mide el tiempo de recuperacion de liderazgo (desde que se mata al
# lider hasta que el nuevo lider produce e importa su primer bloque) en un
# clustre real de 3 nodos Besu+Raft. Cada trial arranca el clustre desde
# cero (Raft no soporta reconfiguracion dinamica: tras un fallo el cluster
# se queda en 2 nodos y no se puede "matar otra vez" sin perder mayoria).
set -u
cd "$(dirname "$0")"

IP1=172.30.0.11; IP2=172.30.0.12; IP3=172.30.0.13
ENODE1=f0301e71ea00c976b21dc00e753af2563776c5b4ecab2875ed7eaff2058928f24cf48db542fca80f6f4f434237b67641d23498071483dcbcedf8ff84d565aead
ENODE2=c16a1984cb7c7d85a16a47e94fd37c3c0736d3101ce7cb480218bf0fc75c0f5c6f3aaf5efb75bc702e7ac514eff52fc668d4f5299fe6c5cd31539ec41d8a679b
ENODE3=1af6700af2e798472a84b4ef9a3080486c5ad31fb718f05ac4ad34f4e055dd2ef35899e49c9cfad4322a08705ef801699759acc0be76597d7c06fa5c07455187

N_TRIALS=${1:-5}
OUT_CSV="dia6_resultados.csv"
echo "trial,lider_muerto,nuevo_lider,recuperacion_ms" > "$OUT_CSV"

strip_ansi() { sed -E 's/\x1b\[[0-9;]*[a-zA-Z]//g'; }

start_cluster() {
  for N in 1 2 3; do
    MSYS_NO_PATHCONV=1 docker rm -f raft-node$N >/dev/null 2>&1
    # borra todo menos la clave del nodo, para conservar la misma identidad
    find "data$N" -mindepth 1 -maxdepth 1 ! -name key -exec rm -rf {} +
  done

  MSYS_NO_PATHCONV=1 docker run -d --name raft-node1 --network raft-net --ip $IP1 --hostname node1 \
    -v "C:\Users\admin\Desktop\TFG\besu-26.7.1:/besu-src" -w /besu-src \
    -e RAFT_CLUSTER_FILE=/besu-src/raft-network/raft-cluster.conf \
    eclipse-temurin:25-jdk sh /besu-src/build/install/besu/bin/besu \
      --genesis-file=/besu-src/raft-network/genesis-raft.json --data-path=/besu-src/raft-network/data1 \
      --p2p-enabled=true --p2p-host=$IP1 --p2p-port=30303 --sync-min-peers=1 \
      --bootnodes="enode://${ENODE2}@${IP2}:30303,enode://${ENODE3}@${IP3}:30303" \
      --rpc-http-enabled=false >/dev/null

  MSYS_NO_PATHCONV=1 docker run -d --name raft-node2 --network raft-net --ip $IP2 --hostname node2 \
    -v "C:\Users\admin\Desktop\TFG\besu-26.7.1:/besu-src" -w /besu-src \
    -e RAFT_CLUSTER_FILE=/besu-src/raft-network/raft-cluster.conf \
    eclipse-temurin:25-jdk sh /besu-src/build/install/besu/bin/besu \
      --genesis-file=/besu-src/raft-network/genesis-raft.json --data-path=/besu-src/raft-network/data2 \
      --p2p-enabled=true --p2p-host=$IP2 --p2p-port=30303 --sync-min-peers=1 \
      --bootnodes="enode://${ENODE1}@${IP1}:30303,enode://${ENODE3}@${IP3}:30303" \
      --rpc-http-enabled=false >/dev/null

  MSYS_NO_PATHCONV=1 docker run -d --name raft-node3 --network raft-net --ip $IP3 --hostname node3 \
    -v "C:\Users\admin\Desktop\TFG\besu-26.7.1:/besu-src" -w /besu-src \
    -e RAFT_CLUSTER_FILE=/besu-src/raft-network/raft-cluster.conf \
    eclipse-temurin:25-jdk sh /besu-src/build/install/besu/bin/besu \
      --genesis-file=/besu-src/raft-network/genesis-raft.json --data-path=/besu-src/raft-network/data3 \
      --p2p-enabled=true --p2p-host=$IP3 --p2p-port=30303 --sync-min-peers=1 \
      --bootnodes="enode://${ENODE1}@${IP1}:30303,enode://${ENODE2}@${IP2}:30303" \
      --rpc-http-enabled=false >/dev/null
}

for TRIAL in $(seq 1 "$N_TRIALS"); do
  echo "=== Trial $TRIAL/$N_TRIALS: arrancando cluster ==="
  start_cluster
  sleep 22

  LEADER_N=""
  for N in 1 2 3; do
    if docker logs raft-node$N 2>&1 | grep -q "Bloque Raft"; then
      LEADER_N=$N
      break
    fi
  done
  if [ -z "$LEADER_N" ]; then
    echo "  [trial $TRIAL] nadie produjo bloques a tiempo, se descarta"
    for N in 1 2 3; do MSYS_NO_PATHCONV=1 docker rm -f raft-node$N >/dev/null 2>&1; done
    continue
  fi

  LEADER_ADDR=$(docker logs raft-node$LEADER_N 2>&1 | strip_ansi | grep -m1 "Bloque Raft" | grep -oE 'lider=0x[0-9a-f]+')
  echo "  lider actual: node$LEADER_N ($LEADER_ADDR)"

  KILL_RFC=$(date -u +%Y-%m-%dT%H:%M:%S.%3NZ)
  KILL_EPOCH=$(date -u +%s%3N)
  MSYS_NO_PATHCONV=1 docker rm -f raft-node$LEADER_N >/dev/null 2>&1
  echo "  lider matado en $KILL_RFC"

  SURVIVORS=""
  for N in 1 2 3; do [ "$N" != "$LEADER_N" ] && SURVIVORS="$SURVIVORS $N"; done

  NEW_LINE=""
  for i in $(seq 1 60); do
    sleep 0.3
    for N in $SURVIVORS; do
      LINE=$(docker logs --since "$KILL_RFC" raft-node$N 2>&1 | strip_ansi | grep -m1 "Bloque Raft")
      if [ -n "$LINE" ]; then NEW_LINE="$LINE"; break 2; fi
    done
  done

  if [ -z "$NEW_LINE" ]; then
    echo "  [trial $TRIAL] no hubo nuevo lider en 18s, se descarta"
    echo "$TRIAL,$LEADER_ADDR,TIMEOUT,NA" >> "$OUT_CSV"
  else
    TS=$(echo "$NEW_LINE" | grep -oE '^[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}\.[0-9]{3}\+[0-9]{4}')
    NEW_LEADER_ADDR=$(echo "$NEW_LINE" | grep -oE 'lider=0x[0-9a-f]+')
    NEW_EPOCH=$(date -d "$TS" +%s%3N)
    RECOVERY_MS=$((NEW_EPOCH - KILL_EPOCH))
    echo "  nuevo lider: $NEW_LEADER_ADDR -- recuperacion: ${RECOVERY_MS}ms"
    echo "$TRIAL,$LEADER_ADDR,$NEW_LEADER_ADDR,$RECOVERY_MS" >> "$OUT_CSV"
  fi

  for N in 1 2 3; do MSYS_NO_PATHCONV=1 docker rm -f raft-node$N >/dev/null 2>&1; done
done

echo
echo "=== Resultados (dia6_resultados.csv) ==="
cat "$OUT_CSV"
