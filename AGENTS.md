# AGENTS.md

Notes for AI coding agents (and people) maintaining this project. Start with `README.md` (what it does, how to run
it) and `doc/PLAN.md` (decisions and history).

## Project in one paragraph

`wci-snomed-sparql` implements SNOMED CT ECL with SPARQL. Mode `--release` converts an RF2 release to an OWL file of the
inferred view (snomed-owl-toolkit + ELK) plus `isMemberOf`, association, concept-metadata and annotated-description
annotations. Mode `--owl` loads that file into an in-memory Jena store (adding index triples) and serves
`POST /ecl` (ECL → SPARQL → concepts, paged) and a one-page UI. Mode `--fetch-examples` records the expected result
of every catalog example from a local Snowstorm, for the unit tests.

## Layout

| Path | Contents |
|---|---|
| `src/main/java/com/wci/snomed/sparql/` | `Application` (mode from args → Spring profile `convert` / `serve` / `fetch-examples`), `RunMode` |
| `.../convert/` | `InferredOwlConverter` (pipeline), `InferredRelationshipCollector`, `RefsetMembershipReader` (members, associations, concepts), `DescriptionAnnotator`, `ReleaseFolder` |
| `.../store/` | `SnomedStore`: OWL API → Turtle pipe → Jena; adds named-parent `rdfs:subClassOf`, `sct:hasRestriction`, `sct:hasRoleGroup` index triples |
| `.../ecl/` | `EclTranslator` (parse + top/bottom check), `SparqlGenerator` (all ECL → SPARQL generation), `UnsupportedEclException` |
| `.../rest/` | `EclController` (OpenAPI/springdoc annotations: keep them up to date when changing endpoints), `EclService` (paging, LRU result cache), `ErrorHandler` (problem details), `ServeConfiguration` (store, translator, OpenAPI info) |
| `.../examples/` | Catalog (`EclExampleCatalog`, `ecl-examples.json`), `SnowstormClient`, `FetchExamplesRunner`, `ExpectedResultStore` |
| Swagger UI | `/swagger-ui/index.html`, OpenAPI JSON at `/v3/api-docs` (springdoc 3.x, the Spring Boot 4 line, version from the SNOMED BOM) |
| `src/main/resources/static/index.html` | The UI — keep it a single self-contained page (or index.html + index.js + index.css) |
| `src/test/resources/rf2/SnomedCT_MiniRF2_20190131` | Tiny RF2 release for fast tests (`MiniStore`) |
| `src/test/data/snomed-inferred-20261001.owl` | Full test OWL (919 MB, git-ignored; `make test-data`) — `TestDataStore` loads it once per test JVM |
| `src/test/resources/data/20261001/` | Expected results from Snowstorm, one JSON per example |
| `tools/` | `extract_ecl.py` / `curate_ecl.py`: build `ecl-examples.json` from the spec (see `tools/README.md`) |
| `snowstorm/` | docker-compose for the local Snowstorm used to generate expected results |
| `doc/` | `TODO.md` (requirements), `PLAN.md` (plan, decisions, progress log), `ECL as SPARQL.docx` (draft mapping) |

## Build and test

- Java 25 (the user's shell sets it up with `source ~/.bashrc25`), Gradle wrapper 9.1. snomed-owl-toolkit and
  snomed-ecl-parser **develop** branches must be installed to `~/.m2` (`mvn install -DskipTests
  -Ddependency-check.skip=true`); the SNOMED BOM is applied as a Gradle `platform()`.
- `make build` / `make test` (all tests + checkstyle + spotbugs, ~5 min, needs the test OWL and ~20 GB heap) /
  `./gradlew test --tests '*EclTranslatorTest'` for the fast mini-release tests.
- Spotless adds the Apache 2.0 / WCI license header on compile; checkstyle (warnings must stay at 0) and spotbugs
  (must pass) configs are in `config/`. Match the existing style: Javadoc on every member, `final` parameters,
  `package-info.java` per package, 120-column lines.
- Memory: conversion ~6 GB, store ~28M triples (run with `-Xmx20g`). Don't rebuild `bootJar` while a server is
  running from that jar (lazy class loading then fails with NoClassDefFoundError) — copy the jar first.

## Regenerating the test data (new release or converter change)

1. **Test OWL** (after any converter change, or for a new release): put the RF2 release at the top level, then
   `make test-data` (or `make test-data VERSION=... RELEASE_DIR=...`). Update `TestDataOwlFileTest.VERSION` and
   `ExpectedResultsDataTest.DATA_DIR` for a new version.
2. **Expected results**, from a local Snowstorm loaded with the same release (see `snowstorm/README.md`):
   ```bash
   make snowstorm-up                       # Elasticsearch 8.19 + Snowstorm 11.0.0 in Docker, API on 127.0.0.1:8081
   make snowstorm-import VERSION=20261001  # zip the Snapshot, import as MAIN/2026-10-01 (~15 min)
   make expected-results VERSION=20261001  # every executable example → src/test/resources/data/20261001/<id>.json
   make snowstorm-down                     # data is kept in a Docker volume
   ```
   - Add `--skip-existing` (via `java -jar ... --fetch-examples=<version> --skip-existing`) to fetch only new
     examples, and `--timeout-sec=120` to give up on slow ones (failures are recorded, not thrown).
   - The containers are pinned to `linux/arm64`: the user's `DOCKER_DEFAULT_PLATFORM=linux/amd64` makes
     Elasticsearch fail (no seccomp under emulation). The image is Jib-built: pass JVM options via
     `JAVA_TOOL_OPTIONS`, not a custom entrypoint.
   - No delay is needed locally. The public SNOMED browsers block programmatic access — don't use them.
3. Run `make test`. `EclExamplesTest` then tells you, per example, what changed.

## The test contract (`EclExamplesTest`)

Every executable catalog example has exactly one outcome; a behaviour change must fail a test:

- Snowstorm evaluated it → our concept ids must equal Snowstorm's (count first, then ids). `CONCEPT_IDS_ONLY` covers
  Snowstorm's HISTORY quirk (it returns description 492714018).
- `EclExampleCatalogTest.KNOWN_PARSER_GAPS` → translation must still fail.
- `UNSUPPORTED` → must throw `UnsupportedEclException` containing the reason.
- No Snowstorm result → `EQUIVALENT` (equal to another example's ids) or `BASELINE` (a count verified against the
  RF2 files — verify any new baseline the same way, e.g. with a short Python script over the release).

When you add support for a feature, move its examples out of `UNSUPPORTED`; when you add catalog examples, edit
`tools/curate_ecl.py` (P-xx parser-test examples, A-xx International adaptations) and regenerate
`ecl-examples.json` with `python3 tools/curate_ecl.py tools/ecl-examples-raw.json > src/main/resources/ecl-examples.json`
(existing examples must not change — diff the output), then fetch their expected results.

## Things learned the hard way

- **Jena join substitution**: Jena substitutes bound variables into the right side of a join, even into subqueries.
  A complex AND part is therefore wrapped as `{ SELECT (?c AS ?v) WHERE { ... } }` (renaming blocks substitution →
  evaluated once, hash-joined), simple parts go last, and set-mode MINUS is a SPARQL `MINUS` (evaluated once).
- **Bound vs set mode** (`SparqlGenerator`): inside FILTER EXISTS / refinement values the variable is bound, so
  patterns must navigate *from* it; MINUS there must be FILTER NOT EXISTS (a substituted MINUS shares no variable).
- **Reverse navigation of OWL blank-node structures** (value → concept via `rdf:rest*` paths) is pathologically slow
  in Jena; that's why the store adds `sct:hasRestriction` / `sct:hasRoleGroup`. Navigate forward from concepts.
- **Parser quirks** (snomed-ecl-parser 5.0.0-SNAPSHOT): top/bottom `!!>`/`!!<` are silently dropped (we reject them by
  scanning the text); an empty conjunction list can accompany a disjunction; single concepts in filters
  (`definitionStatusId`, `moduleId`) arrive as sub-expressions; AND/OR mixed without brackets is accepted though the
  spec says it is invalid. The local `../snomed-ecl-parser` checkout is on master (4.0.0) — inspect the 5.0.0 jar
  in `~/.m2` (`javap`) for the real API.
- **Snowstorm semantics we follow**: `^ refset` includes inactive member concepts; `*` and the hierarchy are active
  only; `dialect != X` means "in some other language refset (with the acceptability)"; HISTORY profiles: MIN = SAME
  AS, MOD = + REPLACED BY, WAS A, PARTIALLY EQUIVALENT TO, MAX = all `< 900000000000522004`.
- The OWL label language tag is the plain language code (dialect and acceptability are axiom annotations); only
  active descriptions are written, so `{{ D active = 0 }}` matches nothing.
- macOS has no `timeout` command; long-running fetches should run in the background.

## Open items

See `doc/PLAN.md` ("To revisit at the end" and the step notes): refset member fields (map targets etc.) are not in
the OWL file (9 examples unsupported), top/bottom of set, slow refinements over large focus sets (README "Slow
examples"), and how to store the 919 MB test OWL (Git LFS or gzip).
