/*
 * Copyright 2026 West Coast Informatics
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.wci.snomed.sparql.examples;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Checks the expected results in src/test/resources/data/&lt;version&gt; (regenerated with "make expected-results")
 * are consistent with the example catalog: one result per executable example, matching expressions, and totals that
 * match the recorded ids.
 */
public class ExpectedResultsDataTest {

  /** The expected results dir for the test version. */
  public static final Path DATA_DIR = Path.of("src/test/resources/data/20261001");

  /**
   * The expected results match the catalog.
   */
  @Test
  public void testExpectedResults() {
    final Map<String, ExpectedResult> results = ExpectedResultStore.readAll(DATA_DIR).stream()
        .collect(Collectors.toMap(ExpectedResult::id, Function.identity()));
    final Map<String, EclExample> examples = EclExampleCatalog.loadExecutable().stream()
        .collect(Collectors.toMap(EclExample::id, Function.identity()));
    assertEquals(examples.keySet(), results.keySet());
    for (final ExpectedResult result : results.values()) {
      assertEquals(examples.get(result.id()).ecl(), result.ecl(), result.id());
      if (result.evaluated()) {
        assertNotNull(result.ids(), result.id());
        assertEquals(result.total().longValue(), result.ids().size(), result.id());
        assertEquals(result.ids().size(), Set.copyOf(result.ids()).size(), result.id() + " duplicate ids");
      } else {
        assertTrue(result.error() != null && !result.error().isBlank(), result.id());
      }
    }
  }

}
