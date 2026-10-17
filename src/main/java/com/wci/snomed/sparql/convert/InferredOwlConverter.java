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

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.ihtsdo.otf.snomedboot.ReleaseImporter;
import org.ihtsdo.otf.snomedboot.factory.LoadingProfile;
import org.semanticweb.elk.owlapi.ElkReasonerFactory;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.AxiomType;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotationProperty;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClassAxiom;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.reasoner.InferenceType;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.semanticweb.owlapi.reasoner.SimpleConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.snomed.otf.owltoolkit.classification.ReasonerTaxonomy;
import org.snomed.otf.owltoolkit.classification.ReasonerTaxonomyWalker;
import org.snomed.otf.owltoolkit.constants.Concepts;
import org.snomed.otf.owltoolkit.conversion.AxiomRelationshipConversionService;
import org.snomed.otf.owltoolkit.conversion.RF2ToOWLService;
import org.snomed.otf.owltoolkit.domain.AxiomRepresentation;
import org.snomed.otf.owltoolkit.domain.Relationship;
import org.snomed.otf.owltoolkit.normalform.RelationshipNormalFormGenerator;
import org.snomed.otf.owltoolkit.ontology.OntologyService;
import org.snomed.otf.owltoolkit.taxonomy.SnomedTaxonomy;
import org.snomed.otf.owltoolkit.taxonomy.SnomedTaxonomyLoader;

/**
 * Converts an RF2 release into an OWL ontology (functional syntax) representing the inferred view:
 *
 * <ol>
 * <li>Load the RF2 snapshot and build the stock (stated) ontology with snomed-owl-toolkit.</li>
 * <li>Classify it with ELK and generate the inferred relationships in normal form (as the toolkit's
 * classification service does).</li>
 * <li>Replace every class axiom with one generated from the concept's inferred relationships (EquivalentClasses
 * for fully defined concepts, SubClassOf otherwise), in the same OWL shape the toolkit uses.</li>
 * <li>Add {@code AnnotationAssertion(:isMemberOf :member :refset)} for each active concept refset membership.</li>
 * <li>Add {@code AnnotationAssertion(:refset :source :target)} for each active association refset member (e.g.
 * historical associations), with each association refset declared as an annotation property.</li>
 * <li>Inactive concepts referenced by the above are annotated {@code owl:deprecated}, but are not declared as
 * classes.</li>
 * <li>Concept moduleId / effectiveTime, and descriptions with their RF2 fields as axiom annotations (see
 * {@link DescriptionAnnotator}), replacing the toolkit's term-only description annotations.</li>
 * </ol>
 */
public class InferredOwlConverter {

  /** The logger. */
  private static final Logger LOGGER = LoggerFactory.getLogger(InferredOwlConverter.class);

  /** The IRI of the refset membership annotation property. */
  public static final String IS_MEMBER_OF_IRI = OntologyService.SNOMED_CORE_COMPONENTS_URI + "isMemberOf";

  /** RF2 loading profile: same content as the toolkit's snapshot profile, plus descriptions/language refsets. */
  private static final LoadingProfile LOADING_PROFILE = new LoadingProfile()
      .withConcepts()
      .withRelationships()
      .withInactiveConcepts()
      .withStatedRelationships()
      .withInactiveRelationships()
      .withAllRefsets()
      .withIncludedReferenceSetFilenamePattern(".*_sRefset_.*OWL.*")
      .withIncludedReferenceSetFilenamePattern(".*_cissccRefset_.*MRCMAttributeDomain.*");

  /**
   * Convert the release to an inferred OWL file.
   *
   * @param releaseDir the release dir (containing a "Snapshot" folder, or the snapshot files directly)
   * @param outputFile the output OWL file
   * @return the conversion statistics
   * @throws Exception the exception
   */
  public Stats convert(final Path releaseDir, final Path outputFile) throws Exception {
    final ReleaseFolder release = ReleaseFolder.resolve(releaseDir);
    LOGGER.info("Converting RF2 snapshot {} (version {})", release.getSnapshotDir(), release.getVersionDate());
    final Stats stats = new Stats();

    // 1. Load RF2
    final SnomedTaxonomy taxonomy = loadTaxonomy(release);
    final String ontologyUri = getOntologyUri(taxonomy);
    final Set<Long> ungroupedRoles =
        taxonomy.getUngroupedRolesForContentTypeOrDefault(Long.parseLong(Concepts.ALL_PRECOORDINATED_CONTENT));
    stats.concepts = taxonomy.getAllConceptIds().size();

    // 2. Classify the stated ontology (without descriptions, as the toolkit does) with ELK and generate the
    // inferred normal form
    final InferredRelationshipCollector collector = classify(taxonomy, ungroupedRoles, stats);

    // Build the output ontology: the stock (stated) ontology, without descriptions (added in step 5, annotated)
    final OntologyService ontologyService = new OntologyService(ungroupedRoles);
    final OWLOntology ontology =
        ontologyService.createOntology(taxonomy, ontologyUri, release.getVersionDate(), false);
    LOGGER.info("Stated ontology built: {} concepts, {} axioms", stats.concepts, ontology.getAxiomCount());

    // 3. Replace stated class axioms with inferred ones
    final Set<OWLAxiom> statedClassAxioms = ontology.getAxioms().stream()
        .filter(a -> a instanceof OWLClassAxiom).collect(Collectors.toSet());
    ontology.getOWLOntologyManager().removeAxioms(ontology, statedClassAxioms);
    final OWLDataFactory factory = OWLManager.getOWLDataFactory();
    final Set<OWLAxiom> inferredAxioms = new HashSet<>();
    for (final Map.Entry<Long, List<Relationship>> entry : collector.getInferred().entrySet()) {
      inferredAxioms.add(factory.getOWLDeclarationAxiom(factory.getOWLClass(conceptIri(entry.getKey()))));
      // The root concept has no relationships: declare it only (rather than SubClassOf owl:Thing)
      if (entry.getValue().isEmpty()) {
        continue;
      }
      final AxiomRepresentation representation = new AxiomRepresentation();
      representation.setLeftHandSideNamedConcept(entry.getKey());
      representation.setRightHandSideRelationships(entry.getValue().stream()
          .collect(Collectors.groupingBy(Relationship::getGroup, TreeMap::new, Collectors.toList())));
      representation.setPrimitive(!taxonomy.getFullyDefinedConceptIds().contains(entry.getKey()));
      inferredAxioms.add(ontologyService.createOwlClassAxiom(representation));
    }
    ontology.getOWLOntologyManager().addAxioms(ontology, inferredAxioms);
    stats.statedClassAxiomsRemoved = statedClassAxioms.size();
    stats.inferredClassAxioms = inferredAxioms.stream().filter(a -> a instanceof OWLClassAxiom).count();
    LOGGER.info("Replaced {} stated class axioms with {} inferred class axioms", stats.statedClassAxiomsRemoved,
        stats.inferredClassAxioms);

    // 4. Add refset memberships and historical associations (members may be inactive concepts)
    final Map<Long, RefsetMembershipReader.Rf2Concept> rf2Concepts =
        RefsetMembershipReader.readConcepts(release.getConceptFile());
    final Map<Long, Set<Long>> memberships =
        RefsetMembershipReader.read(release.getRefsetFiles(), rf2Concepts.keySet());
    final Map<Long, Set<RefsetMembershipReader.Association>> associations =
        RefsetMembershipReader.readAssociations(release.getAssociationFiles(), rf2Concepts.keySet());
    final OWLAnnotationProperty isMemberOf = factory.getOWLAnnotationProperty(IRI.create(IS_MEMBER_OF_IRI));
    final Set<OWLAxiom> membershipAxioms = new HashSet<>();
    final Set<Long> referenced = new HashSet<>();
    membershipAxioms.add(factory.getOWLDeclarationAxiom(isMemberOf));
    for (final Map.Entry<Long, Set<Long>> entry : memberships.entrySet()) {
      final IRI refset = conceptIri(entry.getKey());
      for (final Long member : entry.getValue()) {
        membershipAxioms.add(factory.getOWLAnnotationAssertionAxiom(isMemberOf, conceptIri(member), refset));
        referenced.add(member);
      }
    }
    stats.refsets = memberships.size();
    stats.memberships = membershipAxioms.size() - 1;
    for (final Map.Entry<Long, Set<RefsetMembershipReader.Association>> entry : associations.entrySet()) {
      // Each association refset is an annotation property: AnnotationAssertion(:refset :source :target)
      final OWLAnnotationProperty property = factory.getOWLAnnotationProperty(conceptIri(entry.getKey()));
      membershipAxioms.add(factory.getOWLDeclarationAxiom(property));
      for (final RefsetMembershipReader.Association association : entry.getValue()) {
        membershipAxioms.add(factory.getOWLAnnotationAssertionAxiom(property, conceptIri(association.source()),
            conceptIri(association.target())));
        referenced.add(association.source());
        referenced.add(association.target());
        stats.associations++;
      }
    }
    // Inactive concepts are not declared as classes (so they are not matched by "*" or the hierarchy); they are
    // marked owl:deprecated (and get descriptions and metadata like active concepts, below)
    final Set<Long> inactive = referenced.stream().filter(id -> !rf2Concepts.get(id).active())
        .collect(Collectors.toCollection(TreeSet::new));
    for (final Long id : inactive) {
      membershipAxioms.add(factory.getDeprecatedOWLAnnotationAssertionAxiom(conceptIri(id)));
    }
    stats.inactiveConcepts = inactive.size();
    ontology.getOWLOntologyManager().addAxioms(ontology, membershipAxioms);
    LOGGER.info("Added {} isMemberOf annotations for {} refsets, {} associations in {} association refsets, "
        + "{} inactive concepts", stats.memberships, stats.refsets, stats.associations, associations.size(),
        stats.inactiveConcepts);

    // 5. Concept metadata and descriptions, with their RF2 fields as axiom annotations
    final Set<Long> conceptIds = new HashSet<>(taxonomy.getAllConceptIds());
    conceptIds.addAll(inactive);
    final DescriptionAnnotator annotator = new DescriptionAnnotator(factory);
    final Set<OWLAxiom> metadataAxioms = annotator.conceptAxioms(rf2Concepts, conceptIds);
    ontology.getOWLOntologyManager().addAxioms(ontology, metadataAxioms);
    final Set<OWLAxiom> descriptionAxioms =
        annotator.descriptionAxioms(release.getDescriptionFiles(), release.getLanguageFiles(), conceptIds);
    ontology.getOWLOntologyManager().addAxioms(ontology, descriptionAxioms);
    stats.descriptions = descriptionAxioms.stream().filter(a -> a.isOfType(AxiomType.ANNOTATION_ASSERTION)).count();
    LOGGER.info("Added module/effectiveTime for {} concepts and {} descriptions", conceptIds.size(),
        stats.descriptions);

    // 6. Save
    save(taxonomy, ontology, ontologyService, outputFile);
    LOGGER.info("Wrote {} ({} axioms)", outputFile, ontology.getAxiomCount());
    return stats;
  }

  /**
   * Load the RF2 snapshot into a toolkit taxonomy.
   *
   * @param release the release
   * @return the snomed taxonomy
   * @throws Exception the exception
   */
  private SnomedTaxonomy loadTaxonomy(final ReleaseFolder release) throws Exception {
    final SnomedTaxonomyLoader loader = new SnomedTaxonomyLoader();
    new ReleaseImporter().loadEffectiveSnapshotReleaseFiles(Set.of(release.getSnapshotDir().toString()),
        LOADING_PROFILE, loader, false);
    final SnomedTaxonomy taxonomy = loader.getSnomedTaxonomy();
    LOGGER.info("Loaded {} active concepts, {} active axioms", taxonomy.getAllConceptIds().size(),
        taxonomy.getAxiomCount());
    if (taxonomy.getAxiomCount() == 0 && taxonomy.getStatedRelationships().isEmpty()) {
      throw new IllegalStateException("No OWL axioms or stated relationships found in " + release.getSnapshotDir());
    }
    return taxonomy;
  }

  /**
   * Classify the stated ontology with ELK and generate the inferred relationships in normal form.
   *
   * @param taxonomy the taxonomy
   * @param ungroupedRoles the ungrouped roles
   * @param stats the stats
   * @return the collected inferred relationships
   * @throws Exception the exception
   */
  private InferredRelationshipCollector classify(final SnomedTaxonomy taxonomy, final Set<Long> ungroupedRoles,
    final Stats stats) throws Exception {
    final OntologyService ontologyService = new OntologyService(ungroupedRoles);
    final OWLOntology ontology = ontologyService.createOntology(taxonomy);
    LOGGER.info("Classifying with ELK ({} axioms)", ontology.getAxiomCount());
    final OWLReasoner reasoner = new ElkReasonerFactory().createReasoner(ontology, new SimpleConfiguration());
    final ReasonerTaxonomy reasonerTaxonomy;
    try {
      reasoner.precomputeInferences(InferenceType.CLASS_HIERARCHY);
      reasonerTaxonomy = new ReasonerTaxonomyWalker(reasoner, new ReasonerTaxonomy()).walk();
    } finally {
      reasoner.dispose();
    }
    stats.equivalentSets = reasonerTaxonomy.getEquivalentConceptIds().size();
    stats.unsatisfiable = reasonerTaxonomy.getUnsatisfiableConceptIds().size();
    if (stats.equivalentSets > 0 || stats.unsatisfiable > 0) {
      LOGGER.warn("Classification found {} sets of equivalent concepts and {} unsatisfiable concepts",
          stats.equivalentSets, stats.unsatisfiable);
    }

    LOGGER.info("Generating inferred normal form");
    final Map<Long, Set<AxiomRepresentation>> conceptAxiomStatements = new AxiomRelationshipConversionService(
        ungroupedRoles).convertAxiomsToRelationships(taxonomy.getConceptAxiomMap(), true);
    final InferredRelationshipCollector collector = new InferredRelationshipCollector();
    new RelationshipNormalFormGenerator(reasonerTaxonomy, taxonomy, conceptAxiomStatements,
        ontologyService.getPropertyChains(ontology)).collectNormalFormChanges(collector);
    stats.relationshipsAdded = collector.getAddedCount();
    stats.relationshipsRemoved = collector.getRemovedStatements().values().stream().mapToLong(Collection::size)
        .sum();
    LOGGER.info("Inferred relationships for {} concepts; vs. release inferred file: {} added, {} removed",
        collector.getInferred().size(), stats.relationshipsAdded, stats.relationshipsRemoved);
    ontology.getOWLOntologyManager().removeOntology(ontology);
    return collector;
  }

  /**
   * Returns the ontology URI from the OWL ontology refset header (as the toolkit's RF2ToOWLService does).
   *
   * @param taxonomy the taxonomy
   * @return the ontology uri
   */
  private String getOntologyUri(final SnomedTaxonomy taxonomy) {
    final Collection<String> headers = taxonomy.getOntologyHeader().values();
    if (headers.size() != 1) {
      LOGGER.warn("Expected one ontology header but found {}, using {}", headers.size(),
          OntologyService.SNOMED_INTERNATIONAL_EDITION_URI);
      return OntologyService.SNOMED_INTERNATIONAL_EDITION_URI;
    }
    final String header = headers.iterator().next();
    return header.substring(RF2ToOWLService.HEADER_PREFIX.length(),
        header.length() - RF2ToOWLService.HEADER_SUFFIX.length());
  }

  /**
   * Save the ontology in SNOMED functional syntax, preceded by the toolkit's copyright notice and any
   * non-default namespaces (as the toolkit's RF2ToOWLService does).
   *
   * @param taxonomy the taxonomy
   * @param ontology the ontology
   * @param ontologyService the ontology service
   * @param outputFile the output file
   * @throws Exception the exception
   */
  private void save(final SnomedTaxonomy taxonomy, final OWLOntology ontology,
    final OntologyService ontologyService, final Path outputFile) throws Exception {
    final Path parent = outputFile.toAbsolutePath().getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }
    try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(outputFile))) {
      final Writer writer = new OutputStreamWriter(out, StandardCharsets.UTF_8);
      writer.write(getCopyrightNotice());
      writer.write("\n");
      for (final String namespace : new TreeSet<>(taxonomy.getOntologyNamespaces().values())) {
        if (!namespace.startsWith("Prefix(:=") && !isDefaultPrefix(namespace)) {
          writer.write(namespace);
          writer.write("\n");
        }
      }
      writer.flush();
      ontologyService.saveOntology(ontology, out);
    }
  }

  /**
   * Indicates whether the prefix declaration is one the OWL API writes itself.
   *
   * @param namespace the namespace prefix declaration
   * @return true, if default
   */
  private static boolean isDefaultPrefix(final String namespace) {
    return namespace.startsWith("Prefix(xml:") || namespace.startsWith("Prefix(xsd:")
        || namespace.startsWith("Prefix(owl:") || namespace.startsWith("Prefix(rdf:")
        || namespace.startsWith("Prefix(rdfs:");
  }

  /**
   * Returns the toolkit's OWL file copyright notice.
   *
   * @return the copyright notice
   * @throws IOException Signals that an I/O exception has occurred.
   */
  private String getCopyrightNotice() throws IOException {
    try (InputStream in = RF2ToOWLService.class.getResourceAsStream("/owl-file-copyright-notice.txt")) {
      return in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  /**
   * Returns the IRI for a concept id.
   *
   * @param conceptId the concept id
   * @return the iri
   */
  private static IRI conceptIri(final Long conceptId) {
    return IRI.create(OntologyService.SNOMED_CORE_COMPONENTS_URI + conceptId);
  }

  /**
   * Statistics about a conversion.
   */
  public static class Stats {

    /** Number of active concepts. */
    private long concepts;

    /** Number of stated class axioms removed. */
    private long statedClassAxiomsRemoved;

    /** Number of inferred class axioms added. */
    private long inferredClassAxioms;

    /** Inferred relationships generated by classification that are not in the release inferred file. */
    private long relationshipsAdded;

    /** Release inferred relationships not generated by classification. */
    private long relationshipsRemoved;

    /** Number of sets of equivalent concepts found by classification. */
    private long equivalentSets;

    /** Number of unsatisfiable concepts found by classification. */
    private long unsatisfiable;

    /** Number of refsets with concept members. */
    private long refsets;

    /** Number of isMemberOf annotations. */
    private long memberships;

    /** Number of association annotations. */
    private long associations;

    /** Number of inactive concepts referenced by memberships or associations. */
    private long inactiveConcepts;

    /** Number of description annotations. */
    private long descriptions;

    /**
     * Returns the concepts.
     *
     * @return the concepts
     */
    public long getConcepts() {
      return concepts;
    }

    /**
     * Returns the stated class axioms removed.
     *
     * @return the stated class axioms removed
     */
    public long getStatedClassAxiomsRemoved() {
      return statedClassAxiomsRemoved;
    }

    /**
     * Returns the inferred class axioms.
     *
     * @return the inferred class axioms
     */
    public long getInferredClassAxioms() {
      return inferredClassAxioms;
    }

    /**
     * Returns the relationships added (vs. the release inferred file).
     *
     * @return the relationships added
     */
    public long getRelationshipsAdded() {
      return relationshipsAdded;
    }

    /**
     * Returns the relationships removed (vs. the release inferred file).
     *
     * @return the relationships removed
     */
    public long getRelationshipsRemoved() {
      return relationshipsRemoved;
    }

    /**
     * Returns the equivalent sets.
     *
     * @return the equivalent sets
     */
    public long getEquivalentSets() {
      return equivalentSets;
    }

    /**
     * Returns the unsatisfiable.
     *
     * @return the unsatisfiable
     */
    public long getUnsatisfiable() {
      return unsatisfiable;
    }

    /**
     * Returns the refsets.
     *
     * @return the refsets
     */
    public long getRefsets() {
      return refsets;
    }

    /**
     * Returns the memberships.
     *
     * @return the memberships
     */
    public long getMemberships() {
      return memberships;
    }

    /**
     * Returns the associations.
     *
     * @return the associations
     */
    public long getAssociations() {
      return associations;
    }

    /**
     * Returns the inactive concepts.
     *
     * @return the inactive concepts
     */
    public long getInactiveConcepts() {
      return inactiveConcepts;
    }

    /**
     * Returns the descriptions.
     *
     * @return the descriptions
     */
    public long getDescriptions() {
      return descriptions;
    }

  }

}
