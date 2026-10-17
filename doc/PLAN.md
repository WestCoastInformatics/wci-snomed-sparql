# wci-snomed-sparql Implementation Plan

Source of requirements: `doc/TODO.md`. This file tracks progress; update the status
column and the notes as each step completes.

| # | Step | Status |
|---|------|--------|
| 1 | Project scaffold (Gradle, Spring Boot, checkstyle/spotbugs, Makefile skeleton) | ✅ done |
| 2 | Mode 1: RF2 release → OWL (toolkit conversion + `isMemberOf` refset and association annotations) | ✅ done |
| 3 | Generate the 20261001 test OWL file into `src/test/data` | ✅ done |
| 4 | Collect ECL spec examples ("Behaviour specification with examples") into a catalog | ✅ done |
| 5 | Mode 3: fetch expected results from the SNOMED browser and save to `src/test/resources/data` | ✅ done |
| 6 | Mode 2a: load OWL into an in-memory SPARQL store; REST endpoint skeleton with paging | ✅ done |
| 7 | ECL → SPARQL translator, core (constraint operators, refsets, wildcard, compound AND/OR/MINUS, dot notation) | ✅ done |
| 8 | ECL → SPARQL translator, advanced (refinements, groups, cardinality, reverse, concrete values, filters, etc.) | ✅ done |
| 9 | Unit test suite driven by saved expected results; iterate until all examples pass | ✅ done |
| 10 | Finish: web UI, README.md, AGENTS.md, Makefile clean/build/test/install/run, final checks | ✅ done |

## Step details

### 1. Project scaffold
- `build.gradle`, `settings.gradle`, Gradle wrapper (8.x as in `wci-common-java`).
- Spring Boot / Java versions aligned with `snomed-owl-toolkit` develop (Spring Boot 4.1.1, Java 25 — see decisions).
- Dependencies: `snomed-owl-toolkit` (from local maven / built from `../snomed-owl-toolkit`),
  `snomed-ecl-parser` (ANTLR-based, `../snomed-ecl-parser`), OWL API, Apache Jena (SPARQL engine).
- Copy `config/checkstyle` + `config/spotbugs` from `wci-common-java`.
- Single CLI entry point with three mutually exclusive modes, e.g. `--release <dir>`, `--owl <file>`, `--fetch-examples <version>`.

### 2. Mode 1: RF2 → OWL
- `--release=<dir> [--output=<file>]` (default output `snomed-inferred-<version>.owl`), via `ConvertRunner`.
- `ReleaseFolder`: snapshot files from `<dir>/Snapshot` or `<dir>` itself; version from the concept file name.
- `InferredOwlConverter`:
  1. Load RF2 with snomed-boot into the toolkit's `SnomedTaxonomy` (incl. descriptions/language refsets).
  2. Build the stated ontology *without* descriptions (toolkit's `ReasonerTaxonomyWalker` chokes on
     annotation properties), classify with ELK, run the toolkit's `RelationshipNormalFormGenerator`;
     `InferredRelationshipCollector` captures each concept's full inferred normal form. Logs the diff vs. the
     release's inferred file as a sanity check.
  3. Build the output ontology (stock stated ontology *with* descriptions: rdfs:label = FSN, skos:prefLabel /
     altLabel), remove all `OWLClassAxiom`s, add one inferred axiom per concept (EquivalentClasses if fully
     defined, else SubClassOf) via the toolkit's `OntologyService.createOwlClassAxiom`. The root is declared only.
     Property axioms (SubObjectPropertyOf, chains, etc.) are kept. Attribute concepts get SubClassOf too (punning).
  4. `isMemberOf` annotations for active members of every `der2_*Refset_*Snapshot*` / `sct2_*Refset_*Snapshot*` file whose
     referencedComponentId is a concept (partition 00/10), active or inactive.
  5. Association refsets (`der2_cRefset_Association*`) as annotation properties: `AnnotationAssertion(:refset :source
     :target)` per active member whose referenced component and target are concepts (concept history only); inactive concepts referenced by 4–5 get `owl:deprecated` + FSN label (not classes).
  6. Save in SNOMED functional syntax with the toolkit's copyright header.
- Test fixture `src/test/resources/rf2/SnomedCT_MiniRF2_20190131` (toolkit's mini RF2 + a primitive concept whose
  stale RF2 inferred row ELK must correct, + a simple refset, + an inactive concept with a SAME AS association). `InferredOwlConverterTest`.
- JVM needs `--add-opens java.base/java.lang=ALL-UNNAMED` (OWL API 4 / Guice) — set for tests, bootRun, and
  as `Add-Opens` in the bootJar manifest. ELK needs `log4j-over-slf4j`.

### 3. Test OWL file
- `make test-data` (optionally `VERSION=... RELEASE_DIR=...`) builds the jar and writes
  `src/test/data/snomed-inferred-<VERSION>.owl` from the RF2 release folder.
- 20261001: 276,542,738 bytes, sha256 `6ef95d16b57e...0ecb`; output is deterministic (byte-identical across runs).
- `TestDataOwlFileTest` checks the file exists and has the expected version IRI (header only).
- NOTE: at 277 MB the file exceeds GitHub's 100 MB per-file limit — when this goes into git, use Git LFS or
  switch to `.owl.gz` (deferred per decision below).

### 4. ECL example catalog
- `src/main/resources/ecl-examples.json` (on the classpath so mode 3 can read it from the jar), loaded by
  `EclExampleCatalog` into `EclExample` records: id (e.g. `6.2-05`), section, heading, ecl (brief), eclLong,
  features (heuristic tags), executable, note, source.
- Built from the GitBook markdown of spec sections 6.1–6.12 by `tools/extract_ecl.py` + `tools/curate_ecl.py`
  (see `tools/README.md`); raw extraction saved in `tools/ecl-examples-raw.json`.
- 218 examples, 214 executable. Not executable: 6.4-09 (spec shows as invalid), 6.8-01 (filter fragment),
  6.11-03/04 (HISTORY templates).
- Spec typos corrected (each has a `note`): 6.3-18 `[1..]`→`[1..*]`, 6.6-01 comment asterisks, 6.8-37/38 and
  6.9-12/13 missing `)`.
- `EclExampleCatalogTest` parses every executable example with snomed-ecl-parser. Long syntax is reference-only
  (parser and browser support brief syntax only). Known parser gaps (asserted to still fail): 6.1-25 `^R`,
  6.1-27 `LOINC#"..."`, 6.8-44 `active = "*"`, 6.8-39 / 6.9-14 `effectiveTime = ""` (NumberFormatException).
- Cross-check with `doc/ECL as SPARQL.docx`: 53/56 of its examples are in the catalog; added `S-01` (precoordinated
  focus + refinement); its two concrete-value examples used placeholder ids and are superseded by 6.2-08/09/10.

### 5. Mode 3: expected results
- The public browsers refuse programmatic access (snomedbrowser.org: "IP blocked"; browser.ihtsdotools.org: denied,
  reason=browser). A short attempt via the browser UI in Chrome was abandoned (slow, heavy queries 504).
- Instead: local **Snowstorm 11.0.0** + Elasticsearch 8.19.8 in Docker (`snowstorm/docker-compose.yml`,
  `snowstorm/README.md`), API on 127.0.0.1:8081, pinned to linux/arm64 (the user's `DOCKER_DEFAULT_PLATFORM=linux/amd64`
  breaks Elasticsearch: no seccomp under emulation). Import of the 20261001 snapshot: 15 min.
  Snowstorm 11 is newer than the public browser's ECL 2.0 engine, but still lacks some ECL 2.2 syntax (see README).
- `--fetch-examples=<version>` (`FetchExamplesRunner` → `SnowstormClient`, `ExpectedResultStore`): one request at a
  time (`returnIdOnly`, searchAfter paging, 10k pages), full id sets, `--delay-ms` (default 0), `--timeout-sec`
  (600), `--skip-existing`, `--max-ids`. Request failures are recorded, not thrown.
- Makefile: `snowstorm-up`, `snowstorm-import`, `expected-results`, `snowstorm-down`.
- 20261001 results in `src/test/resources/data/20261001/<id>.json` (53 MB): 213 files, 171 evaluated (all with full
  ids; 29 empty), 42 rejected by Snowstorm (status + message recorded). Local `*` = 387,630 = our OWL's concept count.
- README.md "ECL examples set aside" lists everything without usable expected results (47 = 5 not executable
  + 42 Snowstorm errors), plus parser gaps and spec corrections.
- Tests: `SnowstormClientTest` (mock server), `ExpectedResultsDataTest` (data consistent with catalog).

### 6. Mode 2a: SPARQL store + REST
- `store.SnomedStore.load(owl)`: OWL API loads the functional syntax, saves it as Turtle through a pipe straight
  into a Jena in-memory graph (standard OWL→RDF mapping), then the OWL API ontology is dropped.
- Helper triples: class axioms map to blank nodes (`:c rdfs:subClassOf [owl:intersectionOf (:p ...)]`), so each
  concept's named parents are also asserted as `:c rdfs:subClassOf :p` (entailed; 538,438 added for 20261001).
  Hierarchy queries then use plain `rdfs:subClassOf+`. Blank class expressions are also `a owl:Class`, so queries
  must keep `FILTER(isIRI(..))` or rely on `selectCodes` dropping non-SNOMED bindings.
- 20261001: 33s load (7s OWL API + 24s Jena), 10.59M triples, ~4 GB RSS. `<404684003` via
  `rdfs:subClassOf+` = 129,411 ids, identical to Snowstorm, in ~0.4s → no transitive closure index needed (yet).
- `store.selectCodes(sparql, timeout)` returns the distinct codes bound to `?concept` (or the first variable),
  sorted numerically; names are looked up per page (FSN = `rdfs:label`; `skos:prefLabel` also available).
- REST (profile `serve`, `rest` package): `POST /ecl?limit=&offset=` (text/plain ECL) →
  `{total, limit, offset, items: [{code, name}]}`; `POST /ecl/sparql` → generated SPARQL; `POST /sparql` raw
  SPARQL (debugging); `GET /status`. limit default 50, max 10000, 0 = count only. Errors as RFC 9457 problem
  details: invalid ECL/SPARQL/paging 400, unsupported ECL 501, timeout (`query.timeout-sec`, default 300) 503.
- `ecl.EclTranslator`: parses with snomed-ecl-parser; supports only `*` and a single concept so far (steps 7–8).
- Build: the BOM excludes libthrift from jena-arq, so it is added explicitly (RIOT needs it at runtime).
- `make serve [OWL=...]`.
- Tests: `SnomedStoreTest`, `EclTranslatorTest`, `EclControllerTest` (MockMvc standalone) on the mini release
  (`MiniStore`); `TestDataStoreTest` on the full OWL (`TestDataStore`, loaded once per JVM, shared by step 9).

### 7. ECL → SPARQL translator, core
- `ecl.EclTranslator` walks the snomed-ecl-parser domain objects; each expression constraint becomes a group graph
  pattern binding a variable; the query is `SELECT DISTINCT ?concept`.
  - concept: `VALUES ?v { sct:C } ?v a owl:Class` (unknown/inactive concepts match nothing); `*`: `?v a owl:Class`
    + `isIRI` (inactive concepts are not classes, so never match).
  - `<` `rdfs:subClassOf+`, `<<` `*`, `<!` (one step), `<<!` `?`; ancestors are the reverse + `FILTER(isIRI)`
    (blank class expressions). Self operators on a constant also require `sct:C a owl:Class`.
  - `^ R`: `?v sct:isMemberOf R` (includes inactive members, as Snowstorm). `^ *` supported (6.1-24 still set aside:
    no Snowstorm result).
  - Wildcard with operators: `<*`/`<!*` = concepts with a named parent, `>*`/`>!*` = concepts with a child.
  - Nested focus: `{ SELECT DISTINCT ?x WHERE { ... } }` then the path from `?x`.
  - AND: join; OR: UNION; MINUS: SPARQL `MINUS` (right side evaluated once; FILTER NOT EXISTS re-evaluated it per
    row: 6.12-02 21s → <1s).
  - Dot notation: `?s ATTRIBUTE_PATH ?r . ?r owl:onProperty A ; owl:someValuesFrom ?v`, where the path reaches each
    attribute restriction of the concept's own class axiom, ungrouped or inside a role group (609096000).
- Performance: Jena substitutes bound variables into the right side of a join (index join), even into subqueries,
  so `< 91723000 AND (X . attr)` re-ran the dotted subquery per anatomy concept (timeout). Fix: in AND, complex
  parts go first; later complex parts are wrapped as `{ SELECT (?c AS ?v) WHERE { ... } }` (renaming blocks the
  substitution → evaluated once and hash-joined): 6.2-13 300s+ → 0.2s.
- Top/bottom (`!!>`, `!!<`): the parser drops the operator, so the translator rejects them (501); detected in the
  ECL text outside terms/comments/strings.
- `EclService` keeps the last 20 results (LRU by SPARQL) so paging does not re-run a query.
- Results: all 39 evaluated examples in scope match Snowstorm exactly (ids), each ≤1.5s; the rest (refinements,
  filters, history, member fields) are 501 for step 8.
- Tests: `EclTranslatorTest` (mini release: every operator, wildcard, inactive, compound, dotted, errors);
  `EclExamplesTest` (parameterized over the catalog against the 20261001 OWL: count then ids; unsupported or no
  expected result → skipped; 39 pass / 174 skipped).

### 8. ECL → SPARQL translator, advanced
- Generation moved to `ecl.SparqlGenerator` with two modes: **set** (enumerate matches) and **bound** (the variable
  is already bound — inside FILTER EXISTS or as a refinement value — so navigate from it: cheap per-row checks).
  Bound-mode MINUS is FILTER NOT EXISTS (MINUS shares no variable once substituted).
- Refinements: focus, then `FILTER(<condition>)` on each focus concept. Attribute = (NOT) EXISTS over
  `?c sct:hasRestriction ?r . ?r owl:onProperty A ; owl:someValuesFrom ?val` (+ value check in bound mode);
  `!=` = value NOT in the set; `*` attribute excludes the role group. Cardinality [min..max]: EXISTS min pairwise
  distinct restriction nodes && NOT EXISTS max+1 (≤5). Groups `{ }`: `?c sct:hasRoleGroup ?g` and the attribute set
  evaluated within `?g` (members via `(owl:intersectionOf/rdf:rest*/rdf:first)?`), group cardinality on distinct
  `?g`. Group 0 (ungrouped) attributes are not a group. Reverse `R`: `?r owl:someValuesFrom ?c`, `?s
  sct:hasRestriction ?r`, value check on `?s`. Concrete values: `owl:hasValue` + numeric/string/boolean FILTER.
  AND/OR of refinements and attribute sets as `&&`/`||` (parser quirk: empty conjunction list next to a disjunction).
- History supplements: `{ X } UNION { X(?x) . ?c ?assoc ?x }` with `?assoc` = SAME AS (MIN), + REPLACED BY, WAS A,
  PARTIALLY EQUIVALENT TO (MOD), or `< 900000000000522004` (MAX / none / `(ecl)` ∩ that).
- Concept filters: definitionStatus(Id) (`owl:equivalentClass` = defined), active (class vs `owl:deprecated`).
  Member filters: `referencedComponentId`, and `targetComponentId` on association refsets (`?c R ?t`).
- Store index (in memory, `SnomedStore.addRestrictionIndex`): `sct:hasRestriction` / `sct:hasRoleGroup` (1.1M
  triples, 1s). Reverse navigation of the blank-node structures by property path was pathological in Jena (a single
  value → concepts lookup timed out at 60s); with the index, reverse attributes 6.2-12/15 run in ~2s.
- Descriptions & concept metadata (user decision 2026-10-16: "annotated labels" + "concept metadata only"):
  the converter no longer uses the toolkit's description annotations; `convert.DescriptionAnnotator` writes each
  active description (and text definition) as the toolkit does (FSN `rdfs:label`, preferred-in-any-dialect synonym
  `skos:prefLabel`, other synonyms `skos:altLabel`, definitions `skos:definition`; language tag = language code)
  with axiom annotations `:descriptionId`, `:typeId`, `:moduleId`, `:effectiveTime`, `:caseSignificanceId`,
  `:<languageRefset> :<acceptability>`; and concept `:moduleId` / `:effectiveTime`. Inactive referenced concepts
  get the same. 20261001: 1,437,997 descriptions, OWL 919 MB / 5.0M axioms, 93s, 5.7 GB; store 28.0M triples,
  70s load; heap for tests/serve raised to 20 GB.
- Description filters: `FILTER EXISTS { ?d owl:annotatedSource ?c ; sct:descriptionId ?id ; owl:annotatedTarget
  ?term ; sct:typeId ?type ... }` with term match (each search word = case-insensitive word prefix, any order),
  wild (whole-term regex), type / typeId, language (`lang()`), dialect alias (fixed alias→refset map) / dialectId
  with acceptability, moduleId, effectiveTime, id; active = 0 matches nothing (active descriptions only). Concept
  moduleId / effectiveTime filters from the concept annotations.
- Results: 162 / 162 supported evaluated examples match Snowstorm (concept ids; Snowstorm's HISTORY-MAX also
  returns description 492714018, compared concept-only). All < 10s; ≥2s listed in README "Slow examples"
  (user: slow is OK for now if < 2 min).
- Not supported (README): member field filters other than referencedComponentId / association targetComponentId,
  member moduleId/effectiveTime/active (9 examples; refset member fields not in the OWL — deferred by the user);
  top/bottom of set.
- README "How the OWL file differs" now has diff-highlighted examples (active concept, inactive concept with
  association, root, declarations) — user request.

### 9. Test suite
- `EclExamplesTest`: every executable catalog example has exactly one expected outcome, so behaviour changes fail:
  known parser gap (translation fails) / `UNSUPPORTED` (UnsupportedEclException with a reason) / Snowstorm result
  (count, then ids) / no Snowstorm result → `EQUIVALENT` (= another example's expected ids, e.g. "X OR ^ 700043003"
  = X) or reviewed `BASELINE` count (verified against RF2: 6.3-22 = 81, description ids 6.8-45..48, ...).
  `CONCEPT_IDS_ONLY` for the Snowstorm HISTORY description-id quirk. `testOutcomeLists` checks the lists against
  the catalog and expected results.
- Fixed while making outcomes explicit: alternate identifiers (6.1-26 `LOINC#...`) gave HTTP 500 (null focus) →
  501; `{{ M referencedComponentId = <inactive concept> }}` matched nothing (constant check required a class).
- Additional examples (user request): P-01..P-15 from the snomed-ecl-parser unit tests (develop) that add
  something beyond the spec; A-01..A-21 International adaptations of spec examples whose content is not in the
  International edition (700043003 → 447562003 ICD-10 map, 445238008 → 55342001, AU dialect → GB, US module →
  core, Hay asthma → SAME AS member). Added in `tools/curate_ecl.py`; expected results fetched with
  `--skip-existing --timeout-sec=120` (Snowstorm is slow on some dialect filters). A-20 is rejected by Snowstorm
  (inactive concept in the ECL) → baseline 1.
- 6.4-18 (and its adaptation A-11) marked not executable: the spec says AND/OR mixed in a refinement without
  brackets is not valid (parser and Snowstorm accept it with different meanings).
- `dialect != X` now follows Snowstorm: the description is in some *other* language refset (descendant of
  900000000000506000) with the given acceptability (P-13: 1 → 2,839).
- Results: catalog 254 examples (247 executable): 192 match Snowstorm, 4 match concept-only (HISTORY quirk),
  others explicit (unsupported 19, parser gaps 5, equivalent 5, baseline 22). `make test`: 530 tests, 0 skipped.
- `doc/ECL as SPARQL.docx` (moved from the project root at the user's request; references updated).

### 10. Finish
- Web UI: `src/main/resources/static/index.html` (single self-contained page, as the user prefers): catalog
  drop-down (`GET /examples`), ECL box, Go (Ctrl/⌘+Enter), generated SPARQL, results table (code, FSN), Previous /
  Next, page size 10/50/100/500/1000/10000. Checked in Chrome (6.2-05: 11 results, paging).
- README.md rewritten: quick start (download release → `make owl` → `make serve` → UI), modes, REST API with curl
  examples (paging, `/ecl/sparql`), UI, ECL→SPARQL mapping table, OWL differences (diff-highlighted examples),
  unsupported, slow examples, additional examples, set-aside examples, tests, building, documents, license.
- AGENTS.md: layout, build/test, regenerating the test data (test OWL + local Snowstorm workflow), the test contract,
  lessons learned (Jena substitution, bound/set modes, reverse paths, parser quirks, Snowstorm semantics), open items.
- Makefile: `make owl` (OWL at the top level), `make serve` (the generated OWL if present, else the test OWL),
  `.PHONY`; clean/build/test/install/run checked.
- 6.1-24 `^*` made executable (baseline 539,490, verified against RF2).
- Moved `TODO.md`, `PLAN.md` (and earlier the docx) to `doc/`. Repo renamed to `wci-snomed-sparql` (user): Gradle
  project, jar, Spring app name, Makefile SERVICE, docs, Snowstorm compose project/containers (the Elasticsearch
  volume was copied to `wci-snomed-sparql-snowstorm_elastic`; the old `snomed-sparql-snowstorm_elastic` is left
  for the user to remove); directory renamed to `wci-snomed-sparql`.
- Final: `make clean build test install` — 532 tests, 0 skipped, checkstyle 0 warnings, spotbugs clean.

## To revisit at the end
- ~~**6.1-24 `^*`**~~ resolved in step 10: supported; Snowstorm times out, so it is tested against a baseline checked
  against RF2 (539,490 = every concept, active or inactive, that is an active member of any refset).
- **Top/bottom of set (`!!>`, `!!<`)**: snomed-ecl-parser drops the operator (not in its `Operator` enum), so the
  translator rejects them (501). Equivalent: `!!> E` = `E MINUS < E`, `!!< E` = `E MINUS > E` (cf. 6.12-02/04).
- **Refset member fields** (map targets etc.) are not in the OWL file: 9 examples unsupported (user decision: concept
  metadata only for now).
- **Slow refinements** over large focus sets (README "Slow examples", all < 10s).
- **Storing the 919 MB test OWL** (git-ignored today: `*owl`): Git LFS or gzip.

## Open questions / decisions
- **Inferred view**: classify the stated OWL with ELK during mode 1 and materialize the inferred
  hierarchy (and inferred attribute relationships) so SPARQL results match the browser. (decided 2026-10-16)
- **OWL content**: class axioms in the output are the *inferred* normal form (stated axioms are not in the file),
  in the toolkit's OWL shape, so the draft doc's SPARQL patterns apply unchanged. (decided 2026-10-16)
- **Serialization**: OWL functional syntax (readable, same as toolkit); mode 2 loads it via OWL API and converts
  to RDF for Jena. (decided 2026-10-16)
- **Test OWL storage**: store as plain `.owl` in `src/test/data` for easy inspection; revisit `.gz`
  once the size is known. (decided 2026-10-16)
- **Versions**: Spring Boot 4.1.1 / Spring 7.0.9 / Java 25, matching `snomed-parent-bom` 5.0.0-SNAPSHOT used by
  the `develop` branches of snomed-owl-toolkit (7.0.0-SNAPSHOT, "PIP-1186 Spring 7 upgrade") and snomed-ecl-parser
  (5.0.0-SNAPSHOT, adds ECL 2.1/2.2 + ANTLR 4.13.2). The released toolkit 6.0.1 / BOM 4.0.x are still on Boot 3.5
  (EOL), so we deliberately track develop. Gradle 9.1.0 wrapper (Java 25 needs 9.1+). (decided 2026-10-16)
- **Build prerequisites**: install both develop branches to the local maven repo with
  `mvn install -DskipTests -Ddependency-check.skip=true` (the OWASP check otherwise downloads the whole NVD database).
  They were built from git worktrees of `origin/develop` so the sibling checkouts (on master) are untouched.
  When upstream releases these versions, switch to the released artifacts.
- **BOM**: `snomed-parent-bom` is applied as a Gradle `platform()` on `implementation` only, so its pins don't leak
  into checkstyle/spotbugs classpaths.
- **License**: Apache 2.0 (`LICENSE.txt`), with "Copyright <year> West Coast Informatics" in the Java file headers
  (for now). Headers come from `config/license-header.txt` via the Spotless plugin (runs before compileJava); the
  hierynomus license plugin used by wci-common-java breaks under Spring Boot 4's Gradle plugin (Spring 7 classpath
  conflict). (decided 2026-10-16)
- **SPARQL engine**: Apache Jena (in-memory), since OWL API itself has no SPARQL support.

## Progress log
- 2026-10-16: Plan drafted.
- 2026-10-16: Step 1 done. Gradle 9.1.0 wrapper, Java 25 toolchain, Spring Boot 3.5.14, Jena 5.5.0, OWL API 4.1.3,
  ELK 0.4.3. `Application` + `RunMode` dispatch (`--release`, `--owl`, `--fetch-examples`; only `--owl` starts
  the web server; each mode activates a same-named Spring profile). Makefile with clean/build/test/install/run.
  checkstyle + spotbugs from wci-common-java pass clean.
- 2026-10-16: Upgraded to Spring Boot 4.1.1 (toolkit 7.0.0-SNAPSHOT, ecl-parser 5.0.0-SNAPSHOT, BOM 5.0.0-SNAPSHOT);
  `spring-boot-starter-web` → `spring-boot-starter-webmvc`. Build/tests/checks pass; serve mode starts Tomcat.
- 2026-10-16: Apache 2.0 license + WCI copyright headers (Spotless).
- 2026-10-16: Step 2 done. Full 20261001 International conversion: 387,630 concepts, ELK + normal form
  reproduces the release inferred relationships exactly (0 added / 0 removed), 387,629 inferred class axioms,
  980,879 isMemberOf annotations across 16 refsets, 277 MB / ~3.6M lines, 78s, ~7 GB RSS (run with -Xmx20g).
  Noisy toolkit/ELK warnings silenced in application.properties.
- 2026-10-16: Step 3 done. `make test-data` → `src/test/data/snomed-inferred-20261001.owl` (277 MB, deterministic).
- 2026-10-16: Step 4 done. ECL example catalog: 218 examples (214 executable) from spec 6.1–6.12 + 1 supplementary.
- 2026-10-16: Step 5 done. Expected results from local Snowstorm 11.0.0: 171 evaluated / 42 errors; 6.1-24 `^*` set aside.
- 2026-10-16: Step 6 done. Jena store from the OWL (33s, 10.6M triples), named-parent helper triples, REST endpoints with paging; `<404684003` matches Snowstorm exactly.
- 2026-10-16: Converter change (user request): historical/association refsets are added as annotation properties
  (`AnnotationAssertion(:refset :source :target)`, 174,071 for 20261001 in 10 association refsets); `isMemberOf` now
  includes inactive concept members (Snowstorm's `^` does: e.g. `^ 900000000000527005` = 38,981, all inactive),
  1,414,286 total; the 151,860 inactive concepts referenced are `owl:deprecated` + FSN `rdfs:label`, not classes.
  Test OWL regenerated: 340 MB, 96s; store 10.96M triples, 38s load. README documents the changes vs. the stock OWL.
- 2026-10-16: Step 7 done. Core ECL → SPARQL (hierarchy, wildcard, member of, AND/OR/MINUS, nesting, dot notation):
  39/39 evaluated examples in scope match Snowstorm; join-order fix for Jena subquery substitution.
- 2026-10-16: Step 8 (part 1): refinements, groups, cardinality, reverse, concrete values, history supplements,
  concept definitionStatus/active filters, association targetComponentId member filters; restriction index.
  112/112 supported evaluated examples match. Description/module/effectiveTime/member-field filters pending a data
  decision. Associations confirmed concept-only (4,456 REFERS TO description members skipped).
- 2026-10-16: Step 8 done. Descriptions as annotated labels + concept module/effectiveTime in the OWL (919 MB);
  description, concept module/effectiveTime and description id filters. 162/162 supported evaluated examples
  match Snowstorm; 9 member-field examples deferred.
- 2026-10-16: Step 9 done. Explicit outcome per example (no skips), P-01..P-15 (parser tests) and A-01..A-21
  (International adaptations) with Snowstorm results; fixes for alt identifiers (500), inactive member
  referencedComponentId, dialect !=. 530 tests pass. Docx moved to doc/. README quick-start + UI added to step 10.
- 2026-10-16: Step 10 done. Web UI, README (quick start, curl, mapping), AGENTS.md, `make owl`, `^*` baseline,
  docs moved to doc/, project renamed to wci-snomed-sparql. 532 tests pass.
