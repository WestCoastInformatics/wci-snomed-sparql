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
package com.wci.snomed.sparql;

import java.util.ArrayList;
import java.util.List;

/**
 * The three ways the application can be launched.
 */
public enum RunMode {

  /** Convert an RF2 release folder to an OWL file (with isMemberOf annotations). */
  CONVERT("release"),

  /** Load an OWL file and serve the ECL REST endpoint. */
  SERVE("owl"),

  /** Fetch expected ECL results for a release version from the SNOMED browser. */
  FETCH_EXAMPLES("fetch-examples");

  /** The command line option (without the leading "--") that selects this mode. */
  private final String option;

  /**
   * Instantiates a new run mode.
   *
   * @param option the command line option
   */
  RunMode(final String option) {
    this.option = option;
  }

  /**
   * Returns the command line option (without the leading "--").
   *
   * @return the option
   */
  public String getOption() {
    return option;
  }

  /**
   * Determine the run mode from the command line arguments. Exactly one mode option must be present.
   *
   * @param args the command line arguments
   * @return the run mode
   * @throws IllegalArgumentException if zero or more than one mode option is present
   */
  public static RunMode fromArgs(final String[] args) {
    final List<RunMode> found = new ArrayList<>();
    for (final RunMode mode : values()) {
      for (final String arg : args) {
        if (arg.equals("--" + mode.option) || arg.startsWith("--" + mode.option + "=")) {
          found.add(mode);
          break;
        }
      }
    }
    if (found.size() != 1) {
      throw new IllegalArgumentException("Exactly one of --release=<dir>, --owl=<file>, "
          + "or --fetch-examples=<version> must be specified");
    }
    return found.get(0);
  }

}
