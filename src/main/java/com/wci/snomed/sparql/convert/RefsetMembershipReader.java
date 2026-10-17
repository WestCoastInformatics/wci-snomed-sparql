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

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads active reference set memberships of concepts from RF2 refset snapshot files, and the concept and
 * description data needed to describe inactive member concepts.
 */
public final class RefsetMembershipReader {

  /** The logger. */
  private static final Logger LOGGER = LoggerFactory.getLogger(RefsetMembershipReader.class);

  /** Index of the "active" column (common to all refset files). */
  private static final int ACTIVE_COL = 2;

  /** Index of the "refsetId" column (common to all refset files). */
  private static final int REFSET_ID_COL = 4;

  /** Index of the "referencedComponentId" column (common to all refset files). */
  private static final int REFERENCED_COMPONENT_COL = 5;

  /** Index of the "targetComponentId" column (association refsets). */
  private static final int TARGET_COMPONENT_COL = 6;

  /** Index of the "conceptId" column (description file). */
  private static final int CONCEPT_COL = 4;

  /** Index of the "typeId" column (description file). */
  private static final int TYPE_COL = 6;

  /** Index of the "term" column (description file). */
  private static final int TERM_COL = 7;

  /** The FSN description type. */
  private static final String FSN_TYPE = "900000000000003001";

  /** Offset from the end of an SCTID to its partition identifier (two digits before the check digit). */
  private static final int PARTITION_OFFSET = 3;

  /**
   * Instantiates an empty {@link RefsetMembershipReader}.
   */
  private RefsetMembershipReader() {
    // n/a
  }

  /**
   * Read the active members of all refsets, keeping only members that are concepts (active or inactive: like
   * Snowstorm, "^ refset" returns inactive concepts too, e.g. the members of historical association refsets).
   *
   * @param refsetFiles the refset snapshot files
   * @param conceptIds the concept ids (active and inactive)
   * @return map of refset id to its member concept ids
   * @throws IOException Signals that an I/O exception has occurred.
   */
  public static Map<Long, Set<Long>> read(final Iterable<Path> refsetFiles, final Set<Long> conceptIds)
    throws IOException {
    final Map<Long, Set<Long>> members = new TreeMap<>();
    for (final Path file : refsetFiles) {
      long count = 0;
      try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
        // skip header
        String line = reader.readLine();
        while ((line = reader.readLine()) != null) {
          final String[] fields = line.split("\t", REFERENCED_COMPONENT_COL + 2);
          if (fields.length <= REFERENCED_COMPONENT_COL || !"1".equals(fields[ACTIVE_COL])
              || !isConceptId(fields[REFERENCED_COMPONENT_COL])) {
            continue;
          }
          final Long conceptId = Long.valueOf(fields[REFERENCED_COMPONENT_COL]);
          if (conceptIds.contains(conceptId)) {
            members.computeIfAbsent(Long.valueOf(fields[REFSET_ID_COL]), k -> new TreeSet<>()).add(conceptId);
            count++;
          }
        }
      }
      LOGGER.info("  {} active concept members in {}", count, file.getFileName());
    }
    return members;
  }

  /**
   * Read the active members of association refsets whose referenced component and target are concepts.
   *
   * @param associationFiles the association refset snapshot files (refsetId, referencedComponentId,
   *          targetComponentId)
   * @param conceptIds the concept ids (active and inactive)
   * @return map of refset id to its (referenced concept id, target concept id) pairs
   * @throws IOException Signals that an I/O exception has occurred.
   */
  public static Map<Long, Set<Association>> readAssociations(final Iterable<Path> associationFiles,
    final Set<Long> conceptIds) throws IOException {
    final Map<Long, Set<Association>> associations = new TreeMap<>();
    for (final Path file : associationFiles) {
      long count = 0;
      try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
        String line = reader.readLine();
        while ((line = reader.readLine()) != null) {
          final String[] fields = line.split("\t");
          if (fields.length <= TARGET_COMPONENT_COL || !"1".equals(fields[ACTIVE_COL])
              || !isConceptId(fields[REFERENCED_COMPONENT_COL]) || !isConceptId(fields[TARGET_COMPONENT_COL])) {
            continue;
          }
          final Association association = new Association(Long.parseLong(fields[REFERENCED_COMPONENT_COL]),
              Long.parseLong(fields[TARGET_COMPONENT_COL]));
          if (conceptIds.contains(association.source()) && conceptIds.contains(association.target())) {
            associations.computeIfAbsent(Long.valueOf(fields[REFSET_ID_COL]), k -> new TreeSet<>()).add(association);
            count++;
          }
        }
      }
      LOGGER.info("  {} active concept associations in {}", count, file.getFileName());
    }
    return associations;
  }

  /**
   * Read the concept snapshot file.
   *
   * @param conceptFile the concept snapshot file
   * @return map of concept id to active flag
   * @throws IOException Signals that an I/O exception has occurred.
   */
  public static Map<Long, Boolean> readConcepts(final Path conceptFile) throws IOException {
    final Map<Long, Boolean> concepts = new HashMap<>();
    try (BufferedReader reader = Files.newBufferedReader(conceptFile, StandardCharsets.UTF_8)) {
      String line = reader.readLine();
      while ((line = reader.readLine()) != null) {
        final String[] fields = line.split("\t", ACTIVE_COL + 2);
        concepts.put(Long.valueOf(fields[0]), "1".equals(fields[ACTIVE_COL]));
      }
    }
    return concepts;
  }

  /**
   * Read the fully specified names of the given concepts (active FSN descriptions; the first one found if a
   * concept has several, e.g. in different languages).
   *
   * @param descriptionFiles the description snapshot files
   * @param conceptIds the concept ids
   * @return map of concept id to FSN
   * @throws IOException Signals that an I/O exception has occurred.
   */
  public static Map<Long, String> readFsns(final Iterable<Path> descriptionFiles, final Set<Long> conceptIds)
    throws IOException {
    final Map<Long, String> fsns = new HashMap<>();
    for (final Path file : descriptionFiles) {
      try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
        String line = reader.readLine();
        while ((line = reader.readLine()) != null) {
          final String[] fields = line.split("\t");
          if (fields.length > TERM_COL && "1".equals(fields[ACTIVE_COL]) && FSN_TYPE.equals(fields[TYPE_COL])) {
            final Long conceptId = Long.valueOf(fields[CONCEPT_COL]);
            if (conceptIds.contains(conceptId)) {
              fsns.putIfAbsent(conceptId, fields[TERM_COL]);
            }
          }
        }
      }
    }
    return fsns;
  }

  /**
   * An association refset member: the referenced (typically inactive) concept and its target concept.
   *
   * @param source the referenced concept id
   * @param target the target concept id
   */
  public record Association(long source, long target) implements Comparable<Association> {

    /* see superclass */
    @Override
    public int compareTo(final Association other) {
      final int cmp = Long.compare(source, other.source);
      return cmp != 0 ? cmp : Long.compare(target, other.target);
    }

  }

  /**
   * Indicates whether the SCTID identifies a concept (partition identifier 00 or 10).
   *
   * @param sctid the sctid
   * @return true, if the id is a concept id
   */
  public static boolean isConceptId(final String sctid) {
    if (sctid.length() < PARTITION_OFFSET + 1 || !sctid.chars().allMatch(Character::isDigit)) {
      return false;
    }
    final String partition = sctid.substring(sctid.length() - PARTITION_OFFSET, sctid.length() - 1);
    return "00".equals(partition) || "10".equals(partition);
  }

}
