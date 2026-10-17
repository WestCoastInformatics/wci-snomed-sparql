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
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * Evaluates ECL expressions (via their SPARQL translation) against the store, with paging.
 */
@Service
@Profile("serve")
public class EclService {

  /** The number of recent results to keep. */
  private static final int CACHE_SIZE = 20;

  /** The store. */
  private final SnomedStore store;

  /** The translator. */
  private final EclTranslator translator;

  /** The query timeout in milliseconds. */
  private final long timeoutMs;

  /** Recent results by SPARQL query, so paging through a result does not re-run the query. */
  private final Map<String, long[]> cache = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {

    /** The serial version UID. */
    private static final long serialVersionUID = 1L;

    /* see superclass */
    @Override
    protected boolean removeEldestEntry(final Map.Entry<String, long[]> eldest) {
      return size() > CACHE_SIZE;
    }

  });

  /**
   * Instantiates a new service.
   *
   * @param store the store
   * @param translator the translator
   * @param timeoutSec the query timeout in seconds (0 for none)
   */
  public EclService(final SnomedStore store, final EclTranslator translator,
      @Value("${query.timeout-sec:300}") final long timeoutSec) {
    this.store = store;
    this.translator = translator;
    this.timeoutMs = timeoutSec * 1000;
  }

  /**
   * Translate an ECL expression to SPARQL.
   *
   * @param ecl the ECL expression
   * @return the SPARQL query
   */
  public String toSparql(final String ecl) {
    return translator.translate(ecl);
  }

  /**
   * Evaluate an ECL expression.
   *
   * @param ecl the ECL expression
   * @param limit the page size
   * @param offset the offset
   * @return the page
   */
  public ResultPage evaluateEcl(final String ecl, final int limit, final long offset) {
    return evaluateSparql(toSparql(ecl), limit, offset);
  }

  /**
   * Evaluate a SPARQL SELECT query binding {@code ?concept}.
   *
   * @param sparql the SPARQL query
   * @param limit the page size
   * @param offset the offset
   * @return the page
   */
  public ResultPage evaluateSparql(final String sparql, final int limit, final long offset) {
    long[] codes = cache.get(sparql);
    if (codes == null) {
      codes = store.selectCodes(sparql, timeoutMs);
      cache.put(sparql, codes);
    }
    final List<ConceptItem> items = new ArrayList<>();
    for (long i = offset; i < codes.length && i < offset + limit; i++) {
      final long code = codes[(int) i];
      items.add(new ConceptItem(Long.toString(code), store.getFsn(code)));
    }
    return new ResultPage(codes.length, limit, offset, items);
  }

  /**
   * Returns the store status.
   *
   * @return the status
   */
  public StoreStatus getStatus() {
    return new StoreStatus(store.getVersion(), store.getConceptCount(), store.getTripleCount());
  }

}
