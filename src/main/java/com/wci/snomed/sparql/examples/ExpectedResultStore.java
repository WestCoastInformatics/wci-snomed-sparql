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
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads and writes expected results: one JSON file per example, {@code <dir>/<exampleId>.json}.
 */
public final class ExpectedResultStore {

  /** The json mapper. */
  private static final JsonMapper MAPPER =
      JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

  /**
   * Instantiates an empty {@link ExpectedResultStore}.
   */
  private ExpectedResultStore() {
    // n/a
  }

  /**
   * Write an expected result.
   *
   * @param dir the dir
   * @param result the result
   */
  public static void write(final Path dir, final ExpectedResult result) {
    try {
      Files.createDirectories(dir);
      MAPPER.writeValue(dir.resolve(result.id() + ".json").toFile(), result);
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Read all expected results in a dir.
   *
   * @param dir the dir
   * @return the expected results, sorted by id
   */
  public static List<ExpectedResult> readAll(final Path dir) {
    try (Stream<Path> files = Files.list(dir)) {
      return files.filter(p -> p.getFileName().toString().endsWith(".json")).sorted()
          .map(p -> MAPPER.readValue(p.toFile(), ExpectedResult.class)).toList();
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

}
