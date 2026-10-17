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
package com.wci.snomed.sparql.store;

import com.wci.snomed.sparql.convert.InferredOwlConverter;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A store loaded from the mini RF2 release (src/test/resources/rf2), converted once per JVM.
 *
 * <pre>
 *   138875005 SNOMED CT Concept
 *     404684003 Clinical finding               (member of 999991005)
 *       362969004 Disorder of endocrine system (fully defined: finding site 113331007)
 *         999990008 Thyroid disorder           (member of 999991005)
 *   999993001 Old thyroid disorder (inactive, SAME AS 999990008)
 * </pre>
 */
public final class MiniStore {

  /** The mini release. */
  public static final Path RELEASE = Path.of("src/test/resources/rf2/SnomedCT_MiniRF2_20190131");

  /** The store. */
  private static SnomedStore store;

  /**
   * Instantiates an empty {@link MiniStore}.
   */
  private MiniStore() {
    // n/a
  }

  /**
   * Returns the store, converting and loading the mini release on first use.
   *
   * @return the store
   * @throws Exception the exception
   */
  public static synchronized SnomedStore get() throws Exception {
    if (store == null) {
      final Path dir = Files.createTempDirectory("mini-store");
      final Path owlFile = dir.resolve("mini.owl");
      new InferredOwlConverter().convert(RELEASE, owlFile);
      store = SnomedStore.load(owlFile);
      Files.delete(owlFile);
      Files.delete(dir);
    }
    return store;
  }

}
