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

import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.LongStream;
import org.apache.jena.graph.Graph;
import org.apache.jena.graph.GraphMemFactory;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.graph.Triple;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QueryExecutionDatasetBuilder;
import org.apache.jena.query.ResultSet;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.util.iterator.ExtendedIterator;
import org.apache.jena.vocabulary.OWL;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.apache.jena.vocabulary.SKOS;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.TurtleDocumentFormat;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * In-memory SPARQL store (Apache Jena) holding a SNOMED CT OWL file produced by the "--release" mode.
 *
 * <p>
 * The OWL file is loaded with the OWL API and streamed into Jena as RDF (the standard OWL to RDF mapping). The
 * inferred class axioms map to blank nodes (e.g. {@code :c rdfs:subClassOf [ owl:intersectionOf (:p1 :p2 ...) ]}),
 * so after loading, each concept's named (proximal) parents are also asserted directly as
 * {@code :c rdfs:subClassOf :p}. These triples are entailed by the axioms and let the hierarchy be queried with
 * simple property paths ({@code rdfs:subClassOf+}). Note: queries must still restrict results to IRIs, since
 * class expressions are blank nodes typed {@code owl:Class}.
 * </p>
 *
 * <p>
 * The model is read-only once loaded, so it may be queried concurrently.
 * </p>
 */
public class SnomedStore {

  /** The logger. */
  private static final Logger LOGGER = LoggerFactory.getLogger(SnomedStore.class);

  /** The SNOMED CT concept namespace. */
  public static final String SCT = "http://snomed.info/id/";

  /** The isMemberOf annotation property. */
  public static final String IS_MEMBER_OF = SCT + "isMemberOf";

  /** Index property: concept to each attribute restriction of its class axiom (grouped or not). */
  public static final String HAS_RESTRICTION = SCT + "hasRestriction";

  /** Index property: concept to the filler of each role group of its class axiom. */
  public static final String HAS_ROLE_GROUP = SCT + "hasRoleGroup";

  /** The role group attribute. */
  private static final Node ROLE_GROUP = NodeFactory.createURI(SCT + "609096000");

  /** The ontology version IRI prefix. */
  private static final String VERSION_PREFIX = "/version/";

  /** The model. */
  private final Model model;

  /** The release version (e.g. 20261001), or null if the ontology has no version IRI. */
  private final String version;

  /** The concept count. */
  private final long conceptCount;

  /**
   * Instantiates a new store from an existing model (already including the named parent triples).
   *
   * @param model the model
   * @param version the version
   */
  SnomedStore(final Model model, final String version) {
    this.model = model;
    this.version = version;
    this.conceptCount = countConcepts(model.getGraph());
  }

  /**
   * Load an OWL file (any OWL API supported syntax, e.g. functional syntax).
   *
   * @param owlFile the OWL file
   * @return the store
   * @throws Exception the exception
   */
  public static SnomedStore load(final Path owlFile) throws Exception {
    long start = System.currentTimeMillis();
    LOGGER.info("Loading OWL file {}", owlFile);
    final OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
    final OWLOntology ontology = manager.loadOntologyFromOntologyDocument(owlFile.toFile());
    final String version = ontology.getOntologyID().getVersionIRI().transform(SnomedStore::toVersion).orNull();
    LOGGER.info("  OWL API: {} axioms, version {} ({}s)", ontology.getAxiomCount(), version, seconds(start));

    start = System.currentTimeMillis();
    final Model model = ModelFactory.createModelForGraph(GraphMemFactory.createDefaultGraph());
    toRdf(manager, ontology, model);
    manager.removeOntology(ontology);
    LOGGER.info("  Jena: {} triples ({}s)", model.size(), seconds(start));

    start = System.currentTimeMillis();
    final long added = addNamedParents(model.getGraph());
    LOGGER.info("  Named parents: {} rdfs:subClassOf triples added ({}s)", added, seconds(start));

    start = System.currentTimeMillis();
    final long indexed = addRestrictionIndex(model.getGraph());
    LOGGER.info("  Restriction index: {} hasRestriction/hasRoleGroup triples added ({}s)", indexed,
        seconds(start));

    final SnomedStore store = new SnomedStore(model, version);
    LOGGER.info("  Concepts: {}", store.getConceptCount());
    return store;
  }

  /**
   * Stream the ontology into the model as Turtle, written by the OWL API on a separate thread.
   *
   * @param manager the manager
   * @param ontology the ontology
   * @param model the model
   * @throws Exception the exception
   */
  private static void toRdf(final OWLOntologyManager manager, final OWLOntology ontology, final Model model)
    throws Exception {
    final AtomicReference<Exception> writeError = new AtomicReference<>();
    try (PipedInputStream in = new PipedInputStream(1 << 20)) {
      final PipedOutputStream out = new PipedOutputStream(in);
      final Thread writer = new Thread(() -> {
        try (out) {
          manager.saveOntology(ontology, new TurtleDocumentFormat(), out);
        } catch (final Exception e) {
          writeError.set(e);
        }
      }, "owl-to-turtle");
      writer.start();
      try {
        RDFDataMgr.read(model, in, Lang.TURTLE);
      } finally {
        // Unblock the writer if the reader failed, then wait for it
        in.close();
        writer.join(TimeUnit.MINUTES.toMillis(1));
      }
    }
    if (writeError.get() != null) {
      throw writeError.get();
    }
  }

  /**
   * Assert {@code :c rdfs:subClassOf :p} for each named class {@code :p} in the intersection on the right hand
   * side of a concept's SubClassOf/EquivalentClasses axiom (or the named class itself).
   *
   * @param graph the graph
   * @return the number of triples added
   */
  static long addNamedParents(final Graph graph) {
    final List<Triple> parents = new ArrayList<>();
    for (final Node predicate : List.of(RDFS.subClassOf.asNode(), OWL.equivalentClass.asNode())) {
      final ExtendedIterator<Triple> it = graph.find(Node.ANY, predicate, Node.ANY);
      try {
        while (it.hasNext()) {
          final Triple triple = it.next();
          final Node child = triple.getSubject();
          final Node definition = triple.getObject();
          if (!child.isURI()) {
            continue;
          }
          if (definition.isURI()) {
            if (!predicate.equals(RDFS.subClassOf.asNode())) {
              parents.add(Triple.create(child, RDFS.subClassOf.asNode(), definition));
            }
            continue;
          }
          for (final Node member : listMembers(graph, definition)) {
            if (member.isURI()) {
              parents.add(Triple.create(child, RDFS.subClassOf.asNode(), member));
            }
          }
        }
      } finally {
        it.close();
      }
    }
    final long before = graph.size();
    parents.forEach(graph::add);
    return graph.size() - before;
  }

  /**
   * Index each concept's attribute restrictions: {@code :c sct:hasRestriction _:r} for each restriction in the
   * concept's class axiom, ungrouped or inside a role group, and {@code :c sct:hasRoleGroup _:g} for the filler of
   * each role group. Navigating these blank node structures with property paths is fast from the concept, but very
   * slow in reverse (from a value back to the concept); the index makes both directions a single indexed hop, and
   * keeps the restriction nodes, so relationships can still be counted.
   *
   * @param graph the graph
   * @return the number of triples added
   */
  static long addRestrictionIndex(final Graph graph) {
    final Node hasRestriction = NodeFactory.createURI(HAS_RESTRICTION);
    final Node hasRoleGroup = NodeFactory.createURI(HAS_ROLE_GROUP);
    final List<Triple> index = new ArrayList<>();
    for (final Node predicate : List.of(RDFS.subClassOf.asNode(), OWL.equivalentClass.asNode())) {
      final ExtendedIterator<Triple> it = graph.find(Node.ANY, predicate, Node.ANY);
      try {
        while (it.hasNext()) {
          final Triple triple = it.next();
          if (!triple.getSubject().isURI() || !triple.getObject().isBlank()) {
            continue;
          }
          final Node concept = triple.getSubject();
          for (final Node member : membersOrSelf(graph, triple.getObject())) {
            final Node property = first(graph, member, OWL.onProperty.asNode());
            if (property == null) {
              continue;
            }
            if (property.equals(ROLE_GROUP)) {
              final Node filler = first(graph, member, OWL.someValuesFrom.asNode());
              if (filler == null) {
                continue;
              }
              index.add(Triple.create(concept, hasRoleGroup, filler));
              for (final Node grouped : membersOrSelf(graph, filler)) {
                if (first(graph, grouped, OWL.onProperty.asNode()) != null) {
                  index.add(Triple.create(concept, hasRestriction, grouped));
                }
              }
            } else {
              index.add(Triple.create(concept, hasRestriction, member));
            }
          }
        }
      } finally {
        it.close();
      }
    }
    final long before = graph.size();
    index.forEach(graph::add);
    return graph.size() - before;
  }

  /**
   * Returns the members of a node's owl:intersectionOf list, or the node itself if it is not an intersection.
   *
   * @param graph the graph
   * @param node the class expression node
   * @return the members
   */
  private static List<Node> membersOrSelf(final Graph graph, final Node node) {
    final List<Node> members = listMembers(graph, node);
    return members.isEmpty() ? List.of(node) : members;
  }

  /**
   * List the members of a node's owl:intersectionOf list.
   *
   * @param graph the graph
   * @param node the class expression node
   * @return the members (empty if not an intersection)
   */
  private static List<Node> listMembers(final Graph graph, final Node node) {
    final List<Node> members = new ArrayList<>();
    Node list = first(graph, node, OWL.intersectionOf.asNode());
    while (list != null && !list.equals(RDF.nil.asNode())) {
      final Node member = first(graph, list, RDF.first.asNode());
      if (member != null) {
        members.add(member);
      }
      list = first(graph, list, RDF.rest.asNode());
    }
    return members;
  }

  /**
   * Returns the first object of the subject/predicate, or null.
   *
   * @param graph the graph
   * @param subject the subject
   * @param predicate the predicate
   * @return the object, or null
   */
  private static Node first(final Graph graph, final Node subject, final Node predicate) {
    final ExtendedIterator<Triple> it = graph.find(subject, predicate, Node.ANY);
    try {
      return it.hasNext() ? it.next().getObject() : null;
    } finally {
      it.close();
    }
  }

  /**
   * Count the concepts (classes declared with a SNOMED CT IRI).
   *
   * @param graph the graph
   * @return the count
   */
  private static long countConcepts(final Graph graph) {
    final ExtendedIterator<Triple> it = graph.find(Node.ANY, RDF.type.asNode(), OWL.Class.asNode());
    try {
      return it.filterKeep(t -> t.getSubject().isURI() && t.getSubject().getURI().startsWith(SCT)).toList().size();
    } finally {
      it.close();
    }
  }

  /**
   * Extract the version from a version IRI (e.g. http://snomed.info/sct/900000000000207008/version/20261001).
   *
   * @param iri the version IRI
   * @return the version
   */
  private static String toVersion(final IRI iri) {
    final String s = iri.toString();
    final int index = s.lastIndexOf(VERSION_PREFIX);
    return index < 0 ? s : s.substring(index + VERSION_PREFIX.length());
  }

  /**
   * Seconds since the start time.
   *
   * @param start the start time
   * @return the seconds
   */
  private static long seconds(final long start) {
    return (System.currentTimeMillis() - start) / 1000;
  }

  /**
   * Run a SPARQL SELECT query and return the SNOMED CT codes bound to the {@code ?concept} variable (or the
   * first variable, if there is no {@code ?concept}), distinct and in numeric order. Non-SNOMED CT bindings
   * (e.g. blank nodes) are ignored.
   *
   * @param sparql the SPARQL SELECT query
   * @param timeoutMs the timeout in milliseconds (0 for none)
   * @return the codes
   */
  public long[] selectCodes(final String sparql, final long timeoutMs) {
    final QueryExecutionDatasetBuilder builder = QueryExecution.model(model).query(sparql);
    if (timeoutMs > 0) {
      builder.timeout(timeoutMs, TimeUnit.MILLISECONDS);
    }
    try (QueryExecution execution = builder.build()) {
      final ResultSet results = execution.execSelect();
      final List<String> vars = results.getResultVars();
      final String var = vars.contains("concept") ? "concept" : vars.get(0);
      final LongStream.Builder codes = LongStream.builder();
      while (results.hasNext()) {
        final long code = toCode(results.next().get(var));
        if (code > 0) {
          codes.add(code);
        }
      }
      return codes.build().sorted().distinct().toArray();
    }
  }

  /**
   * Returns the SNOMED CT code of a node, or -1 if it is not a SNOMED CT concept IRI.
   *
   * @param node the node
   * @return the code
   */
  private static long toCode(final RDFNode node) {
    if (node == null || !node.isURIResource() || !node.asResource().getURI().startsWith(SCT)) {
      return -1;
    }
    try {
      return Long.parseLong(node.asResource().getURI().substring(SCT.length()));
    } catch (final NumberFormatException e) {
      return -1;
    }
  }

  /**
   * Returns the fully specified name (rdfs:label) of a concept.
   *
   * @param code the code
   * @return the FSN, or null
   */
  public String getFsn(final long code) {
    return getLiteral(code, RDFS.label.asNode());
  }

  /**
   * Returns the preferred term (skos:prefLabel) of a concept.
   *
   * @param code the code
   * @return the preferred term, or null
   */
  public String getPreferredTerm(final long code) {
    return getLiteral(code, SKOS.prefLabel.asNode());
  }

  /**
   * Returns the lexical form of the first literal of a concept's property.
   *
   * @param code the code
   * @param predicate the predicate
   * @return the value, or null
   */
  private String getLiteral(final long code, final Node predicate) {
    final Node value = first(model.getGraph(), NodeFactory.createURI(SCT + code), predicate);
    return value != null && value.isLiteral() ? value.getLiteralLexicalForm() : null;
  }

  /**
   * Returns the model.
   *
   * @return the model
   */
  public Model getModel() {
    return model;
  }

  /**
   * Returns the version.
   *
   * @return the version
   */
  public String getVersion() {
    return version;
  }

  /**
   * Returns the concept count.
   *
   * @return the concept count
   */
  public long getConceptCount() {
    return conceptCount;
  }

  /**
   * Returns the triple count.
   *
   * @return the triple count
   */
  public long getTripleCount() {
    return model.size();
  }

}
