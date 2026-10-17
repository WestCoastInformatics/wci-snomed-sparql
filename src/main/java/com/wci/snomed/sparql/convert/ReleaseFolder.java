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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * An RF2 release folder. The RF2 snapshot files are found either in a "Snapshot" sub-folder of the
 * given folder (the standard release package layout) or directly within the given folder.
 */
public final class ReleaseFolder {

  /** Pattern for the concept snapshot file, capturing the release version date. */
  private static final Pattern CONCEPT_FILE_PATTERN =
      Pattern.compile("sct2_Concept_Snapshot_[^_]*_(\\d{8})\\.txt");

  /** The folder containing the snapshot files. */
  private final Path snapshotDir;

  /** The release version date (yyyyMMdd), from the concept snapshot file name. */
  private final String versionDate;

  /**
   * Instantiates a new release folder.
   *
   * @param snapshotDir the snapshot dir
   * @param versionDate the version date
   */
  private ReleaseFolder(final Path snapshotDir, final String versionDate) {
    this.snapshotDir = snapshotDir;
    this.versionDate = versionDate;
  }

  /**
   * Resolve the snapshot files of a release folder.
   *
   * @param releaseDir the release folder (containing a "Snapshot" folder, or the snapshot files themselves)
   * @return the release folder
   * @throws IllegalArgumentException if no RF2 concept snapshot file can be found
   */
  public static ReleaseFolder resolve(final Path releaseDir) {
    if (!Files.isDirectory(releaseDir)) {
      throw new IllegalArgumentException("Release folder does not exist: " + releaseDir);
    }
    final Path snapshot = releaseDir.resolve("Snapshot");
    final Path snapshotDir = Files.isDirectory(snapshot) ? snapshot : releaseDir;
    final String versionDate = findConceptFile(snapshotDir)
        .map(p -> {
          final Matcher matcher = CONCEPT_FILE_PATTERN.matcher(p.getFileName().toString());
          return matcher.matches() ? matcher.group(1) : null;
        })
        .orElseThrow(() -> new IllegalArgumentException(
            "No sct2_Concept_Snapshot file found in " + snapshotDir));
    return new ReleaseFolder(snapshotDir, versionDate);
  }

  /**
   * Find the concept snapshot file.
   *
   * @param dir the dir to search
   * @return the concept file, if found
   */
  private static Optional<Path> findConceptFile(final Path dir) {
    try (Stream<Path> files = Files.walk(dir)) {
      return files.filter(p -> CONCEPT_FILE_PATTERN.matcher(p.getFileName().toString()).matches())
          .findFirst();
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Returns all reference set snapshot files in the release: der2_*Refset_*Snapshot*.txt, plus the OWL refsets
   * (sct2_sRefset_OWL*Snapshot*.txt) in the Terminology folder.
   *
   * @return the refset files
   */
  public List<Path> getRefsetFiles() {
    try (Stream<Path> files = Files.walk(snapshotDir)) {
      return files.filter(p -> {
        final String name = p.getFileName().toString();
        return (name.startsWith("der2_") || name.startsWith("sct2_")) && name.contains("Refset_")
            && name.contains("Snapshot") && name.endsWith(".txt");
      }).sorted().toList();
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Returns the concept snapshot file.
   *
   * @return the concept file
   */
  public Path getConceptFile() {
    return findConceptFile(snapshotDir).orElseThrow();
  }

  /**
   * Returns the description and text definition snapshot files (sct2_Description_Snapshot*.txt and
   * sct2_TextDefinition_Snapshot*.txt, one per language).
   *
   * @return the description files
   */
  public List<Path> getDescriptionFiles() {
    try (Stream<Path> files = Files.walk(snapshotDir)) {
      return files.filter(p -> {
        final String name = p.getFileName().toString();
        return (name.startsWith("sct2_Description_Snapshot") || name.startsWith("sct2_TextDefinition_Snapshot"))
            && name.endsWith(".txt");
      }).sorted().toList();
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Returns the language refset snapshot files (der2_cRefset_Language*Snapshot*.txt).
   *
   * @return the language refset files
   */
  public List<Path> getLanguageFiles() {
    return getRefsetFiles().stream().filter(p -> p.getFileName().toString().contains("cRefset_Language"))
        .toList();
  }

  /**
   * Returns the association refset snapshot files (der2_cRefset_Association*Snapshot*.txt).
   *
   * @return the association refset files
   */
  public List<Path> getAssociationFiles() {
    return getRefsetFiles().stream().filter(p -> p.getFileName().toString().contains("cRefset_Association"))
        .toList();
  }

  /**
   * Returns the snapshot dir.
   *
   * @return the snapshot dir
   */
  public Path getSnapshotDir() {
    return snapshotDir;
  }

  /**
   * Returns the version date (yyyyMMdd).
   *
   * @return the version date
   */
  public String getVersionDate() {
    return versionDate;
  }

}
