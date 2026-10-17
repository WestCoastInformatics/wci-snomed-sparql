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

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotation;
import org.semanticweb.owlapi.model.OWLAnnotationProperty;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.snomed.otf.owltoolkit.ontology.OntologyService;

/**
 * Writes concept metadata and descriptions as annotations (replacing the toolkit's description annotations, which
 * carry the term only).
 *
 * <ul>
 * <li>Concept: {@code AnnotationAssertion(:moduleId :c :module)} and
 * {@code AnnotationAssertion(:effectiveTime :c "yyyymmdd")}.</li>
 * <li>Each active description is an annotation of its concept, as the toolkit does: FSN as {@code rdfs:label},
 * a synonym preferred in any language refset as {@code skos:prefLabel}, other synonyms as {@code skos:altLabel},
 * text definitions as {@code skos:definition}; the literal's language tag is the description's language code. The
 * assertion carries axiom annotations: {@code :descriptionId}, {@code :typeId}, {@code :moduleId},
 * {@code :effectiveTime}, {@code :caseSignificanceId}, and {@code :<languageRefsetId> :<acceptabilityId>} for each
 * active language refset member of the description.</li>
 * </ul>
 */
public class DescriptionAnnotator {

  /** The logger. */
  private static final Logger LOGGER = LoggerFactory.getLogger(DescriptionAnnotator.class);

  /** The SNOMED namespace. */
  private static final String SCT = OntologyService.SNOMED_CORE_COMPONENTS_URI;

  /** The SKOS namespace. */
  private static final String SKOS = "http://www.w3.org/2004/02/skos/core#";

  /** The FSN type. */
  private static final String FSN = "900000000000003001";

  /** The text definition type. */
  private static final String DEFINITION = "900000000000550004";

  /** The preferred acceptability. */
  private static final long PREFERRED = 900000000000548007L;

  /** Description file columns. */
  private static final int ID = 0;

  /** The effective time column. */
  private static final int EFFECTIVE_TIME = 1;

  /** The active column. */
  private static final int ACTIVE = 2;

  /** The module column. */
  private static final int MODULE = 3;

  /** The concept id column (description) / refset id column (refset). */
  private static final int CONCEPT_OR_REFSET = 4;

  /** The language code column (description) / referenced component column (refset). */
  private static final int LANGUAGE_OR_REFERENCED = 5;

  /** The type column (description) / acceptability column (language refset). */
  private static final int TYPE_OR_ACCEPTABILITY = 6;

  /** The term column. */
  private static final int TERM = 7;

  /** The case significance column. */
  private static final int CASE_SIGNIFICANCE = 8;

  /** The factory. */
  private final OWLDataFactory factory;

  /** The descriptionId annotation property. */
  private final OWLAnnotationProperty descriptionId;

  /** The typeId annotation property. */
  private final OWLAnnotationProperty typeId;

  /** The moduleId annotation property. */
  private final OWLAnnotationProperty moduleId;

  /** The effectiveTime annotation property. */
  private final OWLAnnotationProperty effectiveTime;

  /** The caseSignificanceId annotation property. */
  private final OWLAnnotationProperty caseSignificanceId;

  /**
   * Instantiates a new annotator.
   *
   * @param factory the factory
   */
  public DescriptionAnnotator(final OWLDataFactory factory) {
    this.factory = factory;
    this.descriptionId = property(SCT + "descriptionId");
    this.typeId = property(SCT + "typeId");
    this.moduleId = property(SCT + "moduleId");
    this.effectiveTime = property(SCT + "effectiveTime");
    this.caseSignificanceId = property(SCT + "caseSignificanceId");
  }

  /**
   * Returns an annotation property.
   *
   * @param iri the iri
   * @return the property
   */
  private OWLAnnotationProperty property(final String iri) {
    return factory.getOWLAnnotationProperty(IRI.create(iri));
  }

  /**
   * Returns the concept metadata axioms (module and effective time) for the given concepts.
   *
   * @param concepts the concepts
   * @param conceptIds the ids to include
   * @return the axioms
   */
  public Set<OWLAxiom> conceptAxioms(final Map<Long, RefsetMembershipReader.Rf2Concept> concepts,
    final Set<Long> conceptIds) {
    final Set<OWLAxiom> axioms = new HashSet<>();
    axioms.add(factory.getOWLDeclarationAxiom(moduleId));
    axioms.add(factory.getOWLDeclarationAxiom(effectiveTime));
    for (final Long id : conceptIds) {
      final RefsetMembershipReader.Rf2Concept concept = concepts.get(id);
      final IRI iri = IRI.create(SCT + id);
      axioms.add(factory.getOWLAnnotationAssertionAxiom(moduleId, iri, IRI.create(SCT + concept.moduleId())));
      axioms.add(factory.getOWLAnnotationAssertionAxiom(effectiveTime, iri,
          factory.getOWLLiteral(concept.effectiveTime())));
    }
    return axioms;
  }

  /**
   * Returns the description axioms for the given concepts.
   *
   * @param descriptionFiles the description and text definition snapshot files
   * @param languageFiles the language refset snapshot files
   * @param conceptIds the ids of the concepts to include
   * @return the axioms
   * @throws IOException Signals that an I/O exception has occurred.
   */
  public Set<OWLAxiom> descriptionAxioms(final List<Path> descriptionFiles, final List<Path> languageFiles,
    final Set<Long> conceptIds) throws IOException {
    final Map<Long, List<long[]>> acceptability = readLanguageMembers(languageFiles);
    final Set<OWLAxiom> axioms = new HashSet<>();
    final Set<Long> languageRefsets = new HashSet<>();
    for (final String iri : List.of(SCT + "descriptionId", SCT + "typeId", SCT + "caseSignificanceId",
        SKOS + "prefLabel", SKOS + "altLabel", SKOS + "definition")) {
      axioms.add(factory.getOWLDeclarationAxiom(property(iri)));
    }
    for (final Path file : descriptionFiles) {
      long count = 0;
      try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
        String line = reader.readLine();
        while ((line = reader.readLine()) != null) {
          final String[] fields = line.split("\t");
          if (fields.length <= CASE_SIGNIFICANCE || !"1".equals(fields[ACTIVE])) {
            continue;
          }
          final Long conceptId = Long.valueOf(fields[CONCEPT_OR_REFSET]);
          if (!conceptIds.contains(conceptId)) {
            continue;
          }
          final long id = Long.parseLong(fields[ID]);
          final List<long[]> members = acceptability.getOrDefault(id, List.of());
          final Set<OWLAnnotation> annotations = new HashSet<>();
          annotations.add(factory.getOWLAnnotation(descriptionId, factory.getOWLLiteral(fields[ID])));
          annotations.add(factory.getOWLAnnotation(typeId, IRI.create(SCT + fields[TYPE_OR_ACCEPTABILITY])));
          annotations.add(factory.getOWLAnnotation(moduleId, IRI.create(SCT + fields[MODULE])));
          annotations.add(factory.getOWLAnnotation(effectiveTime, factory.getOWLLiteral(fields[EFFECTIVE_TIME])));
          annotations.add(
              factory.getOWLAnnotation(caseSignificanceId, IRI.create(SCT + fields[CASE_SIGNIFICANCE])));
          boolean preferred = false;
          for (final long[] member : members) {
            annotations.add(factory.getOWLAnnotation(property(SCT + member[0]), IRI.create(SCT + member[1])));
            languageRefsets.add(member[0]);
            preferred |= member[1] == PREFERRED;
          }
          final String type = fields[TYPE_OR_ACCEPTABILITY];
          final OWLAnnotationProperty label = FSN.equals(type) ? factory.getRDFSLabel()
              : DEFINITION.equals(type) ? property(SKOS + "definition")
                  : property(SKOS + (preferred ? "prefLabel" : "altLabel"));
          axioms.add(factory.getOWLAnnotationAssertionAxiom(label, IRI.create(SCT + conceptId),
              factory.getOWLLiteral(fields[TERM], fields[LANGUAGE_OR_REFERENCED]), annotations));
          count++;
        }
      }
      LOGGER.info("  {} active descriptions in {}", count, file.getFileName());
    }
    for (final Long refset : languageRefsets) {
      axioms.add(factory.getOWLDeclarationAxiom(property(SCT + refset)));
    }
    return axioms;
  }

  /**
   * Read the active language refset members: description id to (refset id, acceptability id) pairs.
   *
   * @param languageFiles the language refset files
   * @return the members by description id
   * @throws IOException Signals that an I/O exception has occurred.
   */
  private static Map<Long, List<long[]>> readLanguageMembers(final List<Path> languageFiles) throws IOException {
    final Map<Long, List<long[]>> members = new HashMap<>();
    for (final Path file : languageFiles) {
      try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
        String line = reader.readLine();
        while ((line = reader.readLine()) != null) {
          final String[] fields = line.split("\t");
          if (fields.length <= TYPE_OR_ACCEPTABILITY || !"1".equals(fields[ACTIVE])) {
            continue;
          }
          members.computeIfAbsent(Long.valueOf(fields[LANGUAGE_OR_REFERENCED]), k -> new ArrayList<>(2))
              .add(new long[] {
                  Long.parseLong(fields[CONCEPT_OR_REFSET]), Long.parseLong(fields[TYPE_OR_ACCEPTABILITY])
              });
        }
      }
    }
    return members;
  }

}
