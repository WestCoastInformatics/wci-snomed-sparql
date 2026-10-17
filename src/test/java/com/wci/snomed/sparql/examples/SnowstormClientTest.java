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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link SnowstormClient} and {@link ExpectedResultStore} against a mock Snowstorm server.
 */
public class SnowstormClientTest {

  /** Response delay of the "slow" expression, longer than the client timeout. */
  private static final long SLOW_MS = 7000;

  /** HTTP bad request. */
  private static final int BAD_REQUEST = 400;

  /** The mock server. */
  private static HttpServer server;

  /** The requests received. */
  private static final List<String> REQUESTS = new ArrayList<>();

  /** The client. */
  private static SnowstormClient client;

  /** The temp dir. */
  @TempDir
  private static Path tempDir;

  /**
   * Start the mock server. "<< 1" has 3 concepts, served 2 per page (as if the page limit were 2); "bad" is an
   * error; anything else has 20000 concepts.
   *
   * @throws IOException Signals that an I/O exception has occurred.
   */
  @BeforeAll
  public static void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    server.createContext("/snowstorm/snomed-ct/MAIN/2026-10-01/concepts", SnowstormClientTest::handle);
    server.start();
    client = new SnowstormClient("http://localhost:" + server.getAddress().getPort() + "/snowstorm/snomed-ct/",
        "MAIN/2026-10-01", 0, Duration.ofSeconds(5));
  }

  /**
   * Stop the mock server.
   */
  @AfterAll
  public static void stop() {
    server.stop(0);
  }

  /**
   * Handle a mock request.
   *
   * @param exchange the exchange
   * @throws IOException Signals that an I/O exception has occurred.
   */
  private static void handle(final HttpExchange exchange) throws IOException {
    final String query = URLDecoder.decode(exchange.getRequestURI().getRawQuery(), StandardCharsets.UTF_8);
    REQUESTS.add(query);
    final String body;
    int status = ExpectedResult.OK;
    if (query.contains("ecl=bad")) {
      status = BAD_REQUEST;
      body = "{\"message\":\"Failed to parse ECL 'bad'\"}";
    } else if (query.contains("ecl=slow")) {
      try {
        Thread.sleep(SLOW_MS);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      body = "{}";
    } else if (query.contains("ecl=<< 1")) {
      if (query.contains("limit=1&")) {
        body = "{\"items\":[\"30\"],\"total\":3,\"searchAfter\":\"a\"}";
      } else if (!query.contains("searchAfter=")) {
        body = "{\"items\":[\"30\",\"10\"],\"total\":3,\"searchAfter\":\"p2\"}";
      } else {
        body = "{\"items\":[\"20\"],\"total\":3,\"searchAfter\":\"p3\"}";
      }
    } else {
      body = "{\"items\":[\"1\"],\"total\":20000}";
    }
    final byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }

  /**
   * Small results are paged with searchAfter and saved with sorted ids; large ones are counts only; errors keep
   * the server message; results round-trip through the store.
   *
   * @throws Exception the exception
   */
  @Test
  public void testEvaluate() throws Exception {
    final ExpectedResult small = client.evaluate("t1", "<< 1", SnowstormClient.MAX_PAGE);
    assertTrue(small.evaluated());
    assertEquals(3L, small.total());
    assertEquals(List.of("10", "20", "30"), small.ids());
    assertTrue(REQUESTS.stream().anyMatch(r -> r.contains("returnIdOnly=true") && r.contains("searchAfter=p2")));

    final ExpectedResult large = client.evaluate("t2", "< 2", SnowstormClient.MAX_PAGE);
    assertEquals(20000L, large.total());
    assertNull(large.ids());

    final ExpectedResult bad = client.evaluate("t3", "bad", SnowstormClient.MAX_PAGE);
    assertFalse(bad.evaluated());
    assertEquals(BAD_REQUEST, bad.status());
    assertEquals("Failed to parse ECL 'bad'", bad.error());

    final ExpectedResult slow = new SnowstormClient("http://localhost:" + server.getAddress().getPort()
        + "/snowstorm/snomed-ct", "MAIN/2026-10-01", 0, Duration.ofSeconds(1)).evaluate("t4", "slow", 1);
    assertEquals(0, slow.status());
    assertTrue(slow.error().startsWith("HttpTimeoutException"), slow.error());

    ExpectedResultStore.write(tempDir, small);
    ExpectedResultStore.write(tempDir, large);
    ExpectedResultStore.write(tempDir, bad);
    assertEquals(List.of(small, large, bad), ExpectedResultStore.readAll(tempDir));
  }

}
