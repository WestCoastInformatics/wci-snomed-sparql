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

import com.wci.snomed.sparql.ecl.EclTranslator;
import com.wci.snomed.sparql.store.SnomedStore;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Beans for the "--owl=&lt;file&gt;" mode: the store is loaded at startup.
 */
@Configuration
@Profile("serve")
public class ServeConfiguration {

  /**
   * The store.
   *
   * @param owl the OWL file
   * @return the store
   * @throws Exception the exception
   */
  @Bean
  public SnomedStore snomedStore(@Value("${owl}") final String owl) throws Exception {
    return SnomedStore.load(Path.of(owl));
  }

  /**
   * The ECL translator.
   *
   * @return the translator
   */
  @Bean
  public EclTranslator eclTranslator() {
    return new EclTranslator();
  }

}
