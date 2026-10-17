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
 * Information about the loaded store.
 *
 * @param version the SNOMED CT release version
 * @param concepts the number of concepts
 * @param triples the number of RDF triples
 */
@Schema(description = "The loaded SNOMED CT release and store size")
public record StoreStatus(
    @Schema(description = "The SNOMED CT release version", example = "20261001") String version,
    @Schema(description = "The number of active concepts", example = "387630") long concepts,
    @Schema(description = "The number of RDF triples (including index triples)", example = "29629322") long triples) {
}
