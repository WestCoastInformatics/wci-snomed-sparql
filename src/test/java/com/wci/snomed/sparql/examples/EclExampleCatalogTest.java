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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.snomed.langauges.ecl.ECLObjectFactory;
import org.snomed.langauges.ecl.ECLQueryBuilder;

/**
 * Unit tests for {@link EclExampleCatalog}: the catalog loads, and every executable example (brief syntax) is
 * valid ECL according to snomed-ecl-parser. The long syntax is kept for reference only; neither snomed-ecl-parser
 * nor the SNOMED browser supports it.
 */
public class EclExampleCatalogTest {

  /** The ECL parser. */
  private static final ECLQueryBuilder PARSER = new ECLQueryBuilder(new ECLObjectFactory());

  /**
   * Valid ECL 2.2 the parser (5.0.0-SNAPSHOT) does not yet support: refsetContaining (^R), quoted alternate
   * identifiers, active = "*" filters, and effectiveTime = "" filters (accepted by the grammar but the object
   * builder throws NumberFormatException). Asserted to fail so we notice when the parser supports them.
   */
  public static final Set<String> KNOWN_PARSER_GAPS = Set.of("6.1-25", "6.1-27", "6.8-39", "6.8-44", "6.9-14");

  /**
   * The catalog loads with unique ids, and every example has an expression.
   */
  @Test
  public void testLoad() {
    final List<EclExample> examples = EclExampleCatalog.load();
    assertTrue(examples.size() > 200, "examples: " + examples.size());
    final Set<String> ids = examples.stream().map(EclExample::id).collect(Collectors.toSet());
    assertEquals(examples.size(), ids.size(), "duplicate ids");
    examples.forEach(e -> assertNotNull(e.ecl(), e.id()));
  }

  /**
   * Returns the executable examples.
   *
   * @return the examples
   */
  public static Stream<EclExample> examples() {
    return EclExampleCatalog.loadExecutable().stream();
  }

  /**
   * Each executable expression parses (except known parser gaps, which must still fail).
   *
   * @param example the example
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("examples")
  public void testParses(final EclExample example) {
    if (KNOWN_PARSER_GAPS.contains(example.id())) {
      assertThrows(Exception.class, () -> PARSER.createQuery(example.ecl()), example.id() + " now parses");
    } else {
      assertNotNull(PARSER.createQuery(example.ecl()), example.id());
    }
  }

}
