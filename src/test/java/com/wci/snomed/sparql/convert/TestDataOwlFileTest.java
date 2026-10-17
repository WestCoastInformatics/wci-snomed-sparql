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

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Checks the inferred OWL file used by the unit tests is present and is the expected version. Regenerate it
 * with "make test-data".
 */
public class TestDataOwlFileTest {

  /** The SNOMED version the unit tests are based on. */
  public static final String VERSION = "20261001";

  /** The test OWL file. */
  public static final Path OWL_FILE = Path.of("src/test/data/snomed-inferred-" + VERSION + ".owl");

  /** Number of lines to scan for the ontology header. */
  private static final int HEADER_LINES = 100;

  /**
   * The test OWL file exists and declares the expected version IRI.
   *
   * @throws Exception the exception
   */
  @Test
  public void testOwlFileVersion() throws Exception {
    assertTrue(Files.exists(OWL_FILE), OWL_FILE + " is missing, run: make test-data");
    boolean found = false;
    try (BufferedReader reader = Files.newBufferedReader(OWL_FILE, StandardCharsets.UTF_8)) {
      for (int i = 0; i < HEADER_LINES && !found; i++) {
        final String line = reader.readLine();
        found = line != null && line.equals("<http://snomed.info/sct/900000000000207008/version/" + VERSION + ">");
      }
    }
    assertTrue(found, "Expected version " + VERSION + " in " + OWL_FILE);
  }

}
