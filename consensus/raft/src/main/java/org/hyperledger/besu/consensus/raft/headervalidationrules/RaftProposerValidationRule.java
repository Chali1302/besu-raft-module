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
package org.hyperledger.besu.consensus.raft.headervalidationrules;

import org.hyperledger.besu.consensus.raft.RaftContext;
import org.hyperledger.besu.consensus.raft.RaftHelpers;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.ethereum.ProtocolContext;
import org.hyperledger.besu.ethereum.core.BlockHeader;
import org.hyperledger.besu.ethereum.mainnet.AttachedBlockHeaderValidationRule;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Comprueba que el proposer codificado en extraData pertenece al conjunto fijo de validadores del
 * genesis. Sin firma criptografica (ver {@link RaftHelpers}) — simplificacion deliberada del
 * prototipo de TFG, no una garantia de produccion.
 */
public class RaftProposerValidationRule implements AttachedBlockHeaderValidationRule {

  private static final Logger LOG = LoggerFactory.getLogger(RaftProposerValidationRule.class);

  @Override
  public boolean validate(
      final BlockHeader header, final BlockHeader parent, final ProtocolContext protocolContext) {
    if (header.getExtraData().size() != RaftHelpers.PROPOSER_EXTRA_DATA_LENGTH) {
      LOG.info("Invalid raft block header: extraData no tiene el tamano de una direccion.");
      return false;
    }

    final Address proposer = RaftHelpers.getProposerOfBlock(header);
    final List<Address> validators =
        protocolContext.getConsensusContext(RaftContext.class).getValidators();

    if (!validators.contains(proposer)) {
      LOG.info(
          "Invalid raft block header: el proposer {} no pertenece al conjunto de validadores.",
          proposer);
      return false;
    }
    return true;
  }

  @Override
  public boolean includeInLightValidation() {
    return false;
  }

  @Override
  public String toString() {
    return "RaftProposerValidation";
  }
}
