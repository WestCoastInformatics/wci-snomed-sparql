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
package com.wci.snomed.sparql.convert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.OWLOntology;

/**
 * Unit tests for {@link InferredOwlConverter} using a mini RF2 release.
 */
public class InferredOwlConverterTest {

  /** The mini release (based on the snomed-owl-toolkit test data). */
  private static final Path RELEASE = Path.of("src/test/resources/rf2/SnomedCT_MiniRF2_20190131");

  /** The output dir. */
  @TempDir
  private static Path tempDir;

  /** The output OWL file. */
  private static Path owlFile;

  /** The output lines. */
  private static List<String> lines;

  /** The conversion stats. */
  private static InferredOwlConverter.Stats stats;

  /**
   * Run the conversion once.
   *
   * @throws Exception the exception
   */
  @BeforeAll
  public static void convert() throws Exception {
    owlFile = tempDir.resolve("out/mini.owl");
    stats = new InferredOwlConverter().convert(RELEASE, owlFile);
    lines = Files.readAllLines(owlFile, StandardCharsets.UTF_8);
  }

  /**
   * The release folder is resolved from either the release root or the snapshot folder.
   */
  @Test
  public void testReleaseFolder() {
    final ReleaseFolder fromRoot = ReleaseFolder.resolve(RELEASE);
    final ReleaseFolder fromSnapshot = ReleaseFolder.resolve(RELEASE.resolve("Snapshot"));
    assertEquals(RELEASE.resolve("Snapshot"), fromRoot.getSnapshotDir());
    assertEquals(RELEASE.resolve("Snapshot"), fromSnapshot.getSnapshotDir());
    assertEquals("20190131", fromRoot.getVersionDate());
    // simple + association + language refsets, plus the OWL axiom and OWL ontology refsets
    assertEquals(5, fromRoot.getRefsetFiles().size());
    assertEquals(1, fromRoot.getAssociationFiles().size());
    assertEquals(1, fromRoot.getDescriptionFiles().size());
    assertThrows(IllegalArgumentException.class, () -> ReleaseFolder.resolve(RELEASE.resolve("missing")));
    assertThrows(IllegalArgumentException.class,
        () -> ReleaseFolder.resolve(RELEASE.resolve("Snapshot/Refset")));
  }

  /**
   * Class axioms reflect the ELK classification, not the stated axioms or the (stale) release inferred file.
   */
  @Test
  public void testInferredClassAxioms() {
    // Primitive concept stated under 404684003; classification places it under the defined 362969004
    assertTrue(lines.contains("SubClassOf(:999990008 ObjectIntersectionOf(:362969004 "
        + "ObjectSomeValuesFrom(:609096000 ObjectSomeValuesFrom(:363698007 :113331007))))"), "999990008 axiom");
    // Fully defined concept keeps EquivalentClasses
    assertTrue(lines.contains("EquivalentClasses(:362969004 ObjectIntersectionOf(:404684003 "
        + "ObjectSomeValuesFrom(:609096000 ObjectSomeValuesFrom(:363698007 :113331007))))"), "362969004 axiom");
    // One class axiom per concept: no stated axiom for 999990008 remains
    assertEquals(1, lines.stream().filter(l -> l.startsWith("SubClassOf(:999990008 ")).count());
    // The attribute hierarchy is kept
    assertTrue(lines.contains("SubObjectPropertyOf(:363698007 :762705008)"));
    // The classification differed from the release's (stale) inferred file for the new concept:
    // added IS-A 362969004 and the finding site group; removed IS-A 404684003
    assertEquals(2, stats.getRelationshipsAdded());
    assertEquals(1, stats.getRelationshipsRemoved());
    assertEquals(0, stats.getEquivalentSets());
    // Every concept but the root has a class axiom; the root is declared only
    assertEquals(stats.getConcepts() - 1, stats.getInferredClassAxioms());
    assertTrue(lines.contains("Declaration(Class(:138875005))"));
    assertFalse(lines.stream().anyMatch(l -> l.contains("owl:Thing")));
  }

  /**
   * Active refset memberships of active concepts become isMemberOf annotations.
   */
  @Test
  public void testMemberships() {
    assertTrue(lines.contains("Declaration(AnnotationProperty(:isMemberOf))"));
    assertTrue(lines.contains("AnnotationAssertion(:isMemberOf :404684003 :999991005)"));
    assertTrue(lines.contains("AnnotationAssertion(:isMemberOf :999990008 :999991005)"));
    // inactive member, description member, and unknown concept are excluded
    assertFalse(lines.contains("AnnotationAssertion(:isMemberOf :362969004 :999991005)"));
    assertFalse(lines.stream().anyMatch(l -> l.contains(":754737012")));
    assertFalse(lines.stream().anyMatch(l -> l.contains(":123456001")));
    assertEquals(2, lines.stream().filter(l -> l.startsWith("AnnotationAssertion(:isMemberOf ")
        && l.endsWith(" :999991005)")).count());
    // OWL axiom refset members (from the sct2_sRefset_OWLExpression file) are included too
    assertTrue(lines.contains("AnnotationAssertion(:isMemberOf :362969004 :733073007)"));
  }

  /**
   * Active association refset members become annotations whose property is the refset; inactive concepts are
   * deprecated and labelled, but not declared as classes.
   */
  @Test
  public void testAssociations() {
    assertTrue(lines.contains("Declaration(AnnotationProperty(:900000000000527005))"));
    assertTrue(lines.contains("AnnotationAssertion(:900000000000527005 :999993001 :999990008)"));
    // inactive association member
    assertFalse(lines.contains("AnnotationAssertion(:900000000000527005 :999993001 :404684003)"));
    // association members are also refset members
    assertTrue(lines.contains("AnnotationAssertion(:isMemberOf :999993001 :900000000000527005)"));
    assertTrue(lines.contains("AnnotationAssertion(owl:deprecated :999993001 \"true\"^^xsd:boolean)"));
    assertTrue(lines.stream().anyMatch(
        l -> l.endsWith(" rdfs:label :999993001 \"Old thyroid disorder (disorder)\"@en)")));
    assertFalse(lines.stream().anyMatch(l -> l.startsWith("Declaration(Class(:999993001))")));
    assertFalse(lines.stream().anyMatch(l -> l.startsWith("SubClassOf(:999993001 ")));
    assertEquals(1, stats.getAssociations());
    assertEquals(1, stats.getInactiveConcepts());
  }

  /**
   * Descriptions are written as labels, and the file is valid OWL.
   *
   * @throws Exception the exception
   */
  @Test
  public void testLabelsAndParse() throws Exception {
    // FSN and preferred synonym, with their RF2 fields as axiom annotations
    assertTrue(lines.contains("AnnotationAssertion(Annotation(:caseSignificanceId :900000000000448009) "
        + "Annotation(:descriptionId \"999990018\"^^xsd:string) Annotation(:effectiveTime \"20190131\"^^xsd:string) "
        + "Annotation(:moduleId :900000000000207008) Annotation(:typeId :900000000000003001) "
        + "rdfs:label :999990008 \"Thyroid disorder (disorder)\"@en)"));
    assertTrue(lines.contains("AnnotationAssertion(Annotation(:caseSignificanceId :900000000000448009) "
        + "Annotation(:descriptionId \"999990028\"^^xsd:string) Annotation(:effectiveTime \"20190131\"^^xsd:string) "
        + "Annotation(:moduleId :900000000000207008) Annotation(:typeId :900000000000013009) "
        + "Annotation(:900000000000509007 :900000000000548007) skos:prefLabel :999990008 \"Thyroid disorder\"@en)"));
    assertTrue(lines.contains("Declaration(AnnotationProperty(:900000000000509007))"));
    // concept metadata
    assertTrue(lines.contains("AnnotationAssertion(:moduleId :999990008 :900000000000207008)"));
    assertTrue(lines.contains("AnnotationAssertion(:effectiveTime :999990008 \"20190131\"^^xsd:string)"));
    assertTrue(lines.contains("AnnotationAssertion(:effectiveTime :999993001 \"20190131\"^^xsd:string)"));
    assertEquals(14, stats.getDescriptions());
    assertTrue(lines.stream().anyMatch(l -> l.startsWith("Ontology(<http://snomed.info/sct/900000000000207008>")));
    assertTrue(lines.stream().anyMatch(l -> l.contains("/version/20190131")));
    final OWLOntology ontology =
        OWLManager.createOWLOntologyManager().loadOntologyFromOntologyDocument(owlFile.toFile());
    assertTrue(ontology.getAxiomCount() > 0);
  }

  /**
   * Concept ids are recognized by partition identifier.
   */
  @Test
  public void testIsConceptId() {
    assertTrue(RefsetMembershipReader.isConceptId("404684003"));
    assertTrue(RefsetMembershipReader.isConceptId("1000001000004108"));
    assertFalse(RefsetMembershipReader.isConceptId("754737012"));
    assertFalse(RefsetMembershipReader.isConceptId("4661958023"));
    assertFalse(RefsetMembershipReader.isConceptId("abc"));
  }

}
