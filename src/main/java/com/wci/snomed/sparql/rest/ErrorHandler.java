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

import com.wci.snomed.sparql.ecl.UnsupportedEclException;
import org.apache.jena.query.QueryCancelledException;
import org.apache.jena.query.QueryException;
import org.snomed.langauges.ecl.ECLException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps exceptions to RFC 9457 problem details.
 */
@RestControllerAdvice
public class ErrorHandler {

  /**
   * Invalid ECL, SPARQL, or paging parameters.
   *
   * @param e the exception
   * @return the problem detail
   */
  @ExceptionHandler({
      ECLException.class, IllegalArgumentException.class, QueryException.class
  })
  public ProblemDetail badRequest(final RuntimeException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
  }

  /**
   * Valid ECL that the translation does not support.
   *
   * @param e the exception
   * @return the problem detail
   */
  @ExceptionHandler(UnsupportedEclException.class)
  public ProblemDetail unsupported(final UnsupportedEclException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_IMPLEMENTED, e.getMessage());
  }

  /**
   * Query timeout.
   *
   * @param e the exception
   * @return the problem detail
   */
  @ExceptionHandler(QueryCancelledException.class)
  public ProblemDetail timeout(final QueryCancelledException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, "Query timed out");
  }

}
