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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.apache.jena.graph.Graph;
import org.apache.jena.graph.GraphMemFactory;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.graph.Triple;
import org.apache.jena.query.QueryParseException;
import org.apache.jena.vocabulary.OWL;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/**
 * Unit tests for {@link SnomedStore} using the mini release.
 */
public class SnomedStoreTest {

  /** The prefixes. */
  private static final String PREFIXES = """
      PREFIX sct: <http://snomed.info/id/>
      PREFIX owl: <http://www.w3.org/2002/07/owl#>
      PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
      """;

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
   * Run a query.
   *
   * @param where the WHERE clause body
   * @return the codes
   */
  private static long[] select(final String where) {
    return store.selectCodes(PREFIXES + "SELECT ?concept WHERE { " + where + " }", 0);
  }

  /**
   * The version and concept count are read from the ontology.
   */
  @Test
  public void testLoad() {
    assertEquals("20190131", store.getVersion());
    assertEquals(14, store.getConceptCount());
    assertEquals(14, select("?concept a owl:Class").length);
  }

  /**
   * Named parents are asserted directly, so the hierarchy is navigable with property paths; class expression
   * blank nodes are never returned.
   */
  @Test
  public void testHierarchy() {
    // direct parent from an EquivalentClasses intersection
    assertArrayEquals(new long[] {
        404684003L
    }, select("sct:362969004 rdfs:subClassOf ?concept"));
    // direct parent from a SubClassOf intersection
    assertArrayEquals(new long[] {
        362969004L
    }, select("sct:999990008 rdfs:subClassOf ?concept"));
    assertArrayEquals(new long[] {
        362969004L, 999990008L
    }, select("?concept rdfs:subClassOf+ sct:404684003"));
    assertArrayEquals(new long[] {
        138875005L, 362969004L, 404684003L
    }, select("sct:999990008 rdfs:subClassOf+ ?concept"));
  }

  /**
   * Refset memberships are queryable with isMemberOf.
   */
  @Test
  public void testMembers() {
    assertArrayEquals(new long[] {
        404684003L, 999990008L
    }, select("?concept sct:isMemberOf sct:999991005"));
  }

  /**
   * Inactive concepts are reachable through memberships and associations, but are not concepts in the
   * hierarchy.
   */
  @Test
  public void testInactiveConcepts() {
    assertArrayEquals(new long[] {
        999993001L
    }, select("?concept sct:isMemberOf sct:900000000000527005"));
    assertArrayEquals(new long[] {
        999993001L
    }, select("?concept sct:900000000000527005 sct:999990008"));
    assertArrayEquals(new long[] {
        999993001L
    }, select("?concept owl:deprecated true"));
    assertEquals("Old thyroid disorder (disorder)", store.getFsn(999993001L));
  }

  /**
   * Labels are the FSN (rdfs:label) and preferred term (skos:prefLabel).
   */
  @Test
  public void testLabels() {
    assertEquals("Thyroid disorder (disorder)", store.getFsn(999990008L));
    assertEquals("Clinical finding (finding)", store.getFsn(404684003L));
    assertEquals("Thyroid disorder", store.getPreferredTerm(999990008L));
    assertNull(store.getFsn(123L));
  }

  /**
   * The result variable defaults to the first variable when there is no ?concept; invalid SPARQL is rejected.
   */
  @Test
  public void testSelect() {
    assertArrayEquals(new long[] {
        404684003L
    }, store.selectCodes(PREFIXES + "SELECT ?x WHERE { sct:362969004 rdfs:subClassOf ?x }", 0));
    final Executable invalid = () -> store.selectCodes("SELECT WHERE", 0);
    org.junit.jupiter.api.Assertions.assertThrows(QueryParseException.class, invalid);
  }

  /**
   * Named parents come from intersections and from a named equivalent class.
   */
  @Test
  public void testAddNamedParents() {
    final Graph graph = GraphMemFactory.createDefaultGraph();
    final Node a = NodeFactory.createURI(SnomedStore.SCT + "1");
    final Node b = NodeFactory.createURI(SnomedStore.SCT + "2");
    final Node c = NodeFactory.createURI(SnomedStore.SCT + "3");
    final Node d = NodeFactory.createURI(SnomedStore.SCT + "4");
    final Node expression = NodeFactory.createBlankNode();
    final Node list1 = NodeFactory.createBlankNode();
    final Node list2 = NodeFactory.createBlankNode();
    final Node restriction = NodeFactory.createBlankNode();
    graph.add(Triple.create(a, OWL.equivalentClass.asNode(), expression));
    graph.add(Triple.create(expression, OWL.intersectionOf.asNode(), list1));
    graph.add(Triple.create(list1, RDF.first.asNode(), b));
    graph.add(Triple.create(list1, RDF.rest.asNode(), list2));
    graph.add(Triple.create(list2, RDF.first.asNode(), restriction));
    graph.add(Triple.create(list2, RDF.rest.asNode(), RDF.nil.asNode()));
    graph.add(Triple.create(c, OWL.equivalentClass.asNode(), d));
    graph.add(Triple.create(d, RDFS.subClassOf.asNode(), b));
    assertEquals(2, SnomedStore.addNamedParents(graph));
    assertEquals(true, graph.contains(a, RDFS.subClassOf.asNode(), b));
    assertEquals(true, graph.contains(c, RDFS.subClassOf.asNode(), d));
    assertEquals(false, graph.contains(a, RDFS.subClassOf.asNode(), restriction));
  }

}
