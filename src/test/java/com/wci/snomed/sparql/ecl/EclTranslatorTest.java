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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wci.snomed.sparql.store.MiniStore;
import com.wci.snomed.sparql.store.SnomedStore;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.snomed.langauges.ecl.ECLException;

/**
 * Unit tests for {@link EclTranslator} against the mini release store.
 */
public class EclTranslatorTest {

  /** The translator. */
  private static final EclTranslator TRANSLATOR = new EclTranslator();

  /** The store. */
  private static SnomedStore store;

  /**
   * Load the store.
   *
   * @throws Exception the exception
   */
  @BeforeAll
  public static void load() throws Exception {
    store = MiniStore.get();
  }

  /**
   * Evaluate an expression.
   *
   * @param ecl the ECL
   * @return the codes
   */
  private static long[] eval(final String ecl) {
    return store.selectCodes(TRANSLATOR.translate(ecl), 0);
  }

  /**
   * Self and wildcard.
   */
  @Test
  public void testSimple() {
    assertArrayEquals(new long[] {
        404684003L
    }, eval("404684003 |Clinical finding|"));
    assertArrayEquals(new long[0], eval("123456001"));
    // inactive concept
    assertArrayEquals(new long[0], eval("999993001"));
    assertArrayEquals(new long[] {
        113331007L, 116680003L, 138875005L, 362969004L, 363698007L, 404684003L, 410662002L, 723594008L,
        723596005L, 762705008L, 762706009L, 999990008L, 999991005L, 900000000000441003L
    }, eval("*"));
  }

  /**
   * Hierarchy operators.
   */
  @Test
  public void testHierarchy() {
    assertArrayEquals(new long[] {
        362969004L, 999990008L
    }, eval("< 404684003 |Clinical finding|"));
    assertArrayEquals(new long[] {
        362969004L, 404684003L, 999990008L
    }, eval("<< 404684003"));
    assertArrayEquals(new long[] {
        362969004L
    }, eval("<! 404684003"));
    assertArrayEquals(new long[] {
        362969004L, 404684003L
    }, eval("<<! 404684003"));
    assertArrayEquals(new long[] {
        138875005L, 362969004L, 404684003L
    }, eval("> 999990008"));
    assertArrayEquals(new long[] {
        138875005L, 362969004L, 404684003L, 999990008L
    }, eval(">> 999990008"));
    assertArrayEquals(new long[] {
        362969004L
    }, eval(">! 999990008"));
    assertArrayEquals(new long[] {
        362969004L, 999990008L
    }, eval(">>! 999990008"));
    // self operators do not match an unknown or inactive focus concept
    assertArrayEquals(new long[0], eval("<< 123456001"));
    assertArrayEquals(new long[0], eval(">> 999993001"));
    // nested focus
    assertArrayEquals(new long[] {
        362969004L, 404684003L, 999990008L
    }, eval("<< (^ 999991005)"));
  }

  /**
   * Operators applied to the wildcard (inactive concepts are never matched).
   */
  @Test
  public void testWildcard() {
    assertEquals(13, eval("< *").length);
    assertEquals(13, eval("<! *").length);
    assertEquals(14, eval("<< *").length);
    assertEquals(14, eval(">> *").length);
    // concepts with children
    assertEquals(eval("> *").length, eval(">! *").length);
    assertArrayEquals(eval("> *"), store.selectCodes(EclTranslator.PREFIXES
        + "SELECT DISTINCT ?concept WHERE { ?x rdfs:subClassOf ?concept FILTER(isIRI(?concept)) }", 0));
  }

  /**
   * Member of, including inactive members (e.g. of historical association refsets) and the wildcard.
   */
  @Test
  public void testMemberOf() {
    assertArrayEquals(new long[] {
        404684003L, 999990008L
    }, eval("^ 999991005 |Example refset|"));
    assertArrayEquals(new long[] {
        999993001L
    }, eval("^ 900000000000527005 |SAME AS association reference set|"));
    assertArrayEquals(new long[] {
        404684003L, 999990008L
    }, eval("^ (<< 999991005)"));
    assertTrue(eval("^ *").length > 3);
  }

  /**
   * AND, OR, MINUS, nesting.
   */
  @Test
  public void testCompound() {
    assertArrayEquals(new long[] {
        999990008L
    }, eval("< 404684003 AND ^ 999991005"));
    assertArrayEquals(new long[] {
        138875005L, 362969004L, 999990008L
    }, eval("< 404684003 OR 138875005"));
    assertArrayEquals(new long[] {
        362969004L
    }, eval("<< 404684003 MINUS ^ 999991005"));
    assertArrayEquals(new long[] {
        362969004L
    }, eval("(<< 404684003 MINUS ^ 999991005) AND < 404684003"));
    assertArrayEquals(new long[] {
        138875005L, 362969004L
    }, eval("(<< 404684003 MINUS ^ 999991005) OR (138875005 AND << 138875005)"));
  }

  /**
   * Dot notation: attribute values.
   */
  @Test
  public void testDotted() {
    assertArrayEquals(new long[] {
        113331007L
    }, eval("< 404684003 . 363698007 |Finding site|"));
    assertArrayEquals(new long[] {
        113331007L
    }, eval("999990008 . << 762705008"));
    assertArrayEquals(new long[] {
        113331007L
    }, eval("999990008 . *"));
    assertArrayEquals(new long[0], eval("404684003 . 363698007"));
    assertArrayEquals(new long[0], eval("(999990008 . 363698007) . 363698007"));
  }

  /**
   * Refinements: attributes, wildcards, cardinality, groups, reverse, not equals, disjunction.
   */
  @Test
  public void testRefinements() {
    final long[] both = new long[] {
        362969004L, 999990008L
    };
    assertArrayEquals(both, eval("< 404684003 : 363698007 |Finding site| = 113331007"));
    assertArrayEquals(both, eval("< 404684003 : 363698007 = << 113331007"));
    assertArrayEquals(both, eval("< 404684003 : * = 113331007"));
    assertArrayEquals(both, eval("< 404684003 : << 762705008 = *"));
    assertArrayEquals(both, eval("< 404684003 : [1..1] 363698007 = *"));
    assertArrayEquals(new long[0], eval("< 404684003 : [2..*] 363698007 = *"));
    assertArrayEquals(new long[] {
        404684003L
    }, eval("<< 404684003 : [0..0] 363698007 = *"));
    assertArrayEquals(new long[0], eval("< 404684003 : 363698007 != 113331007"));
    assertArrayEquals(both, eval("< 404684003 : [0..0] 363698007 != 113331007"));
    assertArrayEquals(both, eval("< 404684003 : { 363698007 = 113331007 }"));
    assertArrayEquals(new long[0], eval("< 404684003 : [0..0] { 363698007 = 113331007 }"));
    assertArrayEquals(new long[0], eval("< 404684003 : { [2..*] 363698007 = 113331007 }"));
    assertArrayEquals(both, eval("< 404684003 : 363698007 = 404684003 OR 363698007 = 113331007"));
    assertArrayEquals(new long[0], eval("< 404684003 : 363698007 = 404684003, 363698007 = 113331007"));
    // compound value
    assertArrayEquals(both, eval("< 404684003 : 363698007 = (<< 113331007 MINUS 404684003)"));
    assertArrayEquals(new long[0], eval("< 404684003 : 363698007 = (<< 113331007 MINUS 113331007)"));
    // reverse: the values of the finding site of thyroid disorder
    assertArrayEquals(new long[] {
        113331007L
    }, eval("* : R 363698007 = 999990008"));
    // nested refinement as a value
    assertArrayEquals(both, eval("< 404684003 : 363698007 = (* : R 363698007 = 999990008)"));
  }

  /**
   * Concept and member filters, and history supplements.
   */
  @Test
  public void testFiltersAndHistory() {
    assertArrayEquals(new long[] {
        362969004L
    }, eval("<< 404684003 {{ C definitionStatus = defined }}"));
    assertArrayEquals(new long[] {
        404684003L, 999990008L
    }, eval("<< 404684003 {{ C definitionStatusId = 900000000000074008 |Primitive| }}"));
    assertArrayEquals(new long[] {
        404684003L, 999990008L
    }, eval("^ 999991005 {{ C active = 1 }}"));
    assertArrayEquals(new long[] {
        999993001L
    }, eval("^ 900000000000527005 {{ C active = false }}"));
    assertArrayEquals(new long[] {
        999993001L
    }, eval("^ 900000000000527005 {{ M targetComponentId = << 404684003 }}"));
    assertArrayEquals(new long[0], eval("^ 900000000000527005 {{ M targetComponentId = 113331007 }}"));
    // the member is an inactive concept
    assertArrayEquals(new long[] {
        999993001L
    }, eval("^ 900000000000527005 {{ M referencedComponentId = 999993001 }}"));
    assertArrayEquals(new long[0], eval("^ 900000000000527005 {{ M referencedComponentId != 999993001 }}"));
    assertArrayEquals(new long[] {
        999990008L, 999993001L
    }, eval("999990008 {{ + HISTORY-MIN }}"));
    assertArrayEquals(new long[] {
        362969004L, 999990008L, 999993001L
    }, eval("< 404684003 {{ + HISTORY-MOD }}"));
    // history inside a bound check
    assertArrayEquals(new long[] {
        999993001L
    }, eval("^ 900000000000527005 AND (999990008 {{ + HISTORY-MIN }})"));
  }

  /**
   * Description filters (term match / wildcard, type, language, dialect, id, module, effective time) and concept
   * module / effective time filters.
   */
  @Test
  public void testDescriptionFilters() {
    final long[] thyroid = new long[] {
        999990008L
    };
    assertArrayEquals(thyroid, eval("< 404684003 {{ term = \"thyr dis\" }}"));
    assertArrayEquals(thyroid, eval("< 404684003 {{ term = \"dis thy\" }}"));
    assertArrayEquals(new long[0], eval("< 404684003 {{ term = \"hyroid\" }}"));
    assertArrayEquals(thyroid, eval("< 404684003 {{ term = wild:\"thyroid*\" }}"));
    assertArrayEquals(new long[0], eval("< 404684003 {{ term = wild:\"thyroid\" }}"));
    assertArrayEquals(new long[] {
        362969004L, 999990008L
    }, eval("< 404684003 {{ D term = (\"thyroid\" \"endocrine\"), type = fsn }}"));
    // the preferred synonym only exists for 999990008
    assertArrayEquals(thyroid, eval("< 404684003 {{ type = syn }}"));
    assertArrayEquals(thyroid, eval("< 404684003 {{ term = \"thyroid\", type = syn, dialect = en-us (prefer) }}"));
    assertArrayEquals(new long[0], eval("< 404684003 {{ dialect = en-gb }}"));
    assertArrayEquals(new long[0], eval("< 404684003 {{ dialect = en-au }}"));
    assertArrayEquals(new long[] {
        362969004L, 999990008L
    }, eval("< 404684003 {{ language = EN }}"));
    assertArrayEquals(new long[0], eval("< 404684003 {{ term = \"thyroid\", language = sv }}"));
    assertArrayEquals(thyroid, eval("< 404684003 {{ D id = 999990028 }}"));
    assertArrayEquals(thyroid, eval("< 404684003 {{ term = \"thyroid\" }} {{ term = \"disorder\", type = fsn }}"));
    assertArrayEquals(new long[] {
        362969004L
    }, eval("< 404684003 {{ term != \"thyroid\" }}"));
    assertArrayEquals(thyroid, eval("< 404684003 {{ D effectiveTime >= \"20190131\" }}"));
    assertArrayEquals(new long[] {
        362969004L
    }, eval("< 404684003 {{ D effectiveTime = (\"20170731\" \"20180131\") }}"));
    assertArrayEquals(new long[0], eval("< 404684003 {{ D active = 0 }}"));
    // concept module and effective time
    assertArrayEquals(thyroid, eval("< 404684003 {{ C effectiveTime > \"20180131\" }}"));
    assertArrayEquals(new long[] {
        362969004L, 999990008L
    }, eval("< 404684003 {{ C moduleId = 900000000000207008 }}"));
    assertArrayEquals(new long[0], eval("< 404684003 {{ C moduleId != 900000000000207008 }}"));
  }

  /**
   * Invalid and unsupported expressions.
   */
  @Test
  public void testErrors() {
    assertThrows(ECLException.class, () -> TRANSLATOR.translate("< 404684003 :"));
    assertThrows(UnsupportedEclException.class, () -> TRANSLATOR.translate("!!> (< 404684003)"));
    assertThrows(UnsupportedEclException.class, () -> TRANSLATOR.translate("!!< 404684003"));
    assertThrows(UnsupportedEclException.class,
        () -> TRANSLATOR.translate("^ 999991005 {{ M moduleId = 900000000000207008 }}"));
    assertThrows(UnsupportedEclException.class,
        () -> TRANSLATOR.translate("^ 999991005 {{ M mapTarget = \"J45\" }}"));
    assertThrows(UnsupportedEclException.class, () -> TRANSLATOR.translate("^ [referencedComponentId] 999991005"));
    // top/bottom inside a term or comment is fine
    assertArrayEquals(new long[] {
        404684003L
    }, eval("404684003 |!!> finding| /* !!< */"));
  }

}
