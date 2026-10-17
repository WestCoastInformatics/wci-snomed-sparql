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

import java.util.List;

/**
 * An ECL example from the ECL specification.
 *
 * @param id the id (section number and sequence, e.g. "6.2-05")
 * @param section the spec section (e.g. "6.2 Refinements")
 * @param heading the heading (and sub-heading) the example appears under
 * @param ecl the expression in brief syntax
 * @param eclLong the expression in long syntax, if the spec gives one
 * @param features the ECL features the expression uses (heuristic tags, for tracking support)
 * @param executable whether the example is an executable expression (false for templates)
 * @param note a curation note, if the example was corrected or needs explanation
 * @param source the spec page the example comes from
 */
public record EclExample(String id, String section, String heading, String ecl, String eclLong,
  List<String> features, boolean executable, String note, String source) {

}
