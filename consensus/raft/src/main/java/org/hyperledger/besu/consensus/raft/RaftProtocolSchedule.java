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
package org.hyperledger.besu.consensus.raft;

import org.hyperledger.besu.config.GenesisConfigOptions;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.ethereum.MainnetBlockValidatorBuilder;
import org.hyperledger.besu.ethereum.chain.BadBlockManager;
import org.hyperledger.besu.ethereum.core.MiningConfiguration;
import org.hyperledger.besu.ethereum.mainnet.BalConfiguration;
import org.hyperledger.besu.ethereum.mainnet.MainnetBlockBodyValidator;
import org.hyperledger.besu.ethereum.mainnet.MainnetBlockHeaderFunctions;
import org.hyperledger.besu.ethereum.mainnet.MainnetBlockImporter;
import org.hyperledger.besu.ethereum.mainnet.ProtocolSchedule;
import org.hyperledger.besu.ethereum.mainnet.ProtocolScheduleBuilder;
import org.hyperledger.besu.ethereum.mainnet.ProtocolSpecAdapters;
import org.hyperledger.besu.ethereum.mainnet.ProtocolSpecBuilder;
import org.hyperledger.besu.ethereum.mainnet.feemarket.BaseFeeMarket;
import org.hyperledger.besu.ethereum.mainnet.feemarket.FeeMarket;
import org.hyperledger.besu.evm.internal.EvmConfiguration;
import org.hyperledger.besu.plugin.services.MetricsSystem;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Define las reglas de protocolo para una cadena Raft (prototipo de TFG). Igual que
 * CliqueProtocolSchedule, reutiliza {@link ProtocolScheduleBuilder}/Mainnet casi sin cambios: solo
 * se sustituyen las reglas de validacion de cabecera, la dificultad (constante), la recompensa de
 * bloque (cero) y el calculo del beneficiario. Sin ForksSchedule: el prototipo no soporta
 * transiciones de fork a distintas alturas.
 */
public final class RaftProtocolSchedule {

  private static final BigInteger DEFAULT_CHAIN_ID = BigInteger.valueOf(986086);

  private RaftProtocolSchedule() {}

  public static ProtocolSchedule create(
      final GenesisConfigOptions config,
      final int blockPeriodSeconds,
      final boolean isRevertReasonEnabled,
      final EvmConfiguration evmConfiguration,
      final MiningConfiguration miningConfiguration,
      final BadBlockManager badBlockManager,
      final boolean isParallelTxProcessingEnabled,
      final BalConfiguration balConfiguration,
      final MetricsSystem metricsSystem) {

    final Map<Long, Function<ProtocolSpecBuilder, ProtocolSpecBuilder>> specMap = new HashMap<>();
    specMap.put(0L, builder -> applyRaftSpecificModifications(blockPeriodSeconds, builder));
    final ProtocolSpecAdapters specAdapters = new ProtocolSpecAdapters(specMap);

    return new ProtocolScheduleBuilder(
            config,
            Optional.of(DEFAULT_CHAIN_ID),
            specAdapters,
            isRevertReasonEnabled,
            evmConfiguration,
            miningConfiguration,
            badBlockManager,
            isParallelTxProcessingEnabled,
            balConfiguration,
            metricsSystem)
        .createProtocolSchedule();
  }

  private static ProtocolSpecBuilder applyRaftSpecificModifications(
      final int secondsBetweenBlocks, final ProtocolSpecBuilder specBuilder) {

    return specBuilder
        .blockHeaderValidatorBuilder(
            (baseFeeMarket, gasCalculator, gasLimitCalculator) ->
                getBlockHeaderValidator(secondsBetweenBlocks, baseFeeMarket))
        .ommerHeaderValidatorBuilder(
            (baseFeeMarket, gasCalculator, gasLimitCalculator) ->
                getBlockHeaderValidator(secondsBetweenBlocks, baseFeeMarket))
        .blockBodyValidatorBuilder(MainnetBlockBodyValidator::new)
        .blockValidatorBuilder(MainnetBlockValidatorBuilder::frontier)
        .blockImporterBuilder(MainnetBlockImporter::new)
        .difficultyCalculator((time, parent) -> BigInteger.ONE)
        .blockReward(Wei.ZERO)
        .skipZeroBlockRewards(true)
        .miningBeneficiaryCalculator(RaftHelpers::getProposerOfBlock)
        .blockHeaderFunctions(new MainnetBlockHeaderFunctions());
  }

  private static org.hyperledger.besu.ethereum.mainnet.BlockHeaderValidator.Builder
      getBlockHeaderValidator(final int secondsBetweenBlocks, final FeeMarket feeMarket) {
    final Optional<BaseFeeMarket> baseFeeMarket =
        Optional.of(feeMarket).filter(FeeMarket::implementsBaseFee).map(BaseFeeMarket.class::cast);

    return RaftBlockHeaderValidationRulesetFactory.raftBlockHeaderValidator(
        secondsBetweenBlocks, baseFeeMarket);
  }
}
