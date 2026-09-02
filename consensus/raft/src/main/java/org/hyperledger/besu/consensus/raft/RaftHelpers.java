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

import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.ethereum.core.BlockHeader;

/**
 * A diferencia de Clique (que codifica el proposer en una estructura RLP firmada dentro de
 * extraData), este prototipo guarda la direccion del proposer en claro como los 20 bytes de
 * extraData: no hay sellado criptografico, solo se comprueba pertenencia al conjunto de
 * validadores del genesis (ver {@link org.hyperledger.besu.consensus.raft.headervalidationrules
 * .RaftProposerValidationRule}). Es una simplificacion deliberada: Raft es CFT, no BFT, así que no
 * pretende dar la garantia criptografica de origen que si da QBFT/IBFT.
 */
public final class RaftHelpers {

  private RaftHelpers() {}

  public static final int PROPOSER_EXTRA_DATA_LENGTH = 20;

  public static Address getProposerOfBlock(final BlockHeader header) {
    return Address.wrap(header.getExtraData());
  }
}
