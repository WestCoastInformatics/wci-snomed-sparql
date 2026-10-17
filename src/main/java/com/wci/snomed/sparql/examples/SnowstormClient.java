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
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Minimal client for the Snowstorm ECL concept search used by the SNOMED browser:
 * {@code GET <baseUrl>/<branch>/concepts?ecl=...&returnIdOnly=true}. Requests are made one at a time, with a
 * fixed delay after each, to avoid overloading the server.
 */
public class SnowstormClient {

  /** Snowstorm's maximum page size. */
  public static final int MAX_PAGE = 10000;

  /** Connect timeout. */
  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);

  /** The json mapper. */
  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  /** The http client. */
  private final HttpClient client = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();

  /** The concepts endpoint, e.g. https://host/snowstorm/snomed-ct/MAIN/2026-10-01/concepts. */
  private final String conceptsUrl;

  /** Delay after each request, in milliseconds. */
  private final long delayMs;

  /** Request timeout. */
  private final Duration timeout;

  /**
   * Instantiates a new snowstorm client.
   *
   * @param baseUrl the Snowstorm base url (e.g. https://host/snowstorm/snomed-ct)
   * @param branch the branch (e.g. MAIN/2026-10-01)
   * @param delayMs the delay after each request, in milliseconds
   * @param timeout the request timeout
   */
  public SnowstormClient(final String baseUrl, final String branch, final long delayMs, final Duration timeout) {
    this.conceptsUrl = baseUrl.replaceAll("/+$", "") + "/" + branch + "/concepts";
    this.delayMs = delayMs;
    this.timeout = timeout;
  }

  /**
   * Evaluate an ECL expression: always gets the total, and the matching ids if the total is at most maxIds.
   * Request failures (e.g. timeouts) are recorded in the result with status 0 rather than thrown.
   *
   * @param id the example id
   * @param ecl the expression
   * @param maxIds the maximum number of ids to fetch (0 for counts only)
   * @return the expected result
   * @throws InterruptedException the interrupted exception
   */
  public ExpectedResult evaluate(final String id, final String ecl, final long maxIds)
    throws InterruptedException {
    try {
      return doEvaluate(id, ecl, maxIds);
    } catch (final IOException e) {
      return new ExpectedResult(id, ecl, 0, null, null, e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }

  /**
   * Evaluate an ECL expression.
   *
   * @param id the example id
   * @param ecl the expression
   * @param maxIds the maximum number of ids to fetch (0 for counts only)
   * @return the expected result
   * @throws IOException Signals that an I/O exception has occurred.
   * @throws InterruptedException the interrupted exception
   */
  private ExpectedResult doEvaluate(final String id, final String ecl, final long maxIds)
    throws IOException, InterruptedException {
    HttpResponse<String> response = get(ecl, 1, null);
    if (response.statusCode() != ExpectedResult.OK) {
      return new ExpectedResult(id, ecl, response.statusCode(), null, null, errorMessage(response.body()));
    }
    final long total = MAPPER.readTree(response.body()).path("total").asLong();
    if (total > maxIds) {
      return new ExpectedResult(id, ecl, ExpectedResult.OK, total, null, null);
    }
    final List<String> ids = new ArrayList<>();
    String searchAfter = null;
    while (ids.size() < total) {
      response = get(ecl, MAX_PAGE, searchAfter);
      if (response.statusCode() != ExpectedResult.OK) {
        return new ExpectedResult(id, ecl, response.statusCode(), total, null, errorMessage(response.body()));
      }
      final JsonNode page = MAPPER.readTree(response.body());
      page.path("items").forEach(item -> ids.add(item.asString()));
      searchAfter = page.path("searchAfter").asString(null);
      if (page.path("items").isEmpty() || searchAfter == null) {
        break;
      }
    }
    return new ExpectedResult(id, ecl, ExpectedResult.OK, total, ids.stream().sorted().distinct().toList(), null);
  }

  /**
   * Make one request, then wait the configured delay.
   *
   * @param ecl the ecl
   * @param limit the page size
   * @param searchAfter the search after token, or null for the first page
   * @return the http response
   * @throws IOException Signals that an I/O exception has occurred.
   * @throws InterruptedException the interrupted exception
   */
  private HttpResponse<String> get(final String ecl, final int limit, final String searchAfter)
    throws IOException, InterruptedException {
    final StringBuilder url = new StringBuilder(conceptsUrl).append("?offset=0&limit=").append(limit)
        .append("&termActive=true&returnIdOnly=true&ecl=").append(URLEncoder.encode(ecl, StandardCharsets.UTF_8));
    if (searchAfter != null) {
      url.append("&searchAfter=").append(URLEncoder.encode(searchAfter, StandardCharsets.UTF_8));
    }
    final HttpRequest request = HttpRequest.newBuilder(URI.create(url.toString())).timeout(timeout)
        .header("Accept", "application/json").GET().build();
    try {
      return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    } finally {
      Thread.sleep(delayMs);
    }
  }

  /**
   * Extract the error message from a Snowstorm error response.
   *
   * @param body the body
   * @return the message
   */
  private static String errorMessage(final String body) {
    try {
      final String message = MAPPER.readTree(body).path("message").asString(null);
      return message != null ? message : body;
    } catch (final RuntimeException e) {
      return body;
    }
  }

}
