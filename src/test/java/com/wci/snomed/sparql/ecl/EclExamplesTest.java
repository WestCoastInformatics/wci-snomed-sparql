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

import static java.util.Map.entry;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wci.snomed.sparql.convert.RefsetMembershipReader;
import com.wci.snomed.sparql.examples.EclExample;
import com.wci.snomed.sparql.examples.EclExampleCatalog;
import com.wci.snomed.sparql.examples.EclExampleCatalogTest;
import com.wci.snomed.sparql.examples.ExpectedResult;
import com.wci.snomed.sparql.examples.ExpectedResultStore;
import com.wci.snomed.sparql.examples.ExpectedResultsDataTest;
import com.wci.snomed.sparql.store.SnomedStore;
import com.wci.snomed.sparql.store.TestDataStore;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Runs each ECL example of the catalog against the test OWL file (20261001). Every executable example has exactly
 * one expected outcome, so any change in behaviour fails a test:
 *
 * <ol>
 * <li>Known parser gaps ({@link EclExampleCatalogTest#KNOWN_PARSER_GAPS}): translation fails.</li>
 * <li>{@link #UNSUPPORTED}: translation throws {@link UnsupportedEclException} with the given reason.</li>
 * <li>An evaluated expected (Snowstorm) result: the count, then the concept ids, match.</li>
 * <li>No expected result (Snowstorm could not evaluate it, see README.md): the result equals that of an
 * {@link #EQUIVALENT} example, or the reviewed {@link #BASELINE} count.</li>
 * </ol>
 */
public class EclExamplesTest {

  /** Examples the translation does not support, with (part of) the reason given. */
  public static final Map<String, String> UNSUPPORTED = Map.ofEntries(
      entry("6.1-11", "Member field selection"), entry("6.1-12", "Member field selection"),
      entry("6.1-13", "Member field selection"), entry("6.1-14", "Member field selection"),
      entry("6.10-07", "Member field selection"),
      entry("6.1-26", "Alternate identifiers"),
      entry("6.10-01", "mapTarget"), entry("6.10-02", "mapTarget"), entry("6.10-03", "mapTarget"),
      entry("6.10-04", "mapTarget"), entry("6.10-05", "mapGroup"), entry("6.10-06", "mapGroup"),
      entry("6.10-08", "Member moduleId and effectiveTime"), entry("6.10-09", "Member moduleId and effectiveTime"),
      entry("6.10-10", "Inactive refset members"), entry("6.10-11", "Member filter on active"),
      entry("6.12-01", "Top/bottom"), entry("6.12-03", "Top/bottom"), entry("6.12-05", "Top/bottom"));

  /**
   * Examples without an expected result whose result must equal the expected result of an equivalent example (the
   * missing content matches nothing): e.g. "X OR ^ 700043003" where 700043003 is not in the International edition.
   */
  public static final Map<String, String> EQUIVALENT = Map.ofEntries(
      entry("6.4-10", "6.4-01"), entry("6.4-11", "6.4-01"),
      entry("6.4-19", "6.4-12"), entry("6.4-20", "6.4-12"),
      entry("6.8-30", "6.1-15"));

  /**
   * Examples without an expected result (and no equivalent example), with the reviewed result count: content not in
   * the International edition matches nothing (0); the others were checked against the RF2 release.
   */
  public static final Map<String, Integer> BASELINE = Map.ofEntries(
      // ^* : every concept (active or inactive) that is an active member of any refset (Snowstorm times out)
      entry("6.1-24", 539490),
      entry("6.1-10", 0), entry("6.2-10", 0), entry("6.2-11", 0),
      entry("6.4-05", 0), entry("6.4-06", 0), entry("6.4-07", 0), entry("6.4-08", 0),
      entry("6.4-15", 0), entry("6.4-16", 0), entry("6.4-17", 0),
      entry("6.7-01", 0), entry("6.7-08", 0), entry("6.7-09", 0), entry("6.8-18", 0), entry("6.9-06", 0),
      // < 105590001 |Substance| : [3..3] R 127489000 |Has active ingredient| = * (Snowstorm: not supported)
      entry("6.3-22", 81),
      // << 19829001 |Disorder of lung| MINUS ^ 700043003 = << 19829001
      entry("6.5-02", 1389),
      // description id filters (Snowstorm 11: syntax error)
      entry("6.8-45", 1), entry("6.8-46", 1), entry("6.8-47", 3), entry("6.8-48", 1),
      // ^ SAME AS {{ M referencedComponentId = 155574008 }} (Snowstorm: inactive concepts in ECL are rejected)
      entry("A-20", 1));

  /**
   * Examples whose Snowstorm result also contains description ids (members of the REFERS TO concept association
   * refset, e.g. 492714018); results here are concepts only, so only the concept ids are compared.
   */
  public static final Set<String> CONCEPT_IDS_ONLY = Set.of("6.11-09", "6.11-10", "6.11-11", "6.11-12");

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
   * Evaluate an expression.
   *
   * @param ecl the ECL
   * @return the sorted concept ids (as strings, sorted as strings like the expected results)
   */
  private static List<String> evaluate(final String ecl) {
    return Arrays.stream(store.selectCodes(TRANSLATOR.translate(ecl), 0)).mapToObj(Long::toString).sorted()
        .toList();
  }

  /**
   * The example has its expected outcome.
   *
   * @param id the example id
   * @param example the example
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("examples")
  public void testExample(final String id, final EclExample example) {
    if (EclExampleCatalogTest.KNOWN_PARSER_GAPS.contains(id)) {
      assertThrows(Exception.class, () -> TRANSLATOR.translate(example.ecl()), id + " now translates");
      return;
    }
    if (UNSUPPORTED.containsKey(id)) {
      final UnsupportedEclException e =
          assertThrows(UnsupportedEclException.class, () -> TRANSLATOR.translate(example.ecl()), id);
      assertTrue(e.getMessage().contains(UNSUPPORTED.get(id)), id + ": " + e.getMessage());
      return;
    }
    final List<String> ids = evaluate(example.ecl());
    final ExpectedResult result = expected.get(id);
    if (result != null && result.evaluated()) {
      final List<String> expectedIds = CONCEPT_IDS_ONLY.contains(id)
          ? result.ids().stream().filter(RefsetMembershipReader::isConceptId).toList() : result.ids();
      assertEquals(expectedIds.size(), ids.size(), id + " count: " + example.ecl());
      assertEquals(expectedIds, ids, id + " ids: " + example.ecl());
    } else if (EQUIVALENT.containsKey(id)) {
      assertEquals(expected.get(EQUIVALENT.get(id)).ids(), ids, id + " = " + EQUIVALENT.get(id));
    } else {
      assertTrue(BASELINE.containsKey(id), id + " has no expected result: add it to EQUIVALENT or BASELINE ("
          + ids.size() + " results)");
      assertEquals(BASELINE.get(id).intValue(), ids.size(), id + " baseline: " + example.ecl());
    }
  }

  /**
   * The outcome lists are consistent with the catalog and the expected results: each id is an executable example,
   * in one list only, and the baselines and equivalents are only used without an expected result.
   */
  @Test
  public void testOutcomeLists() {
    final Set<String> executable =
        EclExampleCatalog.loadExecutable().stream().map(EclExample::id).collect(Collectors.toSet());
    final Set<String> seen = new HashSet<>();
    for (final Set<String> ids : List.of(UNSUPPORTED.keySet(), EQUIVALENT.keySet(), BASELINE.keySet(),
        EclExampleCatalogTest.KNOWN_PARSER_GAPS)) {
      for (final String id : ids) {
        assertTrue(executable.contains(id), id + " is not an executable example");
        assertTrue(seen.add(id), id + " is in more than one outcome list");
      }
    }
    for (final String id : seen) {
      if (EQUIVALENT.containsKey(id) || BASELINE.containsKey(id)) {
        final ExpectedResult result = expected.get(id);
        assertTrue(result == null || !result.evaluated(), id + " now has an expected result");
      }
    }
    EQUIVALENT.values().forEach(other -> assertTrue(expected.get(other).evaluated(), other));
    for (final String id : CONCEPT_IDS_ONLY) {
      assertTrue(expected.get(id).ids().stream().anyMatch(i -> !RefsetMembershipReader.isConceptId(i)), id);
    }
  }

}
