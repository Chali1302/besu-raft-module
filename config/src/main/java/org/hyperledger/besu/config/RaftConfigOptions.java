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
package org.hyperledger.besu.config;

import java.util.List;
import java.util.Map;

import org.immutables.value.Value;

/**
 * Configuration options for el prototipo de consenso Raft (TFG). A diferencia de Clique, el
 * conjunto de nodos no se codifica en el extraData del bloque genesis sino directamente como una
 * lista en el genesis JSON, ya que no hay altas/bajas dinamicas por voto (alcance deliberadamente
 * reducido).
 */
@Value.Immutable
public interface RaftConfigOptions {

  /**
   * Direcciones (hex) de los nodos que forman el cluster Raft fijo.
   *
   * @return la lista de validadores
   */
  List<String> getValidators();

  /**
   * Periodo minimo entre bloques, en segundos.
   *
   * @return el periodo de bloque
   */
  int getBlockPeriodSeconds();

  /**
   * Mapa de las opciones de configuracion.
   *
   * @return el mapa
   */
  Map<String, Object> asMap();
}
