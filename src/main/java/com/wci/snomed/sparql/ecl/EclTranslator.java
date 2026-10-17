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

import java.util.regex.Pattern;
import org.snomed.langauges.ecl.ECLObjectFactory;
import org.snomed.langauges.ecl.ECLQueryBuilder;
import org.snomed.langauges.ecl.domain.expressionconstraint.ExpressionConstraint;

/**
 * Translates an ECL expression to a SPARQL SELECT query binding {@code ?concept}.
 *
 * <p>
 * The expression is parsed with the snomed-ecl-parser grammar (an {@code ECLException} is thrown for invalid
 * syntax), then each expression constraint becomes a SPARQL group graph pattern that binds a variable to the
 * matching concepts. The queries assume the store built by {@code SnomedStore}: the OWL to RDF mapping of the
 * inferred OWL file, plus direct {@code rdfs:subClassOf} triples from each concept to its named (proximal)
 * parents, and {@code sct:isMemberOf} triples for active refset members.
 * </p>
 *
 * <ul>
 * <li>Hierarchy: {@code <} {@code rdfs:subClassOf+}, {@code <<} {@code rdfs:subClassOf*}, {@code <!}
 * {@code rdfs:subClassOf}, {@code <<!} {@code rdfs:subClassOf?}, and the reverse for ancestors.</li>
 * <li>Member of: {@code ^ R} is {@code ?concept sct:isMemberOf R}.</li>
 * <li>AND is a join, OR is a UNION, MINUS is a SPARQL MINUS.</li>
 * <li>Dot notation follows the attribute restrictions of each concept's class axiom (ungrouped, or inside a
 * role group {@code 609096000}) to their values.</li>
 * <li>Refinements are FILTER expressions on each focus concept over the same attribute restrictions (see
 * {@link SparqlGenerator}); concrete values use {@code owl:hasValue}.</li>
 * <li>History supplements follow the association refset annotations ({@code inactive refset target}).</li>
 * <li>Concept filters: definition status and active; member filters: referencedComponentId, and
 * targetComponentId of association refsets.</li>
 * </ul>
 *
 * <p>
 * Not supported (an {@link UnsupportedEclException} is thrown): description filters (not yet); module and effective
 * time filters and other refset member fields (not in the OWL file); member field selection; top/bottom of set
 * ({@code !!>}, {@code !!<}), which the parser drops.
 * </p>
 */
public class EclTranslator {

  /** The SPARQL prefixes. */
  public static final String PREFIXES = """
      PREFIX sct: <http://snomed.info/id/>
      PREFIX owl: <http://www.w3.org/2002/07/owl#>
      PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
      PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
      PREFIX xsd: <http://www.w3.org/2001/XMLSchema#>
      """;

  /** The result variable. */
  public static final String RESULT_VAR = "?concept";

  /** The role group attribute. */
  public static final String ROLE_GROUP = "sct:609096000";

  /**
   * Path from a concept to each attribute restriction of its class axiom, ungrouped or inside a role group (an
   * index built by {@code SnomedStore}; equivalent to {@code (rdfs:subClassOf|owl:equivalentClass)
   * /(owl:intersectionOf/rdf:rest*\/rdf:first)?/(owl:someValuesFrom/(owl:intersectionOf/rdf:rest*\/rdf:first)?)?}
   * restricted to restrictions).
   */
  public static final String ATTRIBUTE_PATH = "sct:hasRestriction";

  /** Path from a concept to the filler of each role group of its class axiom (an index built by SnomedStore). */
  public static final String GROUP_PATH = "sct:hasRoleGroup";

  /** Top/bottom of set operators, outside of terms, comments, and strings (the parser ignores them). */
  private static final Pattern TOP_BOTTOM = Pattern.compile("!!\\s*[<>]");

  /** Terms, comments, and string literals. */
  private static final Pattern NON_SYNTAX = Pattern.compile("\\|[^|]*\\||/\\*.*?\\*/|\"[^\"]*\"", Pattern.DOTALL);

  /** The parser (thread-safe: a new ANTLR parser is created per query). */
  private final ECLQueryBuilder parser = new ECLQueryBuilder(new ECLObjectFactory());

  /**
   * Translate an ECL expression to SPARQL.
   *
   * @param ecl the ECL expression
   * @return the SPARQL query
   * @throws org.snomed.langauges.ecl.ECLException if the expression is not valid ECL
   * @throws UnsupportedEclException if the expression uses an unsupported feature
   */
  public String translate(final String ecl) {
    final ExpressionConstraint constraint = parser.createQuery(ecl);
    if (TOP_BOTTOM.matcher(NON_SYNTAX.matcher(ecl).replaceAll(" ")).find()) {
      throw new UnsupportedEclException("Top/bottom of set (!!>, !!<) is not supported (dropped by the ECL parser)");
    }
    final SparqlGenerator generator = new SparqlGenerator();
    generator.constraint(constraint, RESULT_VAR, 1, false);
    return PREFIXES + "SELECT DISTINCT " + RESULT_VAR + " WHERE {\n" + generator.getOutput() + "}\n";
  }

}
