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
package com.wci.snomed.sparql.convert;

import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Runs the RF2 to OWL conversion (mode "--release=&lt;dir&gt; [--output=&lt;file&gt;]").
 */
@Component
@Profile("convert")
public class ConvertRunner implements ApplicationRunner {

  /** The logger. */
  private static final Logger LOGGER = LoggerFactory.getLogger(ConvertRunner.class);

  /** The release dir. */
  @Value("${release}")
  private String release;

  /** The output file (defaults to snomed-inferred-&lt;version&gt;.owl in the current directory). */
  @Value("${output:}")
  private String output;

  /* see superclass */
  @Override
  public void run(final ApplicationArguments args) throws Exception {
    final Path releaseDir = Path.of(release);
    final Path outputFile = output.isBlank()
        ? Path.of("snomed-inferred-" + ReleaseFolder.resolve(releaseDir).getVersionDate() + ".owl")
        : Path.of(output);
    final long start = System.currentTimeMillis();
    new InferredOwlConverter().convert(releaseDir, outputFile);
    LOGGER.info("Conversion finished in {}s", (System.currentTimeMillis() - start) / 1000);
  }

}
