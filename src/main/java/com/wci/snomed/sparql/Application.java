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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring application class. The run mode is chosen from the command line:
 *
 * <pre>
 *   --release=&lt;RF2 release dir&gt; [--output=&lt;owl file&gt;]   convert RF2 to OWL
 *   --owl=&lt;owl file&gt;                                      serve the ECL REST endpoint
 *   --fetch-examples=&lt;version&gt;                           gather expected test results
 * </pre>
 */
@SpringBootApplication
public class Application {

  /** The logger. */
  private static final Logger LOGGER = LoggerFactory.getLogger(Application.class);

  /**
   * Application entry point.
   *
   * @param args the command line arguments
   */
  public static void main(final String[] args) {
    final RunMode mode;
    try {
      mode = RunMode.fromArgs(args);
    } catch (final IllegalArgumentException e) {
      LOGGER.error(e.getMessage());
      System.exit(1);
      return;
    }
    LOGGER.info("Run mode: {}", mode);

    final SpringApplication app = new SpringApplication(Application.class);
    // Only the SERVE mode needs a web server; the other modes run and exit
    if (mode != RunMode.SERVE) {
      app.setWebApplicationType(WebApplicationType.NONE);
    }
    app.setAdditionalProfiles(mode.name().toLowerCase().replace('_', '-'));
    app.run(args);
  }

}
