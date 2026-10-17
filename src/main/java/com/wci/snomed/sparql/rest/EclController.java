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
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
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
 *   GET  /swagger-ui/index.html    the OpenAPI documentation (springdoc)
 * </pre>
 */
@RestController
@Profile("serve")
@Tag(name = "ECL", description = "Evaluate SNOMED CT Expression Constraint Language (ECL) expressions via SPARQL")
public class EclController {

  /** The default page size. */
  public static final int DEFAULT_LIMIT = 50;

  /** The maximum page size. */
  public static final int MAX_LIMIT = 10000;

  /** Example ECL expressions for the API documentation. */
  private static final String ECL_DESCENDANTS = "< 73211009 |Diabetes mellitus|";

  /** Example refinement. */
  private static final String ECL_REFINEMENT =
      "< 19829001 |Disorder of lung| : 116676008 |Associated morphology| = 79654002 |Edema|";

  /** Example member of. */
  private static final String ECL_MEMBER_OF =
      "^ 447562003 |ICD-10 complex map reference set| AND < 195967001 |Asthma|";

  /** Example history supplement. */
  private static final String ECL_HISTORY = "<< 195967001 |Asthma| {{ + HISTORY-MIN }}";

  /** Example description filter. */
  private static final String ECL_FILTER = "< 64572001 |Disease| {{ term = \"heart att\" }}";

  /** Example SPARQL query. */
  private static final String SPARQL_EXAMPLE = """
      PREFIX sct: <http://snomed.info/id/>
      PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
      SELECT ?concept WHERE { ?concept rdfs:subClassOf+ sct:73211009 }""";

  /** Description of the paging parameters. */
  private static final String LIMIT_DESCRIPTION = "Page size: 0 (count only) to " + MAX_LIMIT;

  /** Description of the offset parameter. */
  private static final String OFFSET_DESCRIPTION = "Index of the first result to return (0-based)";

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
  @Operation(summary = "Evaluate an ECL expression",
      description = "Parses the ECL expression (request body, plain text), translates it to SPARQL, runs it against "
          + "the loaded SNOMED CT release and returns one page of the matching concepts (code and fully specified "
          + "name), in numeric code order, with the total. Recent results are cached, so paging through a result "
          + "does not re-run the query.",
      requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "The ECL expression",
          required = true, content = @Content(mediaType = MediaType.TEXT_PLAIN_VALUE,
              schema = @Schema(type = "string"), examples = {
                  @ExampleObject(name = "Descendants", value = ECL_DESCENDANTS),
                  @ExampleObject(name = "Refinement", value = ECL_REFINEMENT),
                  @ExampleObject(name = "Member of", value = ECL_MEMBER_OF),
                  @ExampleObject(name = "History supplement", value = ECL_HISTORY),
                  @ExampleObject(name = "Description filter", value = ECL_FILTER)
              })))
  @ApiResponse(responseCode = "200", description = "A page of matching concepts")
  @ApiResponse(responseCode = "400", description = "Invalid ECL, or invalid paging parameters",
      content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
          schema = @Schema(implementation = ProblemDetail.class)))
  @ApiResponse(responseCode = "501", description = "Valid ECL that the SPARQL translation does not support "
      + "(e.g. refset member field filters, top/bottom of set)",
      content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
          schema = @Schema(implementation = ProblemDetail.class)))
  @ApiResponse(responseCode = "503", description = "The query timed out",
      content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
          schema = @Schema(implementation = ProblemDetail.class)))
  @PostMapping(path = "/ecl", consumes = MediaType.TEXT_PLAIN_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
  public ResultPage ecl(@RequestBody final String ecl,
    @Parameter(description = LIMIT_DESCRIPTION, example = "50") @RequestParam(defaultValue = ""
        + DEFAULT_LIMIT) final int limit,
    @Parameter(description = OFFSET_DESCRIPTION, example = "0") @RequestParam(defaultValue = "0") final long offset) {
    checkPaging(limit, offset);
    return service.evaluateEcl(ecl, limit, offset);
  }

  /**
   * Return the SPARQL generated for an ECL expression.
   *
   * @param ecl the ECL expression
   * @return the SPARQL query
   */
  @Operation(summary = "Translate an ECL expression to SPARQL",
      description = "Returns the SPARQL SELECT query (binding ?concept) that POST /ecl runs for the expression, "
          + "without running it.",
      requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "The ECL expression",
          required = true, content = @Content(mediaType = MediaType.TEXT_PLAIN_VALUE,
              schema = @Schema(type = "string"), examples = {
                  @ExampleObject(name = "Descendants", value = ECL_DESCENDANTS),
                  @ExampleObject(name = "Refinement", value = ECL_REFINEMENT),
                  @ExampleObject(name = "History supplement", value = ECL_HISTORY)
              })))
  @ApiResponse(responseCode = "200", description = "The SPARQL query",
      content = @Content(mediaType = MediaType.TEXT_PLAIN_VALUE, schema = @Schema(type = "string")))
  @ApiResponse(responseCode = "400", description = "Invalid ECL",
      content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
          schema = @Schema(implementation = ProblemDetail.class)))
  @ApiResponse(responseCode = "501", description = "Valid ECL that the SPARQL translation does not support",
      content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
          schema = @Schema(implementation = ProblemDetail.class)))
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
  @Operation(summary = "Run a SPARQL query",
      description = "Runs a SPARQL SELECT query against the loaded store and returns one page of the SNOMED CT "
          + "concepts bound to ?concept (or to the first variable), for debugging and experimenting with the "
          + "generated queries. The store holds the OWL file as RDF, plus index triples: rdfs:subClassOf to named "
          + "parents, sct:hasRestriction and sct:hasRoleGroup (see README.md).",
      requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "The SPARQL SELECT query",
          required = true, content = @Content(mediaType = MediaType.TEXT_PLAIN_VALUE,
              schema = @Schema(type = "string"), examples = @ExampleObject(name = "Descendants",
                  value = SPARQL_EXAMPLE))))
  @ApiResponse(responseCode = "200", description = "A page of matching concepts")
  @ApiResponse(responseCode = "400", description = "Invalid SPARQL, or invalid paging parameters",
      content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
          schema = @Schema(implementation = ProblemDetail.class)))
  @ApiResponse(responseCode = "503", description = "The query timed out",
      content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
          schema = @Schema(implementation = ProblemDetail.class)))
  @PostMapping(path = "/sparql", consumes = MediaType.TEXT_PLAIN_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
  public ResultPage sparql(@RequestBody final String sparql,
    @Parameter(description = LIMIT_DESCRIPTION, example = "50") @RequestParam(defaultValue = ""
        + DEFAULT_LIMIT) final int limit,
    @Parameter(description = OFFSET_DESCRIPTION, example = "0") @RequestParam(defaultValue = "0") final long offset) {
    checkPaging(limit, offset);
    return service.evaluateSparql(sparql, limit, offset);
  }

  /**
   * Returns the store status.
   *
   * @return the status
   */
  @Operation(summary = "Get the store status",
      description = "The SNOMED CT release version loaded, and the number of concepts and RDF triples in the store.")
  @ApiResponse(responseCode = "200", description = "The status")
  @GetMapping(path = "/status", produces = MediaType.APPLICATION_JSON_VALUE)
  public StoreStatus status() {
    return service.getStatus();
  }

  /**
   * Returns the executable ECL examples (the test catalog), for the UI.
   *
   * @return the examples
   */
  @Operation(summary = "List the ECL examples",
      description = "The executable ECL examples of the test catalog: the examples from the ECL specification "
          + "(\"Behaviour specification with examples\"), the snomed-ecl-parser tests (P-xx) and International "
          + "edition adaptations (A-xx). Used by the UI.")
  @ApiResponse(responseCode = "200", description = "The examples",
      content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
          array = @ArraySchema(schema = @Schema(implementation = EclExample.class))))
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
