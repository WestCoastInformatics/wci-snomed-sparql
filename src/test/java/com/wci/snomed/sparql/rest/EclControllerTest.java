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

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.wci.snomed.sparql.ecl.EclTranslator;
import com.wci.snomed.sparql.store.MiniStore;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Unit tests for {@link EclController} against the mini release store.
 */
public class EclControllerTest {

  /** The mock MVC. */
  private static MockMvc mvc;

  /**
   * Set up the controller.
   *
   * @throws Exception the exception
   */
  @BeforeAll
  public static void setup() throws Exception {
    final EclService service = new EclService(MiniStore.get(), new EclTranslator(), 0);
    mvc = MockMvcBuilders.standaloneSetup(new EclController(service)).setControllerAdvice(new ErrorHandler()).build();
  }

  /**
   * Results are paged in numeric code order, with the total.
   *
   * @throws Exception the exception
   */
  @Test
  public void testPaging() throws Exception {
    mvc.perform(post("/ecl").contentType(MediaType.TEXT_PLAIN).content("*").param("limit", "2").param("offset", "4"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(14)).andExpect(jsonPath("$.limit").value(2))
        .andExpect(jsonPath("$.offset").value(4)).andExpect(jsonPath("$.items.length()").value(2))
        .andExpect(jsonPath("$.items[0].code").value("363698007"))
        .andExpect(jsonPath("$.items[1].code").value("404684003"))
        .andExpect(jsonPath("$.items[1].name").value("Clinical finding (finding)"));
    // default limit, past the end, and count only
    mvc.perform(post("/ecl").contentType(MediaType.TEXT_PLAIN).content("*")).andExpect(status().isOk())
        .andExpect(jsonPath("$.limit").value(EclController.DEFAULT_LIMIT))
        .andExpect(jsonPath("$.items.length()").value(14));
    mvc.perform(post("/ecl").contentType(MediaType.TEXT_PLAIN).content("*").param("offset", "20"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(14))
        .andExpect(jsonPath("$.items.length()").value(0));
    mvc.perform(post("/ecl").contentType(MediaType.TEXT_PLAIN).content("*").param("limit", "0"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(14))
        .andExpect(jsonPath("$.items.length()").value(0));
  }

  /**
   * A single concept reference.
   *
   * @throws Exception the exception
   */
  @Test
  public void testSelf() throws Exception {
    mvc.perform(post("/ecl").contentType(MediaType.TEXT_PLAIN).content("999990008 |Thyroid disorder|"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.items[0].code").value("999990008"))
        .andExpect(jsonPath("$.items[0].name").value("Thyroid disorder (disorder)"));
    // unknown concept
    mvc.perform(post("/ecl").contentType(MediaType.TEXT_PLAIN).content("123456001")).andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(0));
  }

  /**
   * Errors: invalid ECL and paging are 400, unsupported ECL is 501.
   *
   * @throws Exception the exception
   */
  @Test
  public void testErrors() throws Exception {
    mvc.perform(post("/ecl").contentType(MediaType.TEXT_PLAIN).content("< 404684003 :"))
        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").value("ECL is incomplete."));
    mvc.perform(post("/ecl").contentType(MediaType.TEXT_PLAIN).content("*").param("limit", "-1"))
        .andExpect(status().isBadRequest());
    mvc.perform(post("/ecl").contentType(MediaType.TEXT_PLAIN).content("*").param("limit", "10001"))
        .andExpect(status().isBadRequest());
    mvc.perform(post("/ecl").contentType(MediaType.TEXT_PLAIN).content("*").param("offset", "-1"))
        .andExpect(status().isBadRequest());
    mvc.perform(post("/sparql").contentType(MediaType.TEXT_PLAIN).content("SELECT WHERE"))
        .andExpect(status().isBadRequest());
  }

  /**
   * The generated SPARQL, a raw SPARQL query, and the status.
   *
   * @throws Exception the exception
   */
  @Test
  public void testDebugEndpoints() throws Exception {
    mvc.perform(post("/ecl/sparql").contentType(MediaType.TEXT_PLAIN).content("*")).andExpect(status().isOk())
        .andExpect(content().string(containsString("?concept a owl:Class")));
    mvc.perform(post("/sparql").contentType(MediaType.TEXT_PLAIN).content(EclTranslator.PREFIXES
        + "SELECT ?concept WHERE { ?concept rdfs:subClassOf+ sct:404684003 }")).andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(2)).andExpect(jsonPath("$.items[0].code").value("362969004"));
    mvc.perform(get("/status")).andExpect(status().isOk()).andExpect(jsonPath("$.version").value("20190131"))
        .andExpect(jsonPath("$.concepts").value(14));
  }

}
