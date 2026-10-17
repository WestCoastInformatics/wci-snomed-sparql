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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.regex.Pattern;
import org.snomed.langauges.ecl.domain.ConceptReference;
import org.snomed.langauges.ecl.domain.Pair;
import org.snomed.langauges.ecl.domain.expressionconstraint.CompoundExpressionConstraint;
import org.snomed.langauges.ecl.domain.expressionconstraint.DottedExpressionConstraint;
import org.snomed.langauges.ecl.domain.expressionconstraint.ExpressionConstraint;
import org.snomed.langauges.ecl.domain.expressionconstraint.RefinedExpressionConstraint;
import org.snomed.langauges.ecl.domain.expressionconstraint.SubExpressionConstraint;
import org.snomed.langauges.ecl.domain.filter.ActiveFilter;
import org.snomed.langauges.ecl.domain.filter.ConceptFilterConstraint;
import org.snomed.langauges.ecl.domain.filter.FieldFilter;
import org.snomed.langauges.ecl.domain.filter.HistoryProfile;
import org.snomed.langauges.ecl.domain.filter.HistorySupplement;
import org.snomed.langauges.ecl.domain.filter.MemberFieldFilter;
import org.snomed.langauges.ecl.domain.filter.MemberFilterConstraint;
import org.snomed.langauges.ecl.domain.filter.TypedSearchTerm;
import org.snomed.langauges.ecl.domain.refinement.EclAttribute;
import org.snomed.langauges.ecl.domain.refinement.EclAttributeGroup;
import org.snomed.langauges.ecl.domain.refinement.EclAttributeSet;
import org.snomed.langauges.ecl.domain.refinement.EclRefinement;
import org.snomed.langauges.ecl.domain.refinement.Operator;
import org.snomed.langauges.ecl.domain.refinement.SubAttributeSet;
import org.snomed.langauges.ecl.domain.refinement.SubRefinement;

/**
 * Generates the SPARQL group graph pattern for a parsed ECL expression constraint (see {@link EclTranslator}).
 *
 * <p>
 * Each constraint is generated in one of two modes:
 * </p>
 * <ul>
 * <li><b>set</b>: the pattern enumerates the matching concepts (variable unbound). Parts whose results feed further
 * navigation are wrapped in distinct subqueries; complex AND parts are evaluated independently.</li>
 * <li><b>bound</b>: the variable is already bound (inside a FILTER EXISTS, or a refinement value) and the pattern
 * checks it, navigating from the bound concept so each check is cheap.</li>
 * </ul>
 *
 * <p>
 * Refinements are FILTER expressions on the bound focus concept: each attribute is an (NOT) EXISTS over the
 * attribute restrictions of the concept's class axiom ({@link EclTranslator#ATTRIBUTE_PATH}), or of one role group
 * for attributes inside {@code { }}. Cardinality is checked with pairwise distinct restriction (or group) nodes.
 * </p>
 */
final class SparqlGenerator {

  /** The ECL "defined" definition status. */
  private static final String DEFINED = "900000000000073002";

  /** The ECL "primitive" definition status. */
  private static final String PRIMITIVE = "900000000000074008";

  /** The historical association reference set (parent of all historical association refsets). */
  private static final String HISTORICAL_ASSOCIATION = "sct:900000000000522004";

  /** HISTORY-MIN: SAME AS. */
  private static final List<String> HISTORY_MIN = List.of("sct:900000000000527005");

  /** HISTORY-MOD: SAME AS, REPLACED BY, WAS A, PARTIALLY EQUIVALENT TO. */
  private static final List<String> HISTORY_MOD = List.of("sct:900000000000527005", "sct:900000000000526001",
      "sct:900000000000528000", "sct:1186924009");

  /** Path from a role group's filler to its attribute restrictions. */
  private static final String GROUP_MEMBER_PATH = "(owl:intersectionOf/rdf:rest*/rdf:first)?";

  /** Largest cardinality checked with pairwise distinct patterns (beyond it, an aggregate is used). */
  private static final int MAX_PAIRWISE = 5;

  /** A numeric literal. */
  private static final Pattern NUMBER = Pattern.compile("[+-]?\\d+(\\.\\d+)?");

  /** The output. */
  private final StringBuilder out = new StringBuilder();

  /** The variable counter. */
  private int vars;

  /**
   * Returns the output.
   *
   * @return the output
   */
  String getOutput() {
    return out.toString();
  }

  /**
   * Returns a fresh variable.
   *
   * @param prefix the variable name prefix
   * @return the variable
   */
  private String var(final String prefix) {
    return "?" + prefix + (++vars);
  }

  /**
   * Append an indented line.
   *
   * @param indent the indent level
   * @param line the line
   */
  private void line(final int indent, final String line) {
    out.append("  ".repeat(indent)).append(line).append('\n');
  }

  /**
   * Append the pattern binding (or, if bound, checking) a variable for the concepts matching a constraint.
   *
   * @param constraint the constraint
   * @param v the variable
   * @param indent the indent level
   * @param bound whether the variable is already bound
   */
  void constraint(final ExpressionConstraint constraint, final String v, final int indent, final boolean bound) {
    switch (constraint) {
      case SubExpressionConstraint sub -> sub(sub, v, indent, bound);
      case CompoundExpressionConstraint compound -> compound(compound, v, indent, bound);
      case DottedExpressionConstraint dotted -> dotted(dotted, v, indent, bound);
      case RefinedExpressionConstraint refined -> refined(refined, v, indent, bound);
      default -> throw new UnsupportedEclException("Unsupported expression: " + constraint);
    }
  }

  /**
   * Append a distinct subquery binding a variable (set mode).
   *
   * @param constraint the constraint
   * @param v the variable
   * @param indent the indent level
   */
  private void distinct(final ExpressionConstraint constraint, final String v, final int indent) {
    line(indent, "{ SELECT DISTINCT " + v + " WHERE {");
    constraint(constraint, v, indent + 1, false);
    line(indent, "} }");
  }

  /**
   * Append a subquery binding a variable that Jena evaluates once, independently of the enclosing pattern, rather
   * than once per row with the variable substituted (the result is projected under a new name, which prevents the
   * substitution).
   *
   * @param constraint the constraint
   * @param v the variable
   * @param indent the indent level
   */
  private void independent(final ExpressionConstraint constraint, final String v, final int indent) {
    final String c = var("c");
    line(indent, "{ SELECT (" + c + " AS " + v + ") WHERE {");
    distinct(constraint, c, indent + 1);
    line(indent, "} }");
  }

  /**
   * Indicates whether a constraint is a single triple pattern or path (a concept or the wildcard, with an optional
   * operator), which is cheap to evaluate with its variable bound.
   *
   * @param constraint the constraint
   * @return true, if simple
   */
  private static boolean isSimple(final ExpressionConstraint constraint) {
    return constraint instanceof SubExpressionConstraint sub && sub.getNestedExpressionConstraint() == null
        && isPlain(sub);
  }

  /**
   * Indicates whether a sub expression constraint has no filters or supplements.
   *
   * @param sub the sub expression constraint
   * @return true, if plain
   */
  private static boolean isPlain(final SubExpressionConstraint sub) {
    return sub.getConceptFilterConstraints() == null && sub.getDescriptionFilterConstraints() == null
        && sub.getMemberFilterConstraints() == null && sub.getHistorySupplement() == null;
  }

  /**
   * Indicates whether a constraint is the plain wildcard (any concept).
   *
   * @param constraint the constraint
   * @return true, if the wildcard
   */
  private static boolean isAny(final ExpressionConstraint constraint) {
    return constraint instanceof SubExpressionConstraint sub && sub.isWildcard() && sub.getOperator() == null
        && isPlain(sub);
  }

  /**
   * Returns the constant concept of a plain concept reference (no operator or filters), or null.
   *
   * @param constraint the constraint
   * @return the IRI (sct:id), or null
   */
  private static String constant(final ExpressionConstraint constraint) {
    return constraint instanceof SubExpressionConstraint sub && sub.getOperator() == null
        && sub.getConceptId() != null && isPlain(sub) ? "sct:" + sub.getConceptId() : null;
  }

  //
  // Sub expression constraints
  //

  /**
   * Sub expression constraint: [operator] focus [filters] [history supplement].
   *
   * @param sub the sub expression constraint
   * @param v the variable
   * @param indent the indent level
   * @param bound whether the variable is bound
   */
  private void sub(final SubExpressionConstraint sub, final String v, final int indent, final boolean bound) {
    checkSupported(sub);
    if (sub.getHistorySupplement() != null) {
      history(sub, v, indent, bound);
      return;
    }
    final String focus = focus(sub, v, indent, bound);
    if (sub.getConceptFilterConstraints() != null) {
      for (final ConceptFilterConstraint filter : sub.getConceptFilterConstraints()) {
        conceptFilter(filter, v, indent);
      }
    }
    if (sub.getMemberFilterConstraints() != null) {
      for (final MemberFilterConstraint filter : sub.getMemberFilterConstraints()) {
        memberFilter(filter, focus, v, indent);
      }
    }
  }

  /**
   * Append the operator and focus concept pattern.
   *
   * @param sub the sub expression constraint
   * @param v the variable
   * @param indent the indent level
   * @param bound whether the variable is bound
   * @return the focus term (an IRI or variable), or null for the wildcard or a nested expression without operator
   */
  private String focus(final SubExpressionConstraint sub, final String v, final int indent, final boolean bound) {
    final Operator op = sub.getOperator();
    if (sub.isWildcard()) {
      return wildcard(op, v, indent);
    }
    if (sub.getConceptId() == null && op == null) {
      constraint(sub.getNestedExpressionConstraint(), v, indent, bound);
      return null;
    }
    final String focus;
    if (sub.getConceptId() != null) {
      focus = "sct:" + sub.getConceptId();
      if (op == null) {
        if (bound) {
          line(indent, "FILTER(" + v + " = " + focus + ")");
        } else {
          line(indent, "VALUES " + v + " { " + focus + " }");
        }
        line(indent, v + " a owl:Class .");
        return focus;
      }
      if (includesSelf(op)) {
        // zero-length paths match the focus even if it is not a concept
        line(indent, focus + " a owl:Class .");
      }
      operator(op, focus, v, indent);
    } else {
      focus = var("x");
      if (bound) {
        // navigate from the bound concept, then check the nested expression
        operator(op, focus, v, indent);
        constraint(sub.getNestedExpressionConstraint(), focus, indent, true);
      } else {
        distinct(sub.getNestedExpressionConstraint(), focus, indent);
        operator(op, focus, v, indent);
      }
    }
    return focus;
  }

  /**
   * Append the pattern for an operator applied to a focus term.
   *
   * @param op the operator
   * @param focus the focus term
   * @param v the variable
   * @param indent the indent level
   */
  private void operator(final Operator op, final String focus, final String v, final int indent) {
    switch (op) {
      case descendantof -> line(indent, v + " rdfs:subClassOf+ " + focus + " .");
      case descendantorselfof -> line(indent, v + " rdfs:subClassOf* " + focus + " .");
      case childof -> line(indent, v + " rdfs:subClassOf " + focus + " .");
      case childorselfof -> line(indent, v + " rdfs:subClassOf? " + focus + " .");
      case ancestorof -> ancestors(focus, "rdfs:subClassOf+", v, indent);
      case ancestororselfof -> ancestors(focus, "rdfs:subClassOf*", v, indent);
      case parentof -> ancestors(focus, "rdfs:subClassOf", v, indent);
      case parentorselfof -> ancestors(focus, "rdfs:subClassOf?", v, indent);
      case memberOf -> line(indent, v + " sct:isMemberOf " + focus + " .");
      default -> throw new UnsupportedEclException("Unsupported operator: " + op.getText());
    }
  }

  /**
   * Ancestors of the focus (class expression blank nodes are excluded).
   *
   * @param focus the focus
   * @param path the path
   * @param v the variable
   * @param indent the indent level
   */
  private void ancestors(final String focus, final String path, final String v, final int indent) {
    line(indent, focus + " " + path + " " + v + " .");
    line(indent, "FILTER(isIRI(" + v + "))");
  }

  /**
   * Operator applied to the wildcard: any concept, any concept with a parent (all but the root), any concept with
   * a child, or any refset member.
   *
   * @param op the operator (may be null)
   * @param v the variable
   * @param indent the indent level
   * @return the focus term (a variable for the refset of "^ *", else null)
   */
  private String wildcard(final Operator op, final String v, final int indent) {
    if (op == null || includesSelf(op)) {
      line(indent, v + " a owl:Class .");
      line(indent, "FILTER(isIRI(" + v + "))");
      return null;
    }
    final String other = var("x");
    switch (op) {
      case descendantof, childof -> {
        line(indent, v + " rdfs:subClassOf " + other + " .");
        line(indent, "FILTER(isIRI(" + other + "))");
      }
      case ancestorof, parentof -> {
        line(indent, other + " rdfs:subClassOf " + v + " .");
        line(indent, "FILTER(isIRI(" + v + "))");
      }
      case memberOf -> {
        line(indent, v + " sct:isMemberOf " + other + " .");
        return other;
      }
      default -> throw new UnsupportedEclException("Unsupported operator: " + op.getText());
    }
    return null;
  }

  /**
   * Indicates whether a hierarchy operator includes the focus concept itself.
   *
   * @param op the operator
   * @return true, if it includes self
   */
  private static boolean includesSelf(final Operator op) {
    return op == Operator.descendantorselfof || op == Operator.ancestororselfof || op == Operator.childorselfof
        || op == Operator.parentorselfof;
  }

  /**
   * Reject features that are not supported.
   *
   * @param sub the sub expression constraint
   */
  private static void checkSupported(final SubExpressionConstraint sub) {
    if (sub.getMemberFieldsToReturn() != null || sub.isReturnAllMemberFields()) {
      throw new UnsupportedEclException("Member field selection is not supported (results are concepts)");
    }
    if (sub.getDescriptionFilterConstraints() != null) {
      throw new UnsupportedEclException("Description filters are not yet supported");
    }
  }

  //
  // Filters
  //

  /**
   * Concept filter: definition status (from the class axiom: EquivalentClasses = defined) and active (inactive
   * concepts are not classes). Module and effective time are not in the OWL file.
   *
   * @param filter the filter
   * @param v the (bound) variable
   * @param indent the indent level
   */
  private void conceptFilter(final ConceptFilterConstraint filter, final String v, final int indent) {
    if (filter.getModuleFilters() != null || filter.getEffectiveTimeFilters() != null) {
      throw new UnsupportedEclException("Concept moduleId and effectiveTime filters are not supported "
          + "(not in the OWL file)");
    }
    if (filter.getDefinitionStatusFilters() != null) {
      for (final FieldFilter field : filter.getDefinitionStatusFilters()) {
        final List<String> ids;
        if (field.getSubExpressionConstraint() == null) {
          ids = field.getConceptReferences().stream().map(ConceptReference::getConceptId).toList();
        } else if (constant(field.getSubExpressionConstraint()) != null) {
          ids = List.of(((SubExpressionConstraint) field.getSubExpressionConstraint()).getConceptId());
        } else {
          throw new UnsupportedEclException("definitionStatusId filters with an expression are not supported");
        }
        // the set of statuses the concept may have
        final boolean defined = ids.contains(DEFINED) == field.isEquals();
        final boolean primitive = ids.contains(PRIMITIVE) == field.isEquals();
        if (defined && primitive) {
          continue;
        }
        if (!defined && !primitive) {
          line(indent, "FILTER(false)");
        } else {
          line(indent, "FILTER(" + (defined ? "" : "NOT ") + "EXISTS { " + v + " owl:equivalentClass " + var("e")
              + " })");
        }
      }
    }
    if (filter.getActiveFilters() != null) {
      for (final ActiveFilter active : filter.getActiveFilters()) {
        line(indent, active.isActive() ? "FILTER EXISTS { " + v + " a owl:Class }"
            : "FILTER EXISTS { " + v + " owl:deprecated true }");
      }
    }
  }

  /**
   * Member filter. Only referencedComponentId (the member concept itself) and targetComponentId of association
   * refsets (stored as annotations: {@code member refset target}) can be evaluated; other member fields are not in
   * the OWL file. Members are always active.
   *
   * @param filter the filter
   * @param refset the refset term (IRI or variable)
   * @param v the (bound) member variable
   * @param indent the indent level
   */
  private void memberFilter(final MemberFilterConstraint filter, final String refset, final String v,
    final int indent) {
    if (refset == null) {
      throw new UnsupportedEclException("Member filters apply to member of (^) expressions");
    }
    if (filter.getModuleFilters() != null || filter.getEffectiveTimeFilters() != null) {
      throw new UnsupportedEclException("Member moduleId and effectiveTime filters are not supported "
          + "(not in the OWL file)");
    }
    if (filter.getActiveFilters() != null) {
      for (final ActiveFilter active : filter.getActiveFilters()) {
        if (!active.isActive()) {
          throw new UnsupportedEclException("Inactive refset members are not in the OWL file");
        }
      }
    }
    if (filter.getMemberFieldFilters() == null) {
      return;
    }
    for (final MemberFieldFilter field : filter.getMemberFieldFilters()) {
      final String name = field.getFieldName();
      if (field.getSubExpressionConstraint() == null
          || !"referencedComponentId".equals(name) && !"targetComponentId".equals(name)) {
        throw new UnsupportedEclException("Member filter on " + name + " is not supported "
            + "(refset member fields are not in the OWL file)");
      }
      final boolean equals = "=".equals(field.getExpressionComparisonOperator());
      line(indent, "FILTER EXISTS {");
      String target = v;
      if ("targetComponentId".equals(name)) {
        target = var("t");
        line(indent + 1, v + " " + refset + " " + target + " .");
      }
      if (equals) {
        constraint(field.getSubExpressionConstraint(), target, indent + 1, true);
      } else {
        line(indent + 1, "FILTER NOT EXISTS {");
        constraint(field.getSubExpressionConstraint(), target, indent + 2, true);
        line(indent + 1, "}");
      }
      line(indent, "}");
    }
  }

  //
  // History supplements
  //

  /**
   * History supplement: the matching concepts, plus the (inactive) concepts with a historical association to
   * them, via the association refsets of the profile (MIN, MOD, MAX) or matching an expression.
   *
   * @param sub the sub expression constraint
   * @param v the variable
   * @param indent the indent level
   * @param bound whether the variable is bound
   */
  private void history(final SubExpressionConstraint sub, final String v, final int indent, final boolean bound) {
    final SubExpressionConstraint withoutHistory = withoutHistory(sub);
    final String x = var("x");
    final String assoc = var("h");
    line(indent, "{");
    sub(withoutHistory, v, indent + 1, bound);
    line(indent, "} UNION {");
    if (bound) {
      line(indent + 1, v + " " + assoc + " " + x + " .");
      historyRefsets(sub.getHistorySupplement(), assoc, indent + 1, true);
      sub(withoutHistory, x, indent + 1, true);
    } else {
      line(indent + 1, "{ SELECT DISTINCT " + x + " WHERE {");
      sub(withoutHistory, x, indent + 2, false);
      line(indent + 1, "} }");
      historyRefsets(sub.getHistorySupplement(), assoc, indent + 1, false);
      line(indent + 1, v + " " + assoc + " " + x + " .");
    }
    line(indent, "}");
  }

  /**
   * Append the pattern binding (or checking) the historical association refsets of a history supplement.
   *
   * @param supplement the supplement
   * @param assoc the refset variable
   * @param indent the indent level
   * @param bound whether the variable is bound
   */
  private void historyRefsets(final HistorySupplement supplement, final String assoc, final int indent,
    final boolean bound) {
    final HistoryProfile profile = supplement.getHistoryProfile();
    if (profile == HistoryProfile.MIN || profile == HistoryProfile.MOD) {
      final List<String> refsets = profile == HistoryProfile.MIN ? HISTORY_MIN : HISTORY_MOD;
      line(indent, bound ? "FILTER(" + assoc + " IN (" + String.join(", ", refsets) + "))"
          : "VALUES " + assoc + " { " + String.join(" ", refsets) + " }");
      return;
    }
    // MAX, no profile, or a subset expression: historical association refsets (matching the expression)
    line(indent, assoc + " rdfs:subClassOf+ " + HISTORICAL_ASSOCIATION + " .");
    if (supplement.getHistorySubset() != null && !isAny(supplement.getHistorySubset())) {
      constraint(supplement.getHistorySubset(), assoc, indent, true);
    }
  }

  /**
   * Returns a copy of a sub expression constraint without its history supplement.
   *
   * @param sub the sub expression constraint
   * @return the copy
   */
  private static SubExpressionConstraint withoutHistory(final SubExpressionConstraint sub) {
    final SubExpressionConstraint copy = new SubExpressionConstraint(sub.getOperator());
    copy.setConceptId(sub.getConceptId());
    copy.setTerm(sub.getTerm());
    copy.setWildcard(sub.isWildcard());
    copy.setNestedExpressionConstraint(sub.getNestedExpressionConstraint());
    if (sub.getConceptFilterConstraints() != null) {
      sub.getConceptFilterConstraints().forEach(copy::addConceptFilterConstraint);
    }
    copy.setDescriptionFilterConstraints(sub.getDescriptionFilterConstraints());
    copy.setMemberFilterConstraints(sub.getMemberFilterConstraints());
    return copy;
  }

  //
  // Compound and dotted expression constraints
  //

  /**
   * Compound expression constraint: AND (join), OR (union), MINUS.
   *
   * @param compound the compound expression constraint
   * @param v the variable
   * @param indent the indent level
   * @param bound whether the variable is bound
   */
  private void compound(final CompoundExpressionConstraint compound, final String v, final int indent,
    final boolean bound) {
    if (compound.getConjunctionExpressionConstraints() != null
        && !compound.getConjunctionExpressionConstraints().isEmpty()) {
      // Set mode: complex parts first; later simple parts are then cheap checks of each row (Jena substitutes
      // bound variables), and later complex parts are evaluated once, independently, then joined
      final List<SubExpressionConstraint> parts = new ArrayList<>(compound.getConjunctionExpressionConstraints());
      if (!bound) {
        parts.sort(Comparator.comparing(SparqlGenerator::isSimple));
      }
      for (int i = 0; i < parts.size(); i++) {
        if (!bound && i > 0 && !isSimple(parts.get(i))) {
          independent(parts.get(i), v, indent);
        } else {
          line(indent, "{");
          constraint(parts.get(i), v, indent + 1, bound);
          line(indent, "}");
        }
      }
    } else if (compound.getDisjunctionExpressionConstraints() != null
        && !compound.getDisjunctionExpressionConstraints().isEmpty()) {
      final List<SubExpressionConstraint> parts = compound.getDisjunctionExpressionConstraints();
      for (int i = 0; i < parts.size(); i++) {
        line(indent, i == 0 ? "{" : "} UNION {");
        constraint(parts.get(i), v, indent + 1, bound);
      }
      line(indent, "}");
    } else {
      final Pair<SubExpressionConstraint> exclusion = compound.getExclusionExpressionConstraints();
      line(indent, "{");
      constraint(exclusion.getFirst(), v, indent + 1, bound);
      line(indent, "}");
      if (bound) {
        // with the variable bound (substituted), MINUS would share no variable and remove nothing
        line(indent, "FILTER NOT EXISTS {");
        constraint(exclusion.getSecond(), v, indent + 1, true);
      } else {
        // MINUS evaluates the right hand side once (FILTER NOT EXISTS would re-evaluate it for each row)
        line(indent, "MINUS {");
        constraint(exclusion.getSecond(), v, indent + 1, false);
      }
      line(indent, "}");
    }
  }

  /**
   * Dotted expression constraint: the values of the attributes of the matching concepts.
   *
   * @param dotted the dotted expression constraint
   * @param v the variable
   * @param indent the indent level
   * @param bound whether the variable is bound
   */
  private void dotted(final DottedExpressionConstraint dotted, final String v, final int indent,
    final boolean bound) {
    final List<SubExpressionConstraint> attributes = dotted.getDottedAttributes();
    if (bound) {
      dottedBound(dotted.getSubExpressionConstraint(), attributes, attributes.size(), v, indent);
    } else {
      dottedStep(dotted.getSubExpressionConstraint(), attributes, attributes.size(), v, indent);
    }
  }

  /**
   * Set mode: each step is a distinct subquery over the previous step's values.
   *
   * @param source the source expression constraint
   * @param attributes the dotted attributes
   * @param n the number of attributes to apply
   * @param v the variable
   * @param indent the indent level
   */
  private void dottedStep(final SubExpressionConstraint source, final List<SubExpressionConstraint> attributes,
    final int n, final String v, final int indent) {
    final String s = var("s");
    line(indent, "{ SELECT DISTINCT " + v + " WHERE {");
    if (n == 1) {
      distinct(source, s, indent + 1);
    } else {
      dottedStep(source, attributes, n - 1, s, indent + 1);
    }
    final String r = var("r");
    line(indent + 1, s + " " + EclTranslator.ATTRIBUTE_PATH + " " + r + " .");
    restriction(r, attributes.get(n - 1), indent + 1);
    line(indent + 1, r + " owl:someValuesFrom " + v + " .");
    line(indent + 1, "FILTER(isIRI(" + v + "))");
    line(indent, "} }");
  }

  /**
   * Bound mode: from the bound value back to a source concept with the attribute, then check the source.
   *
   * @param source the source expression constraint
   * @param attributes the dotted attributes
   * @param n the number of attributes to apply
   * @param v the (bound) variable
   * @param indent the indent level
   */
  private void dottedBound(final SubExpressionConstraint source, final List<SubExpressionConstraint> attributes,
    final int n, final String v, final int indent) {
    final String s = var("s");
    final String r = var("r");
    line(indent, r + " owl:someValuesFrom " + v + " .");
    restriction(r, attributes.get(n - 1), indent);
    line(indent, s + " " + EclTranslator.ATTRIBUTE_PATH + " " + r + " .");
    if (n == 1) {
      constraint(source, s, indent, true);
    } else {
      dottedBound(source, attributes, n - 1, s, indent);
    }
  }

  /**
   * Append the pattern restricting an attribute restriction node to an attribute name constraint (excluding the
   * role group).
   *
   * @param r the restriction variable
   * @param attribute the attribute name constraint
   * @param indent the indent level
   */
  private void restriction(final String r, final ExpressionConstraint attribute, final int indent) {
    final String constant = constant(attribute);
    if (constant != null) {
      line(indent, r + " owl:onProperty " + constant + " .");
      return;
    }
    final String a = var("a");
    line(indent, r + " owl:onProperty " + a + " .");
    line(indent, "FILTER(" + a + " != " + EclTranslator.ROLE_GROUP + ")");
    if (!isAny(attribute)) {
      constraint(attribute, a, indent, true);
    }
  }

  //
  // Refinements
  //

  /**
   * Refined expression constraint: the focus, filtered by the refinement condition on each (bound) concept.
   *
   * @param refined the refined expression constraint
   * @param v the variable
   * @param indent the indent level
   * @param bound whether the variable is bound
   */
  private void refined(final RefinedExpressionConstraint refined, final String v, final int indent,
    final boolean bound) {
    constraint(refined.getSubexpressionConstraint(), v, indent, bound);
    line(indent, "FILTER(");
    refinement(refined.getEclRefinement(), v, indent + 1);
    line(indent, ")");
  }

  /**
   * Append a boolean expression joining the given parts.
   *
   * @param <T> the part type
   * @param first the first part
   * @param conjunction the conjunction parts (or null)
   * @param disjunction the disjunction parts (or null)
   * @param emitter the part emitter
   * @param indent the indent level
   */
  private <T> void bool(final T first, final List<T> conjunction, final List<T> disjunction,
    final BiConsumer<T, Integer> emitter, final int indent) {
    final List<T> parts = new ArrayList<>();
    parts.add(first);
    final String op;
    // the parser may give an empty conjunction list alongside a disjunction
    if (conjunction != null && !conjunction.isEmpty()) {
      parts.addAll(conjunction);
      op = "&&";
    } else if (disjunction != null && !disjunction.isEmpty()) {
      parts.addAll(disjunction);
      op = "||";
    } else {
      op = null;
    }
    if (parts.size() == 1) {
      emitter.accept(first, indent);
      return;
    }
    line(indent, "(");
    for (int i = 0; i < parts.size(); i++) {
      if (i > 0) {
        line(indent + 1, op);
      }
      emitter.accept(parts.get(i), indent + 1);
    }
    line(indent, ")");
  }

  /**
   * Refinement condition: sub refinements joined by AND / OR.
   *
   * @param refinement the refinement
   * @param v the (bound) concept variable
   * @param indent the indent level
   */
  private void refinement(final EclRefinement refinement, final String v, final int indent) {
    bool(refinement.getSubRefinement(), refinement.getConjunctionSubRefinements(),
        refinement.getDisjunctionSubRefinements(), (sub, i) -> subRefinement(sub, v, i), indent);
  }

  /**
   * Sub refinement: an attribute set, an attribute group, or a nested refinement.
   *
   * @param sub the sub refinement
   * @param v the (bound) concept variable
   * @param indent the indent level
   */
  private void subRefinement(final SubRefinement sub, final String v, final int indent) {
    if (sub.getEclAttributeSet() != null) {
      attributeSet(sub.getEclAttributeSet(), v, false, indent);
    } else if (sub.getEclAttributeGroup() != null) {
      attributeGroup(sub.getEclAttributeGroup(), v, indent);
    } else {
      refinement(sub.getEclRefinement(), v, indent);
    }
  }

  /**
   * Attribute set: attributes (or nested sets) joined by AND / OR.
   *
   * @param set the attribute set
   * @param subject the (bound) concept or group variable
   * @param inGroup whether the subject is a role group
   * @param indent the indent level
   */
  private void attributeSet(final EclAttributeSet set, final String subject, final boolean inGroup,
    final int indent) {
    bool(set.getSubAttributeSet(), set.getConjunctionAttributeSet(), set.getDisjunctionAttributeSet(),
        (sub, i) -> subAttributeSet(sub, subject, inGroup, i), indent);
  }

  /**
   * Sub attribute set: an attribute or a nested attribute set.
   *
   * @param sub the sub attribute set
   * @param subject the (bound) concept or group variable
   * @param inGroup whether the subject is a role group
   * @param indent the indent level
   */
  private void subAttributeSet(final SubAttributeSet sub, final String subject, final boolean inGroup,
    final int indent) {
    if (sub.getAttribute() != null) {
      attribute(sub.getAttribute(), subject, inGroup, indent);
    } else {
      attributeSet(sub.getAttributeSet(), subject, inGroup, indent);
    }
  }

  /**
   * Attribute group: the number of the concept's role groups that satisfy the attribute set is within the
   * cardinality (default [1..*]).
   *
   * @param group the group
   * @param v the (bound) concept variable
   * @param indent the indent level
   */
  private void attributeGroup(final EclAttributeGroup group, final String v, final int indent) {
    final int min = group.getCardinalityMin() == null ? 1 : group.getCardinalityMin();
    final Integer max = group.getCardinalityMax();
    cardinality(min, max, indent, (g, i) -> {
      line(i, v + " " + EclTranslator.GROUP_PATH + " " + g + " .");
      line(i, "FILTER(");
      attributeSet(group.getAttributeSet(), g, true, i + 1);
      line(i, ")");
    }, "g");
  }

  /**
   * Attribute: the number of matching relationships (attribute restrictions) of the subject is within the
   * cardinality (default [1..*]).
   *
   * @param attribute the attribute
   * @param subject the (bound) concept or group variable
   * @param inGroup whether the subject is a role group
   * @param indent the indent level
   */
  private void attribute(final EclAttribute attribute, final String subject, final boolean inGroup,
    final int indent) {
    final int min = attribute.getCardinalityMin() == null ? 0 : attribute.getCardinalityMin();
    final Integer max = attribute.getCardinalityMax();
    cardinality(min, max, indent, (r, i) -> relationship(attribute, subject, inGroup, r, i), "r");
  }

  /**
   * Append a cardinality condition on the number of distinct nodes matching a pattern.
   *
   * @param min the minimum
   * @param max the maximum (null for unbounded)
   * @param indent the indent level
   * @param pattern emits the pattern binding the given node variable
   * @param prefix the node variable prefix
   */
  private void cardinality(final int min, final Integer max, final int indent,
    final BiConsumer<String, Integer> pattern, final String prefix) {
    if (max != null && max < min) {
      line(indent, "false");
      return;
    }
    if (min == 0 && max == null) {
      line(indent, "true");
      return;
    }
    if (Math.max(min, max == null ? 0 : max + 1) > MAX_PAIRWISE) {
      throw new UnsupportedEclException("Cardinality above " + MAX_PAIRWISE + " is not supported");
    }
    final List<String> parts = new ArrayList<>();
    if (min > 0) {
      parts.add("EXISTS");
    }
    if (max != null) {
      parts.add("NOT EXISTS");
    }
    for (int p = 0; p < parts.size(); p++) {
      // at least min distinct nodes, and not max + 1 distinct nodes
      final int n = "EXISTS".equals(parts.get(p)) ? min : max + 1;
      if (p > 0) {
        line(indent, "&&");
      }
      line(indent, parts.get(p) + " {");
      final List<String> nodes = new ArrayList<>();
      for (int k = 0; k < n; k++) {
        final String node = var(prefix);
        pattern.accept(node, indent + 1);
        for (final String other : nodes) {
          line(indent + 1, "FILTER(" + node + " != " + other + ")");
        }
        nodes.add(node);
      }
      line(indent, "}");
    }
  }

  /**
   * Append the pattern binding a relationship (attribute restriction) node matching an attribute.
   *
   * @param attribute the attribute
   * @param subject the (bound) concept or group variable
   * @param inGroup whether the subject is a role group
   * @param r the restriction variable
   * @param indent the indent level
   */
  private void relationship(final EclAttribute attribute, final String subject, final boolean inGroup,
    final String r, final int indent) {
    if (attribute.isReverse()) {
      // the subject is the value: a source concept (in the value set) has the attribute pointing to it
      if (attribute.getExpressionComparisonOperator() == null) {
        throw new UnsupportedEclException("Reverse attributes require a concept value");
      }
      final String source = var("s");
      line(indent, r + " owl:someValuesFrom " + subject + " .");
      restriction(r, attribute.getAttributeName(), indent);
      line(indent, source + " " + EclTranslator.ATTRIBUTE_PATH + " " + r + " .");
      value(attribute.getValue(), "=".equals(attribute.getExpressionComparisonOperator()), source, indent);
      return;
    }
    line(indent, subject + " " + (inGroup ? GROUP_MEMBER_PATH : EclTranslator.ATTRIBUTE_PATH) + " " + r + " .");
    restriction(r, attribute.getAttributeName(), indent);
    final String val = var("val");
    if (attribute.getExpressionComparisonOperator() != null) {
      line(indent, r + " owl:someValuesFrom " + val + " .");
      value(attribute.getValue(), "=".equals(attribute.getExpressionComparisonOperator()), val, indent);
    } else if (attribute.getNumericComparisonOperator() != null) {
      if (!NUMBER.matcher(attribute.getNumericValue()).matches()) {
        throw new UnsupportedEclException("Invalid numeric value: " + attribute.getNumericValue());
      }
      line(indent, r + " owl:hasValue " + val + " .");
      line(indent, "FILTER(isNumeric(" + val + ") && " + val + " " + attribute.getNumericComparisonOperator() + " "
          + attribute.getNumericValue() + ")");
    } else if (attribute.getStringComparisonOperator() != null) {
      line(indent, r + " owl:hasValue " + val + " .");
      final List<String> terms = new ArrayList<>();
      for (final TypedSearchTerm term : attribute.getStringValues()) {
        terms.add("str(" + val + ") = " + literal(term.getTerm()));
      }
      final String match = "(" + String.join(" || ", terms) + ")";
      line(indent, "FILTER(" + ("=".equals(attribute.getStringComparisonOperator()) ? match : "!" + match) + ")");
    } else if (attribute.getBooleanComparisonOperator() != null) {
      line(indent, r + " owl:hasValue " + val + " .");
      line(indent, "FILTER(" + val + " " + attribute.getBooleanComparisonOperator() + " "
          + attribute.getBooleanValue() + ")");
    } else {
      throw new UnsupportedEclException("Unsupported attribute: " + attribute);
    }
  }

  /**
   * Append the check that a (bound) value is, or is not, in a value set.
   *
   * @param value the value constraint
   * @param equals true for =, false for !=
   * @param val the (bound) value variable
   * @param indent the indent level
   */
  private void value(final ExpressionConstraint value, final boolean equals, final String val, final int indent) {
    if (isAny(value)) {
      if (!equals) {
        line(indent, "FILTER(false)");
      }
      return;
    }
    if (equals) {
      constraint(value, val, indent, true);
    } else {
      line(indent, "FILTER NOT EXISTS {");
      constraint(value, val, indent + 1, true);
      line(indent, "}");
    }
  }

  /**
   * Returns a SPARQL string literal.
   *
   * @param s the string
   * @return the literal
   */
  private static String literal(final String s) {
    return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\"";
  }

}
