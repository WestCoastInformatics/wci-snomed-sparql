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

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Loads the catalog of ECL examples (classpath resource {@value #RESOURCE}), extracted from the ECL
 * specification's "Behaviour specification with examples" pages by tools/extract_ecl.py and tools/curate_ecl.py.
 */
public final class EclExampleCatalog {

  /** The classpath resource holding the catalog. */
  public static final String RESOURCE = "/ecl-examples.json";

  /**
   * Instantiates an empty {@link EclExampleCatalog}.
   */
  private EclExampleCatalog() {
    // n/a
  }

  /**
   * Load all examples.
   *
   * @return the examples
   */
  public static List<EclExample> load() {
    try (InputStream in = EclExampleCatalog.class.getResourceAsStream(RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException("Missing classpath resource " + RESOURCE);
      }
      return JsonMapper.builder().build().readValue(in, new TypeReference<List<EclExample>>() {
        // n/a
      });
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Load the executable examples.
   *
   * @return the executable examples
   */
  public static List<EclExample> loadExecutable() {
    return load().stream().filter(EclExample::executable).toList();
  }

}
