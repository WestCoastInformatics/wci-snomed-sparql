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
 * The expected result of an ECL example, as returned by the SNOMED browser (Snowstorm) for a release version.
 * The total is always recorded; concept ids are recorded when the result is small enough (see
 * {@link #ids()}).
 *
 * @param id the example id
 * @param ecl the expression
 * @param status the HTTP status returned by the server (200 when the expression was evaluated)
 * @param total the number of matching concepts (null if the expression was not evaluated)
 * @param ids the sorted matching concept ids, or null if not recorded (result too large, or error)
 * @param error the server's error message, if the expression was not evaluated
 */
public record ExpectedResult(String id, String ecl, int status, Long total, List<String> ids, String error) {

  /** HTTP OK. */
  public static final int OK = 200;

  /**
   * Indicates whether the server evaluated the expression.
   *
   * @return true, if evaluated
   */
  public boolean evaluated() {
    return status == OK && total != null;
  }

}
