# besu-raft-module

Prototipo de un motor de consenso [Raft](https://raft.github.io/) embebido en [Hyperledger Besu](https://github.com/hyperledger/besu) v26.7.1, desarrollado como Trabajo de Fin de Grado (*"Algoritmo de consenso Raft en Hyperledger Besu"*). Sustituye el mecanismo de elección de proponente de bloques de Besu por un motor Raft verificado de forma independiente ([raft-consensus](https://github.com/Chali1302/raft-consensus)), integrado a través de los puntos de extensión formales del cliente (`BesuControllerBuilder`, `ConsensusContext`, `ProtocolSchedule`, `MiningCoordinator`).

## Qué es este repositorio (y qué no es)

Este repo contiene **únicamente los ficheros escritos o modificados para este TFG**, con la misma ruta relativa que ocupan dentro de un *checkout* completo de Besu — no es un *fork* completo del cliente (esa base de código, sin modificar, es propiedad de Hyperledger/Linux Foundation y no aporta nada verlo aquí repetido). Para compilar y ejecutar el prototipo hace falta aplicar estos ficheros sobre un checkout de [hyperledger/besu](https://github.com/hyperledger/besu) en la versión 26.7.1.

## Estructura

```
consensus/raft/
├── build.gradle
└── src/main/java/org/hyperledger/besu/consensus/raft/
    ├── RaftContext.java                      # ConsensusContext: validadores fijos
    ├── RaftHelpers.java                       # lectura/escritura del proposer en extraData
    ├── RaftProtocolSchedule.java               # reglas reutilizadas de Mainnet + 4 cambios
    ├── RaftBlockHeaderValidationRulesetFactory.java
    ├── blockcreation/
    │   ├── RaftBlockCreator.java
    │   └── RaftMiningCoordinator.java          # coordinador de minería por sondeo
    ├── headervalidationrules/
    │   └── RaftProposerValidationRule.java     # única regla específica del consenso
    └── engine/                                 # motor Raft reutilizado SIN modificar
        └── (RaftNode, RaftLog, ElectionRules, CommitRules, ClusterConfig,
             transporte HTTP, persistencia, planificador... — ver raft-consensus)

app/src/main/java/org/hyperledger/besu/controller/
└── RaftBesuControllerBuilder.java              # ata todas las piezas al arrancar un nodo

config/src/main/java/org/hyperledger/besu/config/
├── RaftConfigOptions.java
└── JsonRaftConfigOptions.java                  # lee la sección "raft": {...} del genesis

raft-network/          # genesis, fichero de cluster, script de experimento y resultados
qbft-network/           # genesis QBFT, script de experimento y resultados (comparativa)
```

El paquete `consensus/raft/.../engine/` es una copia sin modificar del motor verificado en [raft-consensus](https://github.com/Chali1302/raft-consensus) — ahí está también su historial de desarrollo y sus propias instrucciones de compilación/ejecución como librería aislada.

## Decisiones de diseño (resumen — detalle completo en la memoria del TFG)

- **Canal de control separado del canal de datos:** `RequestVote`/`AppendEntries` viajan por un canal HTTP lateral propio (`HttpRaftTransport`/`RaftRpcServer`), independiente de la red *devp2p* que usa Besu para propagar bloques.
- **Sin sellado criptográfico del proponente:** `extraData` codifica la dirección del proponente en claro (20 bytes); `RaftProposerValidationRule` solo comprueba pertenencia al conjunto de validadores del genesis — coherente con el modelo de fallos CFT de Raft (no BFT como QBFT).
- **Conjunto de validadores fijo**, declarado en el genesis, sin altas/bajas dinámicas.
- **Coordinador de minería por sondeo** (cada 1s), no dirigido por eventos como los coordinadores BFT nativos de Besu.

## Genesis de ejemplo

```json
{
  "config": {
    "chainId": 986086,
    "raft": {
      "blockperiodseconds": 5,
      "validators": ["0x...", "0x...", "0x..."]
    }
  }
}
```

## Experimentos

`raft-network/dia6_experimento.sh` y `qbft-network/dia_qbft_experimento.sh` automatizan la comparativa de tiempo de recuperación de liderazgo (Raft, clúster de 3 nodos) frente a tiempo de recuperación de proponente (QBFT, clúster de 4 nodos) tras matar al nodo con potestad de producir el siguiente bloque, sobre contenedores Docker independientes con red y procesos reales. Los `.csv` de resultados incluidos son los datos crudos detrás de las Tablas 4.1 y 4.2 de la memoria.

> **Nota:** en Windows, ejecutar Besu directamente (`besu.bat`) puede quedar bloqueado por políticas de App Control; los experimentos se ejecutaron en contenedores Docker por ese motivo.

## Contexto

Trabajo de Fin de Grado — Grado en Ingeniería Informática, Facultad de Informática, Universidad Complutense de Madrid. Convocatoria de septiembre de 2026.
