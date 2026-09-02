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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.ImmutableMap;

/** Lee la seccion {@code "raft": {...}} del genesis JSON. */
public class JsonRaftConfigOptions implements RaftConfigOptions {

  private static final int DEFAULT_BLOCK_PERIOD_SECONDS = 5;

  private final ObjectNode raftConfigRoot;

  /** Instancia por defecto (sin validadores) para cuando falta la clave "raft". */
  public static final JsonRaftConfigOptions DEFAULT =
      new JsonRaftConfigOptions(JsonUtil.createEmptyObjectNode());

  /**
   * Instantiates a new Raft config options.
   *
   * @param raftConfigRoot the raft config root
   */
  JsonRaftConfigOptions(final ObjectNode raftConfigRoot) {
    this.raftConfigRoot = raftConfigRoot;
  }

  @Override
  public List<String> getValidators() {
    List<String> validators = new ArrayList<>();
    JsonUtil.getArrayNode(raftConfigRoot, "validators")
        .ifPresent(array -> array.forEach(node -> validators.add(node.asText())));
    return validators;
  }

  @Override
  public int getBlockPeriodSeconds() {
    return JsonUtil.getPositiveInt(
        raftConfigRoot, "blockperiodseconds", DEFAULT_BLOCK_PERIOD_SECONDS);
  }

  @Override
  public Map<String, Object> asMap() {
    return ImmutableMap.of(
        "validators", getValidators(),
        "blockPeriodSeconds", getBlockPeriodSeconds());
  }
}
