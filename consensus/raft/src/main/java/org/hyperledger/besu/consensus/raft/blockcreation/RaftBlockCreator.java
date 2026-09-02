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

import org.hyperledger.besu.ethereum.ProtocolContext;
import org.hyperledger.besu.ethereum.blockcreation.AbstractBlockCreator;
import org.hyperledger.besu.ethereum.core.BlockHeader;
import org.hyperledger.besu.ethereum.core.BlockHeaderBuilder;
import org.hyperledger.besu.ethereum.core.Difficulty;
import org.hyperledger.besu.ethereum.core.MiningConfiguration;
import org.hyperledger.besu.ethereum.core.SealableBlockHeader;
import org.hyperledger.besu.ethereum.eth.manager.EthScheduler;
import org.hyperledger.besu.ethereum.eth.transactions.TransactionPool;
import org.hyperledger.besu.ethereum.mainnet.ProtocolSchedule;

/**
 * Sella la cabecera del bloque candidato sin prueba de trabajo ni firma criptografica (ver {@link
 * org.hyperledger.besu.consensus.raft.RaftHelpers}): no hay paso de sellado propio mas alla de
 * fijar el nonce a cero, igual que {@link org.hyperledger.besu.ethereum.blockcreation
 * .GenericBlockCreator}. La legitimidad del proponente ya quedo codificada en extraData por el
 * {@code ExtraDataCalculator} pasado al constructor, y se comprueba al recibir el bloque via
 * {@link org.hyperledger.besu.consensus.raft.headervalidationrules.RaftProposerValidationRule}.
 */
public final class RaftBlockCreator extends AbstractBlockCreator {

  public RaftBlockCreator(
      final MiningConfiguration miningConfiguration,
      final MiningBeneficiaryCalculator miningBeneficiaryCalculator,
      final ExtraDataCalculator extraDataCalculator,
      final TransactionPool transactionPool,
      final ProtocolContext protocolContext,
      final ProtocolSchedule protocolSchedule,
      final EthScheduler ethScheduler) {
    super(
        miningConfiguration,
        miningBeneficiaryCalculator,
        extraDataCalculator,
        transactionPool,
        protocolContext,
        protocolSchedule,
        ethScheduler);
  }

  @Override
  protected BlockHeader createFinalBlockHeader(final SealableBlockHeader sealableBlockHeader) {
    return BlockHeaderBuilder.create()
        .difficulty(Difficulty.ZERO)
        .populateFrom(sealableBlockHeader)
        .nonce(0L)
        .blockHeaderFunctions(blockHeaderFunctions)
        .buildBlockHeader();
  }
}
