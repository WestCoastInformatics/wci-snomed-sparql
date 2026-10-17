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
package com.wci.snomed.sparql.rest;

import java.util.List;

/**
 * A page of concepts matching an ECL expression (or SPARQL query), in numeric code order.
 *
 * @param total the total number of matching concepts
 * @param limit the page size
 * @param offset the offset of the first item
 * @param items the concepts on this page
 */
public record ResultPage(long total, int limit, long offset, List<ConceptItem> items) {
}
