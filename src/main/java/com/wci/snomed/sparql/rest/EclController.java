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

import com.wci.snomed.sparql.examples.EclExample;
import com.wci.snomed.sparql.examples.EclExampleCatalog;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoints. The request body is the ECL expression (or SPARQL query) as plain text.
 *
 * <pre>
 *   POST /ecl?limit=&amp;offset=      evaluate ECL, returns {total, limit, offset, items: [{code, name}]}
 *   POST /ecl/sparql               returns the SPARQL generated for the ECL (for debugging)
 *   POST /sparql?limit=&amp;offset=   evaluate a SPARQL SELECT binding ?concept (for debugging)
 *   GET  /status                   release version and store size
 *   GET  /examples                 the ECL examples (catalog) for the UI
 *   GET  /index.html               the UI
 * </pre>
 */
@RestController
@Profile("serve")
public class EclController {

  /** The default page size. */
  public static final int DEFAULT_LIMIT = 50;

  /** The maximum page size. */
  public static final int MAX_LIMIT = 10000;

  /** The service. */
  private final EclService service;

  /**
   * Instantiates a new controller.
   *
   * @param service the service
   */
  public EclController(final EclService service) {
    this.service = service;
  }

  /**
   * Evaluate an ECL expression.
   *
   * @param ecl the ECL expression
   * @param limit the page size
   * @param offset the offset
   * @return the page
   */
  @PostMapping(path = "/ecl", consumes = MediaType.TEXT_PLAIN_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
  public ResultPage ecl(@RequestBody final String ecl,
    @RequestParam(defaultValue = "" + DEFAULT_LIMIT) final int limit,
    @RequestParam(defaultValue = "0") final long offset) {
    checkPaging(limit, offset);
    return service.evaluateEcl(ecl, limit, offset);
  }

  /**
   * Return the SPARQL generated for an ECL expression.
   *
   * @param ecl the ECL expression
   * @return the SPARQL query
   */
  @PostMapping(path = "/ecl/sparql", consumes = MediaType.TEXT_PLAIN_VALUE, produces = MediaType.TEXT_PLAIN_VALUE)
  public String eclSparql(@RequestBody final String ecl) {
    return service.toSparql(ecl);
  }

  /**
   * Evaluate a SPARQL SELECT query binding {@code ?concept}.
   *
   * @param sparql the SPARQL query
   * @param limit the page size
   * @param offset the offset
   * @return the page
   */
  @PostMapping(path = "/sparql", consumes = MediaType.TEXT_PLAIN_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
  public ResultPage sparql(@RequestBody final String sparql,
    @RequestParam(defaultValue = "" + DEFAULT_LIMIT) final int limit,
    @RequestParam(defaultValue = "0") final long offset) {
    checkPaging(limit, offset);
    return service.evaluateSparql(sparql, limit, offset);
  }

  /**
   * Returns the store status.
   *
   * @return the status
   */
  @GetMapping(path = "/status", produces = MediaType.APPLICATION_JSON_VALUE)
  public StoreStatus status() {
    return service.getStatus();
  }

  /**
   * Returns the executable ECL examples (the test catalog), for the UI.
   *
   * @return the examples
   */
  @GetMapping(path = "/examples", produces = MediaType.APPLICATION_JSON_VALUE)
  public List<EclExample> examples() {
    return EclExampleCatalog.loadExecutable();
  }

  /**
   * Validate the paging parameters.
   *
   * @param limit the limit
   * @param offset the offset
   */
  private static void checkPaging(final int limit, final long offset) {
    if (limit < 0 || limit > MAX_LIMIT) {
      throw new IllegalArgumentException("limit must be between 0 and " + MAX_LIMIT);
    }
    if (offset < 0) {
      throw new IllegalArgumentException("offset must not be negative");
    }
  }

}
