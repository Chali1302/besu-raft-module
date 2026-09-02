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
import org.hyperledger.besu.ethereum.ConsensusContext;

import java.util.List;

/**
 * Estado "en paralelo" a la cadena para el consenso Raft (prototipo de TFG): el conjunto fijo de
 * nodos del cluster (equivalente a los firmantes de Clique, pero sin altas/bajas dinamicas).
 */
public class RaftContext implements ConsensusContext {

  private final List<Address> validators;

  public RaftContext(final List<Address> validators) {
    this.validators = validators;
  }

  public List<Address> getValidators() {
    return validators;
  }

  @Override
  public <C extends ConsensusContext> C as(final Class<C> klass) {
    return klass.cast(this);
  }
}
