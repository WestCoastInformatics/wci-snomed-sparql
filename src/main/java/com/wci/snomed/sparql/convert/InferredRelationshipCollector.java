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

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.snomed.otf.owltoolkit.domain.Relationship;
import org.snomed.otf.owltoolkit.normalform.RelationshipChangeProcessor;

/**
 * Captures the complete inferred (normal form) relationships the toolkit generates for each concept
 * after classification, while still tracking the change counts relative to the release's own inferred
 * relationships.
 */
public class InferredRelationshipCollector extends RelationshipChangeProcessor {

  /** The generated normal form relationships, by concept id. */
  private final Map<Long, List<Relationship>> inferred = new HashMap<>();

  /* see superclass */
  @Override
  public void apply(final long conceptId, final Collection<Relationship> existingRelationships,
    final Collection<Relationship> newRelationships) {
    inferred.put(conceptId, new ArrayList<>(newRelationships));
    super.apply(conceptId, existingRelationships, newRelationships);
  }

  /**
   * Returns the inferred relationships by concept id.
   *
   * @return the inferred relationships
   */
  public Map<Long, List<Relationship>> getInferred() {
    return inferred;
  }

}
