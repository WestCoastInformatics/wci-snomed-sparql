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
package com.wci.snomed.sparql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link RunMode}.
 */
public class RunModeTest {

  /**
   * Each mode option selects the corresponding mode.
   */
  @Test
  public void testFromArgs() {
    assertEquals(RunMode.CONVERT, RunMode.fromArgs(new String[] {
        "--release=/data/rf2", "--output=x.owl"
    }));
    assertEquals(RunMode.SERVE, RunMode.fromArgs(new String[] {
        "--owl=x.owl"
    }));
    assertEquals(RunMode.FETCH_EXAMPLES, RunMode.fromArgs(new String[] {
        "--fetch-examples=20261001"
    }));
  }

  /**
   * Zero or multiple mode options are rejected.
   */
  @Test
  public void testFromArgsInvalid() {
    assertThrows(IllegalArgumentException.class, () -> RunMode.fromArgs(new String[] {}));
    assertThrows(IllegalArgumentException.class, () -> RunMode.fromArgs(new String[] {
        "--owl=x.owl", "--release=/data/rf2"
    }));
    assertThrows(IllegalArgumentException.class, () -> RunMode.fromArgs(new String[] {
        "--owlfile=x.owl"
    }));
  }

}
