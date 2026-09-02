#!/bin/bash
# Mide el tiempo de recuperacion en un cluster QBFT real de 4 nodos: a
# diferencia de Raft (lider fijo hasta que falla), QBFT rota el proponente
# en cada bloque por round-robin determinista. Para que el fallo realmente
# dispare el mecanismo de cambio de ronda de QBFT (y no solo deje pasar de
# largo al nodo muerto porque no le tocaba), este script mata exactamente
# al nodo al que le toca proponer el SIGUIENTE bloque.
set -u
cd "$(dirname "$0")"

ADDR1=0x1c0124fe44f96e2c6f78ad17683bd77fd41a912d; PUB1=ba63e01a1b7a84e0934379f0009f5d0c3e3c5fcb92b395b7e6efbae1e083dc611f97add074f3cb29b499dfb2b59efef5aba981eaa270552615ddacb9f3167012
ADDR2=0x415ece3e5f1d5958ac0cb9724a2b7c5fe0cf17ac; PUB2=61bac5348fae7c3147e613dea8341c87b0c71a8ceafc58c81e3d48ff7255178b95129a715d447b340242d1c6ed650cda0f152891af6019b7d9c8d35225301b98
ADDR3=0xb116bd5c817cbe5748255b89f2b2205ade664d48; PUB3=d2634b765f01613bb0f1157d46b19205de59bf60dc5428f04995c9c15dfb99b576dc08abb5d5272828a88587d9292ee50878d520d17aad5b27c29cd754ce4688
ADDR4=0xd6031115f8019dde0f09657cdef5009249466d6b; PUB4=4e40895d0a6e2c7ac9a5c858e6ee806352aa00211ced5d53322d69d6f34fad1be0eac351421cef3b65d427aadc0006a1f0058c0be8e7cee5c0bf22c1834ac7d7
IP1=172.31.0.11; IP2=172.31.0.12; IP3=172.31.0.13; IP4=172.31.0.14

N_TRIALS=${1:-8}
OUT_CSV="dia_qbft_resultados.csv"
echo "trial,nodo_matado,recuperacion_ms" > "$OUT_CSV"

strip_ansi() { sed -E 's/\x1b\[[0-9;]*[a-zA-Z]//g'; }

start_cluster() {
  for N in 1 2 3 4; do
    MSYS_NO_PATHCONV=1 docker rm -f qbft-node$N >/dev/null 2>&1
    find "data$N" -mindepth 1 -maxdepth 1 ! -name key -exec rm -rf {} +
  done
  for N in 1 2 3 4; do
    eval IP=\$IP$N
    BOOT=""
    for M in 1 2 3 4; do
      if [ "$M" != "$N" ]; then
        eval BIP=\$IP$M
        eval BPUB=\$PUB$M
        BOOT="${BOOT}enode://${BPUB}@${BIP}:30303,"
      fi
    done
    BOOT="${BOOT%,}"
    MSYS_NO_PATHCONV=1 docker run -d --name qbft-node$N --network qbft-net --ip $IP --hostname node$N \
      -v "C:\Users\admin\Desktop\TFG\besu-26.7.1:/besu-src" -w /besu-src \
      eclipse-temurin:25-jdk sh /besu-src/build/install/besu/bin/besu \
        --genesis-file=/besu-src/qbft-network/out/genesis-qbft.json --data-path=/besu-src/qbft-network/data$N \
        --p2p-enabled=true --p2p-host=$IP --p2p-port=30303 --sync-min-peers=1 \
        --bootnodes="$BOOT" --rpc-http-enabled=false >/dev/null
  done
}

addr_of() {
  case "$1" in
    1) echo "$ADDR1" ;; 2) echo "$ADDR2" ;; 3) echo "$ADDR3" ;; 4) echo "$ADDR4" ;;
  esac
}

for TRIAL in $(seq 1 "$N_TRIALS"); do
  echo "=== Trial $TRIAL/$N_TRIALS: arrancando cluster QBFT (4 nodos) ==="
  start_cluster
  sleep 75

  # Reconstruye el orden round-robin a partir de los primeros bloques
  # observados: PRODUCER[k] = nodo que produjo el bloque numero k.
  declare -A PRODUCER
  MAXBLOCK=0
  for N in 1 2 3 4; do
    while read -r LINE; do
      BN=$(echo "$LINE" | grep -oE '#[0-9]+' | tr -d '#')
      [ -n "$BN" ] || continue
      PRODUCER[$BN]=$N
      [ "$BN" -gt "$MAXBLOCK" ] && MAXBLOCK=$BN
    done < <(docker logs qbft-node$N 2>&1 | strip_ansi | grep "Produced empty block")
  done

  if [ "$MAXBLOCK" -lt 4 ]; then
    echo "  [trial $TRIAL] no hubo suficientes bloques para inferir el orden, se descarta"
    for N in 1 2 3 4; do MSYS_NO_PATHCONV=1 docker rm -f qbft-node$N >/dev/null 2>&1; done
    unset PRODUCER
    continue
  fi

  # El periodo del round-robin es 4 (numero de validadores): el proponente
  # del bloque MAXBLOCK+1 es el mismo que el del bloque MAXBLOCK+1-4.
  NEXT_BLOCK=$((MAXBLOCK + 1))
  REF_BLOCK=$((NEXT_BLOCK - 4))
  TARGET_N=${PRODUCER[$REF_BLOCK]}
  if [ -z "$TARGET_N" ]; then
    echo "  [trial $TRIAL] no se pudo inferir quien propone el bloque $NEXT_BLOCK, se descarta"
    for N in 1 2 3 4; do MSYS_NO_PATHCONV=1 docker rm -f qbft-node$N >/dev/null 2>&1; done
    unset PRODUCER
    continue
  fi
  TARGET_ADDR=$(addr_of "$TARGET_N")
  echo "  altura actual: $MAXBLOCK. Le toca proponer el bloque $NEXT_BLOCK a: node$TARGET_N ($TARGET_ADDR)"

  KILL_RFC=$(date -u +%Y-%m-%dT%H:%M:%S.%3NZ)
  KILL_EPOCH=$(date -u +%s%3N)
  MSYS_NO_PATHCONV=1 docker rm -f qbft-node$TARGET_N >/dev/null 2>&1
  echo "  nodo $TARGET_N matado en $KILL_RFC"

  SURVIVORS=""
  for N in 1 2 3 4; do [ "$N" != "$TARGET_N" ] && SURVIVORS="$SURVIVORS $N"; done

  NEW_LINE=""
  for i in $(seq 1 80); do
    sleep 0.3
    for N in $SURVIVORS; do
      LINE=$(docker logs --since "$KILL_RFC" qbft-node$N 2>&1 | strip_ansi | grep -E "Produced empty block #$NEXT_BLOCK ")
      if [ -n "$LINE" ]; then NEW_LINE="$LINE"; break 2; fi
    done
  done

  if [ -z "$NEW_LINE" ]; then
    echo "  [trial $TRIAL] el cluster no se recupero en 24s, se descarta"
    echo "$TRIAL,$TARGET_ADDR,TIMEOUT" >> "$OUT_CSV"
  else
    TS=$(echo "$NEW_LINE" | grep -oE '^[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}\.[0-9]{3}\+[0-9]{4}')
    NEW_EPOCH=$(date -d "$TS" +%s%3N)
    RECOVERY_MS=$((NEW_EPOCH - KILL_EPOCH))
    echo "  bloque $NEXT_BLOCK finalmente producido -- recuperacion: ${RECOVERY_MS}ms"
    echo "$TRIAL,$TARGET_ADDR,$RECOVERY_MS" >> "$OUT_CSV"
  fi

  for N in 1 2 3 4; do MSYS_NO_PATHCONV=1 docker rm -f qbft-node$N >/dev/null 2>&1; done
  unset PRODUCER
done

echo
echo "=== Resultados (dia_qbft_resultados.csv) ==="
cat "$OUT_CSV"
