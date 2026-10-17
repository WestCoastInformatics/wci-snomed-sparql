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

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Fetches the expected results of the ECL example catalog from a Snowstorm server (mode
 * "--fetch-examples=&lt;version&gt;"). Options:
 *
 * <pre>
 *   --snowstorm-url=&lt;url&gt;  Snowstorm base url (default http://127.0.0.1:8081, the local Snowstorm)
 *   --branch=&lt;branch&gt;      branch (default MAIN/&lt;yyyy-mm-dd of version&gt;)
 *   --output=&lt;dir&gt;         output dir (default src/test/resources/data/&lt;version&gt;)
 *   --max-ids=&lt;n&gt;          record concept ids only for results of at most n concepts (default: all)
 *   --delay-ms=&lt;ms&gt;        delay after each request (default 0; use e.g. 1000 for a shared server)
 *   --timeout-sec=&lt;s&gt;      request timeout (default 600)
 *   --skip-existing         skip examples whose result file already exists (resume an interrupted run)
 * </pre>
 *
 * Run it against a local Snowstorm loaded with the release (see snowstorm/README.md): the public SNOMED browsers
 * refuse non-browser clients, and the latest Snowstorm also supports a newer ECL version than the browser.
 */
@Component
@Profile("fetch-examples")
public class FetchExamplesRunner implements ApplicationRunner {

  /** The logger. */
  private static final Logger LOGGER = LoggerFactory.getLogger(FetchExamplesRunner.class);

  /** Length of a yyyyMMdd version. */
  private static final int VERSION_LENGTH = 8;

  /** Offset of the month in a yyyyMMdd version. */
  private static final int MONTH = 4;

  /** Offset of the day in a yyyyMMdd version. */
  private static final int DAY = 6;

  /** The release version (yyyyMMdd). */
  @Value("${fetch-examples}")
  private String version;

  /** The snowstorm base url. */
  @Value("${snowstorm-url:http://127.0.0.1:8081}")
  private String snowstormUrl;

  /** The branch (defaults from the version). */
  @Value("${branch:}")
  private String branch;

  /** The output dir (defaults from the version). */
  @Value("${output:}")
  private String output;

  /** The max ids. */
  @Value("${max-ids:9223372036854775807}")
  private long maxIds;

  /** The delay after each request. */
  @Value("${delay-ms:0}")
  private long delayMs;

  /** The request timeout, in seconds. */
  @Value("${timeout-sec:600}")
  private long timeoutSec;

  /** Whether to skip examples that already have a result file ("" when given as a bare flag). */
  @Value("${skip-existing:false}")
  private String skipExisting;

  /* see superclass */
  @Override
  public void run(final ApplicationArguments args) throws Exception {
    if (version.length() != VERSION_LENGTH || !version.chars().allMatch(Character::isDigit)) {
      throw new IllegalArgumentException("Version must be yyyyMMdd: " + version);
    }
    final String br = branch.isBlank() ? "MAIN/" + version.substring(0, MONTH) + "-"
        + version.substring(MONTH, DAY) + "-" + version.substring(DAY) : branch;
    final Path dir = output.isBlank() ? Path.of("src/test/resources/data", version) : Path.of(output);
    final SnowstormClient client = new SnowstormClient(snowstormUrl, br, delayMs, Duration.ofSeconds(timeoutSec));
    LOGGER.info("Fetching expected results from {} {} into {}", snowstormUrl, br, dir);
    final boolean skip = !"false".equalsIgnoreCase(skipExisting.trim());
    for (final EclExample example : EclExampleCatalog.loadExecutable()) {
      if (skip && Files.exists(dir.resolve(example.id() + ".json"))) {
        continue;
      }
      final ExpectedResult result = client.evaluate(example.id(), example.ecl(), maxIds);
      ExpectedResultStore.write(dir, result);
      LOGGER.info("  {} -> {}", example.id(), result.evaluated() ? result.total() + " concepts"
          + (result.ids() == null ? " (count only)" : "") : "HTTP " + result.status() + " " + result.error());
    }
  }

}
