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

import com.wci.snomed.sparql.convert.TestDataOwlFileTest;

/**
 * The store for the full test OWL file (src/test/data), loaded once per JVM (about 30s and 4 GB).
 */
public final class TestDataStore {

  /** The store. */
  private static SnomedStore store;

  /**
   * Instantiates an empty {@link TestDataStore}.
   */
  private TestDataStore() {
    // n/a
  }

  /**
   * Returns the store, loading it on first use.
   *
   * @return the store
   * @throws Exception the exception
   */
  public static synchronized SnomedStore get() throws Exception {
    if (store == null) {
      store = SnomedStore.load(TestDataOwlFileTest.OWL_FILE);
    }
    return store;
  }

}
