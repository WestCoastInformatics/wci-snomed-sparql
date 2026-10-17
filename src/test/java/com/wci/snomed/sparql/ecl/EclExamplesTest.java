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
package com.wci.snomed.sparql.ecl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.wci.snomed.sparql.convert.RefsetMembershipReader;
import com.wci.snomed.sparql.examples.EclExample;
import com.wci.snomed.sparql.examples.EclExampleCatalog;
import com.wci.snomed.sparql.examples.ExpectedResult;
import com.wci.snomed.sparql.examples.ExpectedResultStore;
import com.wci.snomed.sparql.examples.ExpectedResultsDataTest;
import com.wci.snomed.sparql.store.SnomedStore;
import com.wci.snomed.sparql.store.TestDataStore;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Runs each ECL example from the specification against the test OWL file (20261001) and compares the result to
 * the expected (Snowstorm) result: first the count, then the concept ids. Examples without an evaluated expected
 * result, or using features the translation does not support yet, are skipped (aborted).
 */
public class EclExamplesTest {

  /** The translator. */
  private static final EclTranslator TRANSLATOR = new EclTranslator();

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
   * Returns the executable examples.
   *
   * @return the examples
   */
  public static Stream<Arguments> examples() {
    return EclExampleCatalog.loadExecutable().stream().map(e -> Arguments.of(e.id(), e));
  }

  /**
   * The example's result matches the expected result.
   *
   * @param id the example id
   * @param example the example
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("examples")
  public void testExample(final String id, final EclExample example) {
    final ExpectedResult result = expected.get(example.id());
    Assumptions.assumeTrue(result != null && result.evaluated(), "no expected result (see README.md)");
    final String sparql;
    try {
      sparql = TRANSLATOR.translate(example.ecl());
    } catch (final UnsupportedEclException e) {
      Assumptions.abort("not supported yet: " + e.getMessage());
      return;
    }
    final List<String> ids =
        Arrays.stream(store.selectCodes(sparql, 0)).mapToObj(Long::toString).sorted().toList();
    // Snowstorm's HISTORY supplements can return description ids (members of the REFERS TO concept association
    // refset, e.g. 492714018 for 6.11-09..12); results here are concepts only
    final List<String> expectedIds = result.ids().stream().filter(RefsetMembershipReader::isConceptId).toList();
    assertEquals(expectedIds.size(), ids.size(), example.id() + " count: " + example.ecl());
    assertEquals(expectedIds, ids, example.id() + " ids: " + example.ecl());
  }

}
