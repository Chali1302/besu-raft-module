/*
 * Copyright contributors to Hyperledger Besu.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package org.hyperledger.besu.controller;

import org.hyperledger.besu.consensus.raft.RaftContext;
import org.hyperledger.besu.consensus.raft.RaftProtocolSchedule;
import org.hyperledger.besu.consensus.raft.blockcreation.RaftBlockCreator;
import org.hyperledger.besu.consensus.raft.blockcreation.RaftMiningCoordinator;
import org.hyperledger.besu.consensus.raft.engine.ClusterConfig;
import org.hyperledger.besu.consensus.raft.engine.EventLogger;
import org.hyperledger.besu.consensus.raft.engine.HttpRaftTransport;
import org.hyperledger.besu.consensus.raft.engine.InMemoryPersistentStore;
import org.hyperledger.besu.consensus.raft.engine.RaftLog;
import org.hyperledger.besu.consensus.raft.engine.RaftNode;
import org.hyperledger.besu.consensus.raft.engine.RaftRpcServer;
import org.hyperledger.besu.consensus.raft.engine.RaftTransport;
import org.hyperledger.besu.consensus.raft.engine.ScheduledExecutorRaftScheduler;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.ethereum.ProtocolContext;
import org.hyperledger.besu.ethereum.blockcreation.MiningCoordinator;
import org.hyperledger.besu.ethereum.chain.Blockchain;
import org.hyperledger.besu.ethereum.core.MiningConfiguration;
import org.hyperledger.besu.ethereum.core.Util;
import org.hyperledger.besu.ethereum.eth.manager.EthProtocolManager;
import org.hyperledger.besu.ethereum.eth.sync.state.SyncState;
import org.hyperledger.besu.ethereum.eth.transactions.TransactionPool;
import org.hyperledger.besu.ethereum.mainnet.ProtocolSchedule;
import org.hyperledger.besu.ethereum.worldstate.WorldStateArchive;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Controller builder del prototipo de consenso Raft (TFG). Modelado sobre
 * {@link CliqueBesuControllerBuilder}. Dia 4: produccion de bloques real, con un cluster Raft de
 * tamano 1 (el propio nodo es siempre lider). El canal HTTP de Raft (RequestVote/AppendEntries)
 * entre varios nodos y las opciones de linea de comandos para un cluster multi-nodo real llegan
 * en el Dia 5.
 */
public class RaftBesuControllerBuilder extends BesuControllerBuilder {

  private static final Logger LOG = LoggerFactory.getLogger(RaftBesuControllerBuilder.class);

  private List<Address> validators;
  private Address selfAddress;

  public RaftBesuControllerBuilder() {}

  @Override
  protected void prepForBuild() {
    validators =
        genesisConfigOptions.getRaftConfigOptions().getValidators().stream()
            .map(Address::fromHexString)
            .collect(Collectors.toList());
    selfAddress = Util.publicKeyToAddress(nodeKey.getPublicKey());
  }

  @Override
  protected MiningCoordinator createMiningCoordinator(
      final ProtocolSchedule protocolSchedule,
      final ProtocolContext protocolContext,
      final TransactionPool transactionPool,
      final MiningConfiguration miningConfiguration,
      final SyncState syncState,
      final EthProtocolManager ethProtocolManager) {

    final String selfId = selfAddress.getBytes().toHexString();

    // Puramente cosmetico para el RPC (campo "miner"/"coinbase" de eth_getBlockByNumber): la
    // legitimidad real del proponente la decide RaftProposerValidationRule leyendo extraData, no
    // este campo, pero sin fijarlo aqui saldria a 0x0 por defecto y confundiria al leer bloques.
    miningConfiguration.setCoinbase(selfAddress);

    // Dia 5: si se define RAFT_CLUSTER_FILE (fichero "id,host,puerto" por linea, mismo formato
    // que el proyecto standalone), se carga un cluster multi-nodo real y el canal HTTP de Raft
    // (RequestVote/AppendEntries) se activa entre los nodos. Sin esa variable, se mantiene el
    // comportamiento del Dia 4: cluster de tamano 1 (el propio nodo, siempre lider), sin exponer
    // opciones --raft-* de CLI (limitacion deliberada de alcance, ver Capitulo 3 de la memoria).
    final String clusterFile = System.getenv("RAFT_CLUSTER_FILE");
    final ClusterConfig clusterConfig;
    try {
      clusterConfig =
          (clusterFile == null || clusterFile.isBlank())
              ? new ClusterConfig(
                  selfId, Map.of(selfId, new ClusterConfig.NodeAddress("127.0.0.1", 0)))
              : ClusterConfig.loadFromFile(selfId, Path.of(clusterFile));
    } catch (final IOException e) {
      throw new IllegalStateException(
          "No se pudo cargar el fichero de cluster Raft " + clusterFile, e);
    }
    final RaftTransport transport = new HttpRaftTransport(clusterConfig, Duration.ofSeconds(2));
    final EventLogger eventLogger;
    try {
      eventLogger =
          new EventLogger(selfId, dataDirectory.resolve("raft").resolve("events.jsonl"));
    } catch (final IOException e) {
      throw new IllegalStateException("No se pudo crear el log de eventos de Raft", e);
    }

    final RaftNode raftNode =
        new RaftNode(
            selfId,
            clusterConfig,
            new RaftLog(),
            new InMemoryPersistentStore(),
            transport,
            new ScheduledExecutorRaftScheduler(selfId),
            eventLogger,
            null);

    final RaftRpcServer raftRpcServer;
    try {
      raftRpcServer = new RaftRpcServer(clusterConfig.selfAddress(), raftNode);
    } catch (final IOException e) {
      throw new IllegalStateException(
          "No se pudo abrir el puerto del canal HTTP de Raft (" + clusterConfig.selfAddress() + ")",
          e);
    }

    final RaftBlockCreator blockCreator =
        new RaftBlockCreator(
            miningConfiguration,
            (blockTimestamp, parentHeader) -> selfAddress,
            parentHeader -> selfAddress.getBytes(),
            transactionPool,
            protocolContext,
            protocolSchedule,
            ethProtocolManager.ethContext().getScheduler());

    final int blockPeriodSeconds = genesisConfigOptions.getRaftConfigOptions().getBlockPeriodSeconds();

    return new RaftMiningCoordinator(
        raftNode,
        raftRpcServer,
        blockCreator,
        protocolContext,
        protocolSchedule,
        miningConfiguration,
        blockPeriodSeconds,
        ethProtocolManager);
  }

  @Override
  protected ProtocolSchedule createProtocolSchedule() {
    return RaftProtocolSchedule.create(
        genesisConfigOptions,
        genesisConfigOptions.getRaftConfigOptions().getBlockPeriodSeconds(),
        dataStorageConfiguration.getRevertReasonEnabled(),
        evmConfiguration,
        miningConfiguration,
        badBlockManager,
        isParallelTxProcessingEnabled,
        balConfiguration,
        metricsSystem);
  }

  @Override
  protected void validateContext(final ProtocolContext context) {
    if (validators.isEmpty()) {
      LOG.warn("Genesis raft config sin validadores - la cadena no producira bloques.");
    } else if (!validators.contains(selfAddress)) {
      LOG.warn(
          "La direccion de este nodo ({}) no esta en la lista de validadores del genesis {} - "
              + "sus propios bloques seran rechazados al importarlos.",
          selfAddress.getBytes().toHexString(),
          validators);
    }
  }

  @Override
  protected PluginServiceFactory createAdditionalPluginServices(
      final Blockchain blockchain, final ProtocolContext protocolContext) {
    return new NoopPluginServiceFactory();
  }

  @Override
  protected RaftContext createConsensusContext(
      final Blockchain blockchain,
      final WorldStateArchive worldStateArchive,
      final ProtocolSchedule protocolSchedule) {
    return new RaftContext(validators);
  }
}
