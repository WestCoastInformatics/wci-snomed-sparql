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

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A concept in a result page.
 *
 * @param code the SNOMED CT concept id
 * @param name the fully specified name (rdfs:label)
 */
@Schema(description = "A SNOMED CT concept")
public record ConceptItem(
    @Schema(description = "The SNOMED CT concept id", example = "73211009") String code,
    @Schema(description = "The fully specified name", example = "Diabetes mellitus (disorder)") String name) {
}
