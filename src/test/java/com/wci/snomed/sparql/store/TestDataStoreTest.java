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
package com.wci.snomed.sparql.store;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.wci.snomed.sparql.examples.ExpectedResult;
import com.wci.snomed.sparql.examples.ExpectedResultStore;
import com.wci.snomed.sparql.examples.ExpectedResultsDataTest;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Checks the full test OWL file loads and matches the expected (Snowstorm) results for the hierarchy.
 */
public class TestDataStoreTest {

  /** The store. */
  private static SnomedStore store;

  /** The expected results by example id. */
  private static Map<String, ExpectedResult> expected;

  /**
   * Load the store and the expected results.
   *
   * @throws Exception the exception
   */
  @BeforeAll
  public static void load() throws Exception {
    store = TestDataStore.get();
    expected = ExpectedResultStore.readAll(ExpectedResultsDataTest.DATA_DIR).stream()
        .collect(Collectors.toMap(ExpectedResult::id, Function.identity()));
  }

  /**
   * The concept count matches "*" (6.1-15).
   */
  @Test
  public void testConceptCount() {
    assertEquals("20261001", store.getVersion());
    assertEquals(expected.get("6.1-15").total().longValue(), store.getConceptCount());
  }

  /**
   * The named parent triples give exactly the descendants of "&lt;404684003 |Clinical finding|" (6.1-02).
   */
  @Test
  public void testDescendants() {
    final long[] codes = store.selectCodes("""
        PREFIX sct: <http://snomed.info/id/>
        PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
        SELECT ?concept WHERE { ?concept rdfs:subClassOf+ sct:404684003 }
        """, 0);
    final List<String> ids = Arrays.stream(codes).mapToObj(Long::toString).sorted().toList();
    assertEquals(expected.get("6.1-02").ids(), ids);
  }

}
