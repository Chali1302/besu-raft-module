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
package org.hyperledger.besu.consensus.raft.blockcreation;

import org.hyperledger.besu.consensus.raft.engine.NodeRole;
import org.hyperledger.besu.consensus.raft.engine.RaftNode;
import org.hyperledger.besu.consensus.raft.engine.RaftRpcServer;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.ethereum.ProtocolContext;
import org.hyperledger.besu.ethereum.blockcreation.BlockCreator.BlockCreationResult;
import org.hyperledger.besu.ethereum.blockcreation.MiningCoordinator;
import org.hyperledger.besu.ethereum.chain.MinedBlockObserver;
import org.hyperledger.besu.ethereum.core.Block;
import org.hyperledger.besu.ethereum.core.BlockHeader;
import org.hyperledger.besu.ethereum.core.MiningConfiguration;
import org.hyperledger.besu.ethereum.core.Transaction;
import org.hyperledger.besu.ethereum.mainnet.BlockImportResult;
import org.hyperledger.besu.ethereum.mainnet.HeaderValidationMode;
import org.hyperledger.besu.ethereum.mainnet.ProtocolSchedule;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Coordinador de mineria del prototipo Raft (TFG). A diferencia de {@code BftMiningCoordinator}
 * (maquina de estados dirigida por eventos, con una ronda de consenso por cada nueva cabecera de
 * bloque recibida), este coordinador usa un mecanismo de <em>sondeo</em> mucho mas simple: un
 * temporizador periodico comprueba si el nodo local es el lider reconocido por el motor Raft
 * embebido y si ha transcurrido el periodo minimo entre bloques del genesis; si ambas condiciones
 * se cumplen, construye un bloque candidato y lo somete al importador de bloques de la cadena
 * local. La propagacion a los demas nodos la resuelve la infraestructura de sincronizacion ya
 * existente de Besu una vez el bloque esta en la cadena local.
 */
public final class RaftMiningCoordinator implements MiningCoordinator {

  private static final Logger LOG = LoggerFactory.getLogger(RaftMiningCoordinator.class);

  private static final long POLL_INTERVAL_MS = 1_000;

  private final RaftNode raftNode;
  private final RaftRpcServer raftRpcServer;
  private final RaftBlockCreator blockCreator;
  private final ProtocolContext protocolContext;
  private final ProtocolSchedule protocolSchedule;
  private final MiningConfiguration miningConfiguration;
  private final long blockPeriodSeconds;
  private final MinedBlockObserver minedBlockObserver;
  private final ScheduledExecutorService scheduler;

  private final AtomicBoolean miningEnabled = new AtomicBoolean(true);
  private final AtomicBoolean started = new AtomicBoolean(false);
  private ScheduledFuture<?> pollTask;

  public RaftMiningCoordinator(
      final RaftNode raftNode,
      final RaftRpcServer raftRpcServer,
      final RaftBlockCreator blockCreator,
      final ProtocolContext protocolContext,
      final ProtocolSchedule protocolSchedule,
      final MiningConfiguration miningConfiguration,
      final long blockPeriodSeconds,
      final MinedBlockObserver minedBlockObserver) {
    this.raftNode = raftNode;
    this.raftRpcServer = raftRpcServer;
    this.blockCreator = blockCreator;
    this.protocolContext = protocolContext;
    this.protocolSchedule = protocolSchedule;
    this.miningConfiguration = miningConfiguration;
    this.blockPeriodSeconds = blockPeriodSeconds;
    this.minedBlockObserver = minedBlockObserver;
    this.scheduler =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "raft-mining-coordinator");
              t.setDaemon(true);
              return t;
            });
  }

  @Override
  public void start() {
    if (started.compareAndSet(false, true)) {
      raftRpcServer.start();
      raftNode.start();
      pollTask =
          scheduler.scheduleAtFixedRate(
              this::tryProduceBlock, POLL_INTERVAL_MS, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
      LOG.info("RaftMiningCoordinator arrancado (periodo entre bloques: {}s)", blockPeriodSeconds);
    }
  }

  @Override
  public void stop() {
    if (started.compareAndSet(true, false)) {
      if (pollTask != null) {
        pollTask.cancel(false);
      }
      raftNode.stop();
      raftRpcServer.stop();
      scheduler.shutdownNow();
    }
  }

  @Override
  public void awaitStop() {
    // El scheduler es un unico hilo daemon de sondeo; no hay nada que esperar tras stop().
  }

  @Override
  public boolean enable() {
    miningEnabled.set(true);
    return true;
  }

  @Override
  public boolean disable() {
    miningEnabled.set(false);
    return true;
  }

  @Override
  public boolean isMining() {
    return started.get() && miningEnabled.get();
  }

  @Override
  public Wei getMinTransactionGasPrice() {
    return miningConfiguration.getMinTransactionGasPrice();
  }

  @Override
  public Wei getMinPriorityFeePerGas() {
    return miningConfiguration.getMinPriorityFeePerGas();
  }

  @Override
  public Optional<Block> createBlock(
      final BlockHeader parentHeader,
      final List<Transaction> transactions,
      final List<BlockHeader> ommers) {
    // Creacion de bloque "a peticion" (p.ej. desde un endpoint JSON-RPC) no soportada en el
    // prototipo: la produccion de bloques solo ocurre desde el sondeo interno de esta clase,
    // igual que hace BftMiningCoordinator.
    return Optional.empty();
  }

  @Override
  public Optional<Block> createBlock(final BlockHeader parentHeader, final long timestamp) {
    return Optional.empty();
  }

  @Override
  public void changeTargetGasLimit(final Long targetGasLimit) {
    // No soportado en el prototipo: el limite de gas se fija en el genesis.
  }

  private void tryProduceBlock() {
    if (!miningEnabled.get()) {
      return;
    }
    try {
      if (raftNode.role() != NodeRole.LEADER) {
        return;
      }
      final BlockHeader parentHeader = protocolContext.getBlockchain().getChainHeadHeader();
      final long nowSeconds = System.currentTimeMillis() / 1000L;
      if (nowSeconds < parentHeader.getTimestamp() + blockPeriodSeconds) {
        return;
      }

      final BlockCreationResult result = blockCreator.createBlock(nowSeconds, parentHeader);
      final Block block = result.getBlock();

      final BlockImportResult importResult =
          protocolSchedule
              .getByBlockHeader(block.getHeader())
              .getBlockImporter()
              .importBlock(protocolContext, block, HeaderValidationMode.FULL);

      if (importResult.isImported()) {
        // Al contrario que un bloque recibido por sincronizacion (que ya desencadena su propio
        // reenvio dentro del pipeline de sync), un bloque producido localmente no se anuncia a
        // los demas peers a menos que se notifique explicitamente aqui — asi lo hacen tambien
        // QbftBesuControllerBuilder/IbftRound tras cada bloque minado.
        minedBlockObserver.blockMined(block);
        LOG.info(
            "Bloque Raft #{} producido e importado (hash={}, lider={})",
            block.getHeader().getNumber(),
            block.getHash(),
            raftNode.selfId());
      } else {
        LOG.warn(
            "Bloque Raft #{} construido pero rechazado al importarlo a la cadena local",
            block.getHeader().getNumber());
      }
    } catch (final Exception e) {
      LOG.error("Fallo produciendo/importando un bloque Raft", e);
    }
  }
}
