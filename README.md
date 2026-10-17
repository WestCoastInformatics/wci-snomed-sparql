# wci-snomed-sparql: SNOMED CT ECL to SPARQL

A SPARQL-based implementation of the SNOMED CT [Expression Constraint Language
(ECL)](https://docs.snomed.org/snomed-ct-specifications/snomed-ct-expression-constraint-language).

An RF2 release is converted to an OWL ontology of the inferred view (using
[snomed-owl-toolkit](https://github.com/IHTSDO/snomed-owl-toolkit) and the ELK reasoner), extended with reference set
memberships, historical associations and annotated descriptions. The OWL file is loaded into an in-memory
[Apache Jena](https://jena.apache.org/) store, and a REST service (with a small web UI) parses ECL with
[snomed-ecl-parser](https://github.com/IHTSDO/snomed-ecl-parser), translates it to SPARQL, and returns the matching
concepts with paging.

Every example from the ECL specification's "Behaviour specification with examples", plus examples from the
snomed-ecl-parser tests and International edition adaptations, is a unit test checked against the results of
[Snowstorm](https://github.com/IHTSDO/snowstorm) for the same release: 192 of them return exactly the same concepts
(see [ECL to SPARQL: not yet supported](#ecl-to-sparql-not-yet-supported) for the rest).

## Quick start

Requires Java 25 and ~24 GB of RAM (the conversion and the server each use up to 20 GB of heap).

1. **Download the SNOMED CT 20261001 International release** (RF2, from the
   [SNOMED International MLDS](https://mlds.ihtsdotools.org/) — a license is required) and unpack it at the top level of
   this project, so that `SnomedCT_InternationalRF2_PRODUCTION_20261001T120000Z/Snapshot` exists.
2. **Generate the OWL file** (~2 min; writes `snomed-inferred-20261001.owl`, ~920 MB):

   ```bash
   make owl
   # i.e. java -Xmx20g -jar build/libs/wci-snomed-sparql-1.0.0-SNAPSHOT-exec.jar \
   #        --release=SnomedCT_InternationalRF2_PRODUCTION_20261001T120000Z --output=snomed-inferred-20261001.owl
   ```

3. **Run the server** to load the OWL file and expose the API on port 8080 (~70 s to load):

   ```bash
   make serve
   # i.e. java -Xmx20g -jar build/libs/wci-snomed-sparql-1.0.0-SNAPSHOT-exec.jar --owl=snomed-inferred-20261001.owl
   ```

4. **Try it** in the UI at http://localhost:8080/index.html — choose an example (or type ECL), press **Go**, and see
   the generated SPARQL and a page of results — or with curl (see [REST API](#rest-api)).

For another release, set `VERSION` and `RELEASE_DIR`, e.g.
`make owl serve VERSION=20260601 RELEASE_DIR=path/to/SnomedCT_InternationalRF2_PRODUCTION_20260601T120000Z`.

## Running

The executable jar (`build/libs/wci-snomed-sparql-<version>-exec.jar`, built by `make build`) has three modes, chosen by
the command line:

| Mode | Command line | Make |
|---|---|---|
| Convert an RF2 release to OWL | `--release=<release dir> [--output=<owl file>]` | `make owl` |
| Serve the ECL REST API and UI | `--owl=<owl file> [--server.port=8080] [--query.timeout-sec=300]` | `make serve [OWL=...]` |
| Fetch expected test results from Snowstorm | `--fetch-examples=<version> [--snowstorm-url=...] [--skip-existing] [--timeout-sec=600]` | `make expected-results` |

- `--release` accepts the release folder or its `Snapshot` folder. The output defaults to
  `snomed-inferred-<version>.owl`. Use `-Xmx20g`.
- `--fetch-examples` evaluates every executable catalog example on a local Snowstorm and saves the results to
  `src/test/resources/data/<version>/` (see [Tests](#tests)).

## REST API

| Request | Description |
|---|---|
| `POST /ecl?limit=50&offset=0` | Evaluate ECL (request body, `text/plain`); returns `{total, limit, offset, items: [{code, name}]}` in numeric code order. `limit` 0–10000 (0 = count only) |
| `POST /ecl/sparql` | The SPARQL generated for the ECL (`text/plain`) |
| `POST /sparql?limit=&offset=` | Run a SPARQL `SELECT` that binds `?concept` (for debugging) |
| `GET /status` | Release version, concept and triple counts |
| `GET /examples` | The ECL example catalog (used by the UI) |
| `GET /index.html` | The UI |

`name` is the fully specified name. Errors are [RFC 9457](https://www.rfc-editor.org/rfc/rfc9457) problem details:
400 for invalid ECL, SPARQL or paging, 501 for valid ECL the translation does not support, 503 for a query timeout.
Recent results are cached, so paging through a result does not re-run the query.

### Examples

Descendants of diabetes mellitus, first page of 3, then the next page:

```bash
curl -X POST -H 'Content-Type: text/plain' --data '< 73211009 |Diabetes mellitus|' \
  'http://localhost:8080/ecl?limit=3&offset=0'
```
```json
{"total":117,"limit":3,"offset":0,"items":[{"code":"2751001","name":"Fibrocalculous pancreatic diabetes (disorder)"},{"code":"5368009","name":"Drug-induced diabetes mellitus (disorder)"},{"code":"5969009","name":"Diabetes mellitus associated with genetic syndrome (disorder)"}]}
```
```bash
curl -X POST -H 'Content-Type: text/plain' --data '< 73211009 |Diabetes mellitus|' \
  'http://localhost:8080/ecl?limit=3&offset=3'
```
```json
{"total":117,"limit":3,"offset":3,"items":[{"code":"8801005","name":"Secondary diabetes mellitus (disorder)"},{"code":"11530004","name":"Brittle diabetes mellitus (disorder)"},{"code":"11687002","name":"Gestational diabetes mellitus (disorder)"}]}
```

The SPARQL for the same expression:

```bash
curl -X POST -H 'Content-Type: text/plain' --data '< 73211009 |Diabetes mellitus|' http://localhost:8080/ecl/sparql
```
```sparql
PREFIX sct: <http://snomed.info/id/>
PREFIX owl: <http://www.w3.org/2002/07/owl#>
PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
PREFIX xsd: <http://www.w3.org/2001/XMLSchema#>
SELECT DISTINCT ?concept WHERE {
  ?concept rdfs:subClassOf+ sct:73211009 .
}
```

A refinement — disorders of the lung with edema as associated morphology:

```bash
curl -X POST -H 'Content-Type: text/plain' \
  --data '< 19829001 |Disorder of lung| : 116676008 |Associated morphology| = 79654002 |Edema|' \
  'http://localhost:8080/ecl?limit=2'
```
```json
{"total":25,"limit":2,"offset":0,"items":[{"code":"11468004","name":"Postoperative pulmonary edema (disorder)"},{"code":"19242006","name":"Pulmonary edema (disorder)"}]}
```
```bash
curl -X POST -H 'Content-Type: text/plain' \
  --data '< 19829001 |Disorder of lung| : 116676008 |Associated morphology| = 79654002 |Edema|' \
  http://localhost:8080/ecl/sparql
```
```sparql
PREFIX sct: <http://snomed.info/id/>
...
SELECT DISTINCT ?concept WHERE {
  ?concept rdfs:subClassOf+ sct:19829001 .
  FILTER(
    EXISTS {
      ?concept sct:hasRestriction ?r1 .
      ?r1 owl:onProperty sct:116676008 .
      ?r1 owl:someValuesFrom ?val2 .
      FILTER(?val2 = sct:79654002)
      ?val2 a owl:Class .
    }
  )
}
```

Reference set membership, a history supplement, and an invalid expression:

```bash
curl -X POST -H 'Content-Type: text/plain' \
  --data '^ 447562003 |ICD-10 complex map reference set| AND < 195967001 |Asthma|' 'http://localhost:8080/ecl?limit=2'
# {"total":127,"limit":2,"offset":0,"items":[{"code":"11641008","name":"Millers' asthma (disorder)"},...]}

curl -X POST -H 'Content-Type: text/plain' --data '<< 195967001 {{ + HISTORY-MIN }}' 'http://localhost:8080/ecl?limit=0'
# {"total":156,"limit":0,"offset":0,"items":[]}

curl -X POST -H 'Content-Type: text/plain' --data '< 404684003 :' http://localhost:8080/ecl
# {"detail":"ECL is incomplete.","instance":"/ecl","status":400,"title":"Bad Request"}
```

## Web UI

http://localhost:8080/index.html (a single page, `src/main/resources/static/index.html`): a drop-down of the catalog
examples fills the ECL box; **Go** (or Ctrl/⌘+Enter) shows the generated SPARQL and a page of results (code and
FSN), with Previous / Next and a page size of 10, 50, 100, 500, 1000 or 10000.

## How ECL maps to SPARQL

Each expression constraint becomes a SPARQL graph pattern binding `?concept`
(`src/main/java/com/wci/snomed/sparql/ecl/SparqlGenerator.java`):

| ECL | SPARQL |
|---|---|
| `C`, `*` | `VALUES ?concept { sct:C }` / `?concept a owl:Class` (active concepts only) |
| `<`, `<<`, `<!`, `<<!` | `?concept rdfs:subClassOf+` / `*` / (one step) / `?` `sct:C` (named parent index, see below) |
| `>`, `>>`, `>!`, `>>!` | `sct:C rdfs:subClassOf+ ?concept` (etc.), IRIs only |
| `^ R` | `?concept sct:isMemberOf sct:R` (includes inactive members, as Snowstorm) |
| `A AND B`, `A OR B`, `A MINUS B` | join, `UNION`, `MINUS` (`FILTER NOT EXISTS` when checking a bound concept) |
| `X . attr` | `?x sct:hasRestriction ?r . ?r owl:onProperty attr ; owl:someValuesFrom ?concept` |
| `X : refinement` | `X` then `FILTER(...)`: each attribute is an `EXISTS` over the concept's restrictions (`[min..max]` by distinct restriction nodes), `{ }` within one `sct:hasRoleGroup` node, `R` from the value side, `!=` as "value not in", concrete values via `owl:hasValue` |
| `{{ + HISTORY... }}` | `{ X } UNION { X(?x) . ?concept ?refset ?x }` over the historical association refsets of the profile |
| `{{ C ... }}`, `{{ D ... }}`, `{{ M ... }}` | `FILTER EXISTS` over the concept's annotations, its described labels (`owl:annotatedSource`), or its association annotations |

## How the OWL file differs from the stock snomed-owl-toolkit OWL

The `--release` mode starts from the ontology snomed-owl-toolkit builds from RF2, then changes it so ECL can be
answered with SPARQL:

| Change | Stock toolkit OWL | This project | Why |
|---|---|---|---|
| Class axioms | Stated axioms (from the OWL axiom refset) | **Inferred** axioms: the stated ontology is classified with ELK and the toolkit's normal form generator produces each concept's inferred relationships, written back as one `SubClassOf` (primitive) or `EquivalentClasses` (fully defined) axiom per concept, in the toolkit's OWL shape | ECL is evaluated against the inferred view (proximal parents and inferred attributes). For 20261001 this reproduces the release's inferred relationship file exactly (0 added, 0 removed) |
| Root concept | `SubClassOf(:138875005 owl:Thing)` | Declaration only | Has no relationships; avoids an `owl:Thing` parent |
| Refset membership | Not represented | `AnnotationAssertion(:isMemberOf :member :refset)` for every active member of every refset (`der2_*Refset_*` and the `sct2_` OWL refsets) whose referenced component is a concept | `^ refset` |
| Historical associations | Not represented | Each association refset (e.g. 900000000000527005 SAME AS) is declared as an annotation property, with `AnnotationAssertion(:refset :source :target)` for each active member whose referenced component and target are both concepts (description members, e.g. the 4,456 of REFERS TO concept 900000000000531004, are skipped) | `{{ + HISTORY }}` supplements and member filters on `targetComponentId` |
| Inactive concepts | Not represented | Inactive concepts referenced by a membership or association get `AnnotationAssertion(owl:deprecated :id "true"^^xsd:boolean)`, plus their descriptions and metadata like active concepts, but are **not** declared as classes | Snowstorm's `^ refset` returns inactive members (e.g. all SAME AS members), while `*` and the hierarchy only match active concepts |
| Concept metadata | Not represented | `AnnotationAssertion(:moduleId :c :module)` and `AnnotationAssertion(:effectiveTime :c "yyyymmdd")` | `{{ C moduleId = ... }}`, `{{ C effectiveTime ... }}` |
| Descriptions | Term only: FSN as `rdfs:label`, synonyms preferred in any dialect as `skos:prefLabel`, other synonyms as `skos:altLabel` (language tag with dialect, e.g. `@en-us`, when only preferred in one) | Same properties (plus text definitions as `skos:definition`), language tag = language code, and every active description carries its RF2 fields as **axiom annotations**: `:descriptionId`, `:typeId`, `:moduleId`, `:effectiveTime`, `:caseSignificanceId`, and `:<languageRefset> :<acceptability>` per language refset member | Description filters `{{ D term/type/language/dialect/moduleId/effectiveTime/id ... }}` |

The ontology is classified without descriptions (the toolkit's taxonomy walker cannot handle them). The 20261001
file is 919 MB (5.0M axioms); converting takes ~95s and ~6 GB.

### Example: an active concept

All the axioms for 195967001 |Asthma|. Lines starting with `+` are added by this project, the `-` line is the stock
(stated) axiom that the inferred one replaces; other lines are as in the stock file. (The stock file has the same
labels, without the `Annotation(...)` parts.)

```diff
 Declaration(Class(:195967001))
-SubClassOf(:195967001 ObjectIntersectionOf(:64572001 ObjectSomeValuesFrom(:609096000 ObjectSomeValuesFrom(:363698007 :89187006))))
+SubClassOf(:195967001 ObjectIntersectionOf(:50043002 ObjectSomeValuesFrom(:609096000 ObjectSomeValuesFrom(:363698007 :89187006))))
+AnnotationAssertion(:moduleId :195967001 :900000000000207008)
+AnnotationAssertion(:effectiveTime :195967001 "20040731"^^xsd:string)
+AnnotationAssertion(:isMemberOf :195967001 :447562003)
+AnnotationAssertion(:isMemberOf :195967001 :733073007)
+AnnotationAssertion(:isMemberOf :195967001 :900000000000497000)
+AnnotationAssertion(Annotation(:caseSignificanceId :900000000000448009) Annotation(:descriptionId "580135014"^^xsd:string) Annotation(:effectiveTime "20170731"^^xsd:string) Annotation(:moduleId :900000000000207008) Annotation(:typeId :900000000000003001) Annotation(:900000000000508004 :900000000000548007) Annotation(:900000000000509007 :900000000000548007) rdfs:label :195967001 "Asthma (disorder)"@en)
+AnnotationAssertion(Annotation(:caseSignificanceId :900000000000448009) Annotation(:descriptionId "301485011"^^xsd:string) Annotation(:effectiveTime "20170731"^^xsd:string) Annotation(:moduleId :900000000000207008) Annotation(:typeId :900000000000013009) Annotation(:900000000000508004 :900000000000548007) Annotation(:900000000000509007 :900000000000548007) skos:prefLabel :195967001 "Asthma"@en)
+AnnotationAssertion(Annotation(:caseSignificanceId :900000000000448009) Annotation(:descriptionId "301480018"^^xsd:string) Annotation(:effectiveTime "20170731"^^xsd:string) Annotation(:moduleId :900000000000207008) Annotation(:typeId :900000000000013009) Annotation(:900000000000508004 :900000000000549004) Annotation(:900000000000509007 :900000000000549004) skos:altLabel :195967001 "Bronchial asthma"@en)
 ... (6 more skos:altLabel synonyms, annotated the same way)
```

- The stated parent 64572001 |Disease| is replaced by the inferred proximal parent 50043002 |Disorder of
  respiratory system|.
- `:900000000000508004` / `:900000000000509007` are the GB / US English language refsets, used as annotation
  properties; `:900000000000548007` = preferred, `:900000000000549004` = acceptable.
- In RDF (as loaded for SPARQL), each annotated label is the triple `:195967001 skos:prefLabel "Asthma"@en` plus a
  reified `owl:Axiom` node: `[ a owl:Axiom ; owl:annotatedSource :195967001 ; owl:annotatedProperty skos:prefLabel ;
  owl:annotatedTarget "Asthma"@en ; :descriptionId "301485011" ; :typeId :900000000000013009 ; ... ]`, which is
  what description filters query.

### Example: an inactive concept with a historical association

155574008 |Asthma| (inactive, SAME AS 195967001). The stock file has nothing for it; everything is added:

```diff
+AnnotationAssertion(owl:deprecated :155574008 "true"^^xsd:boolean)
+AnnotationAssertion(:moduleId :155574008 :900000000000207008)
+AnnotationAssertion(:effectiveTime :155574008 "20020131"^^xsd:string)
+AnnotationAssertion(:900000000000527005 :155574008 :195967001)
+AnnotationAssertion(:isMemberOf :155574008 :900000000000527005)
+AnnotationAssertion(:isMemberOf :155574008 :900000000000489007)
+AnnotationAssertion(:isMemberOf :155574008 :900000000000497000)
+AnnotationAssertion(Annotation(:caseSignificanceId :900000000000448009) Annotation(:descriptionId "2724893013"^^xsd:string) Annotation(:effectiveTime "20170731"^^xsd:string) Annotation(:moduleId :900000000000207008) Annotation(:typeId :900000000000003001) Annotation(:900000000000508004 :900000000000548007) Annotation(:900000000000509007 :900000000000548007) rdfs:label :155574008 "Asthma (disorder)"@en)
+AnnotationAssertion(Annotation(:caseSignificanceId :900000000000448009) Annotation(:descriptionId "242335016"^^xsd:string) Annotation(:effectiveTime "20170731"^^xsd:string) Annotation(:moduleId :900000000000207008) Annotation(:typeId :900000000000013009) Annotation(:900000000000508004 :900000000000548007) Annotation(:900000000000509007 :900000000000548007) skos:prefLabel :155574008 "Asthma"@en)
```

- No `Declaration(Class(...))` and no class axiom: inactive concepts are not in the hierarchy and never match `*`.
- `AnnotationAssertion(:900000000000527005 :155574008 :195967001)`: the SAME AS association refset is the
  annotation property (declared once: `Declaration(AnnotationProperty(:900000000000527005))`), so
  `<< 195967001 {{ + HISTORY-MIN }}` finds 155574008.
- 900000000000489007 is the concept inactivation indicator refset, 900000000000497000 the CTV3 simple map.

### Example: the root concept

```diff
 Declaration(Class(:138875005))
-SubClassOf(:138875005 owl:Thing)
+AnnotationAssertion(:moduleId :138875005 :900000000000207008)
+AnnotationAssertion(:effectiveTime :138875005 "20020131"^^xsd:string)
+AnnotationAssertion(:isMemberOf :138875005 :900000000000497000)
 ... (descriptions, annotated as above)
```

### Declarations added

```diff
+Declaration(AnnotationProperty(:isMemberOf))
+Declaration(AnnotationProperty(:900000000000527005))   (one per association refset: 10 for 20261001)
+Declaration(AnnotationProperty(:900000000000509007))   (one per language refset: 2 for 20261001)
+Declaration(AnnotationProperty(:moduleId))
+Declaration(AnnotationProperty(:effectiveTime))
+Declaration(AnnotationProperty(:descriptionId))
+Declaration(AnnotationProperty(:typeId))
+Declaration(AnnotationProperty(:caseSignificanceId))
+Declaration(AnnotationProperty(skos:definition))
```

### Index triples added when loading

When the file is loaded for querying (`--owl`), index triples are added in memory only (the file is unchanged).
For 20261001 the store holds 28.0M triples (+1.6M index) and loads in ~70s (run with `-Xmx20g`).
The class axioms map to blank nodes in RDF (`:c rdfs:subClassOf [ owl:intersectionOf (:p1 :p2 ...) ]`), which
are slow to navigate, especially backwards from a value to the concept:

| Index triple | Meaning | Used for |
|---|---|---|
| `:c rdfs:subClassOf :p` | each named (proximal) parent of the concept (entailed by the axiom) | hierarchy operators (`rdfs:subClassOf+`) |
| `:c sct:hasRestriction _:r` | each attribute restriction node of the concept's axiom, ungrouped or inside a role group (`_:r owl:onProperty :attr ; owl:someValuesFrom :value` or `owl:hasValue literal`) | refinements, dot notation, reverse attributes; restriction nodes are kept so relationships can be counted |
| `:c sct:hasRoleGroup _:g` | the filler of each role group (`ObjectSomeValuesFrom(:609096000 _:g)`) | attribute groups `{ }` |

## ECL to SPARQL: not yet supported

Valid ECL the translation rejects (HTTP 501), with an expected result available:

| Examples | Feature | Why |
|---|---|---|
| 6.10-01..6.10-06, 6.10-09..6.10-11 (9) | Member filters on fields other than `referencedComponentId` and association `targetComponentId` (e.g. `mapTarget`, `mapGroup`), member `effectiveTime` / `moduleId` / `active` | Refset member fields are not in the OWL file (decided 2026-10-16: concept metadata only for now) |
| 6.12-01, 6.12-03, 6.12-05 | Top/bottom of set `!!>` `!!<` | Dropped by snomed-ecl-parser (no expected result either: Snowstorm 11 rejects them) |

Description filters only see **active** descriptions (`{{ D active = 0 }}` matches nothing), and dialect aliases
map to a fixed list of language refsets (an alias without a language refset in the release matches nothing).

Known difference: for `{{ + HISTORY }}`, `HISTORY-MAX`, `HISTORY (*)` and `HISTORY (< 900000000000522004)`
(6.11-09..12), Snowstorm also returns 492714018, a *description* id (a member of the REFERS TO concept
association refset). Results here are concepts only; the tests compare concept ids.

## Slow examples

Every supported example runs in under 10 s on the 20261001 data (Apple silicon, query only, no paging). These take
2 s or more, to be optimized later. Most refine a large focus (e.g. the 129,411 descendants of 404684003
|Clinical finding|): each focus concept is checked with a FILTER EXISTS over its attribute restrictions.

| Example | Seconds | Total | ECL |
|---|---|---|---|
| 6.4-21 | 8.4 | 38 | `< 404684003 : { 363698007 = << 39057004, 116676008 = << 415582006 } OR { ... }` |
| 6.3-20 | 5.9 | 74,424 | `< 404684003 : [1..1] { 363698007 = < 91723000 }` |
| 6.3-08 | 5.8 | 74,424 | `< 404684003 : [1..1] 363698007 = < 91723000` |
| 6.3-21 | 4.8 | 129,411 | `< 404684003 : [0..0] { [2..*] 363698007 = < 91723000 }` |
| 6.3-10 | 4.7 | 0 | `< 404684003 : { [2..*] 363698007 = < 91723000 }` |
| 6.7-10 | 4.4 | 33 | `(< 404684003 : 363698007 = << 39057004) AND (< 404684003 : 116676008 = << 415582006)` |
| 6.2-05 | 4.3 | 11 | `< 404684003 : { 363698007 = << 39057004, 116676008 = << 415582006 }` |
| 6.3-09 | 3.6 | 15,220 | `< 404684003 : [2..*] 363698007 = < 91723000` |
| 6.4-12 | 3.4 | 33 | `< 404684003 : 363698007 = << 39057004, 116676008 = << 415582006` |
| 6.2-03 | 3.4 | 33 | `< 404684003 : 363698007 = << 39057004, 116676008 = << 415582006` |
| 6.2-12 | 2.7 | 1,055 | `< 91723000 : R 363698007 = < 125605004` |
| 6.2-07 | 2.6 | 17 | `<< 404684003 : >> 42752001 = << 267038008` |
| 6.2-06 | 2.6 | 18 | `<< 404684003 : << 47429007 = << 267038008` |
| 6.8-29 | 2.1 | 2,832 | `< 125605004 MINUS * {{ term != "fracture" }}` |

## Additional ECL examples

Besides the specification examples (6.x) and `S-01` (from the "ECL as SPARQL" draft, `doc/ECL as SPARQL.docx`), the catalog has:

- **P-01..P-15**: expressions from the snomed-ecl-parser unit tests (develop branch: `ECLQueryBuilderTest`,
  `ECLQueryBuilderWithFiltersTest`) that add something beyond the specification examples: groups joined by OR,
  no-whitespace refinements, description filters with language/type/dialect combinations and negations, and a
  description filter on a refinement value. (The parser has ~30 test expressions; the others repeat spec examples.)
- **A-01..A-21**: International edition adaptations of specification examples whose content is not in the
  International release (so Snowstorm rejects them): 700043003 |Example problem list concepts reference set| →
  447562003 |ICD-10 complex map reference set|, 445238008 |Malignant carcinoid tumor| (inactive) → 55342001
  |Neoplastic disease|, the AU dialect → GB English, the US module → the core module, Hay asthma (inactive) → a
  SAME AS member. Each records the example it was adapted from.

Expected results for them come from the same local Snowstorm (`make expected-results`, which skips nothing; use
`--skip-existing` to fetch only new examples).

## ECL examples set aside

The test catalog (`src/main/resources/ecl-examples.json`) holds every example from the ECL specification's
"Behaviour specification with examples" (sections 6.1–6.12). Expected results come from a local Snowstorm 11.0.0
loaded with the 20261001 International release (`src/test/resources/data/20261001/`). The examples below have no
usable expected result from Snowstorm; each result file records Snowstorm's status and error message. They are
still tested (`EclExamplesTest`): each has an explicit outcome — unsupported (with the reason), a known parser
gap, equal to an equivalent example's result, or a baseline count reviewed against the RF2 release. Most of the
"content not in the International edition" examples also have International adaptations (A-01..A-21) with
Snowstorm results.

### Not executable (catalog) (5)

Marked `executable: false` in `ecl-examples.json`; never sent to Snowstorm or tested.

| Example | ECL | Reason |
|---|---|---|
| 6.4-09 | `< 19829001 \|Disorder of lung\| AND < 301867009 \|Edema of trunk\| OR ^ 700043003 \|Examp…` | The spec presents this as NOT valid: AND and OR mixed without brackets. |
| 6.4-18 | `< 404684003 \|Clinical finding\| : 363698007 \|Finding site\| = << 39057004 \|Pulmonary v…` | The spec presents this as NOT valid: AND and OR mixed in a refinement without brackets (snomed-ecl-parser and Snowstorm accept it anyway, with different meanings). |
| 6.8-01 | `{{ D term = "heart att" }}` | Fragment showing a description filter on its own (no focus concept); not an expression. |
| 6.11-03 | `[[+ecl @ecl_query]] {{ + HISTORY ( [[+ecl @history_refset_query]] ) }}` | Template showing how HISTORY expands; not an executable expression. |
| 6.11-04 | `[[+ecl @ecl_query]] OR ^ [[+ecl @history_refset_query]] {{ M targetComponentId = [[+ecl @…` | Template showing how HISTORY expands; not an executable expression. |

### Content not in the International edition (23)

Snowstorm: *Concepts in the ECL request do not exist or are inactive* — the examples use concepts that are not in, or are inactive in, the 20261001 International release: content from other editions (700043003 Example problem list refset, US/AU modules, AU dialect refset, product name / national benefit scheme attributes) and concepts since inactivated (e.g. 445238008 Malignant carcinoid tumor, 67415000 Hay asthma).

| Example | ECL |
|---|---|
| 6.1-10 | `^ 700043003 \|Example problem list concepts reference set\|` |
| 6.1-11 | `^ [referencedComponentId] 700043003 \|Example problem list concepts reference set\|` |
| 6.2-10 | `< 373873005 \|Pharmaceutical / biologic product\| : 3460481009 \|Has product name\| = "PA…` |
| 6.2-11 | `< 373873005 \|Pharmaceutical / biologic product\| : 859999999102 \|Is in national benefit…` |
| 6.4-05 | `< 19829001 \|Disorder of lung\| AND ^ 700043003 \|Example problem list concepts reference…` |
| 6.4-06 | `< 19829001 \|Disorder of lung\| AND < 301867009 \|Edema of trunk\| AND ^ 700043003 \|Exam…` |
| 6.4-07 | `(< 19829001 \|Disorder of lung\| AND < 301867009 \|Edema of trunk\| ) AND ^ 700043003 \|E…` |
| 6.4-08 | `< 19829001 \|Disorder of lung\| AND (< 301867009 \|Edema of trunk\| AND ^ 700043003 \|Exa…` |
| 6.4-10 | `(< 19829001 \|Disorder of lung\| AND < 301867009 \|Edema of trunk\| ) OR ^ 700043003 \|Ex…` |
| 6.4-11 | `< 19829001 \|Disorder of lung\| AND (< 301867009 \|Edema of trunk\| OR ^ 700043003 \|Exam…` |
| 6.4-15 | `< 404684003 \|Clinical finding\| : 363698007 \|Finding site\| = << 39057004 \|Pulmonary v…` |
| 6.4-16 | `< 404684003 \|Clinical finding\| : ( 363698007 \|Finding site\| = << 39057004 \|Pulmonary…` |
| 6.4-17 | `< 404684003 \|Clinical finding\| : 363698007 \|Finding site\| = << 39057004 \|Pulmonary v…` |
| 6.4-19 | `< 404684003 \|Clinical finding\| : ( 363698007 \|Finding site\| = << 39057004 \|Pulmonary…` |
| 6.4-20 | `< 404684003 \|Clinical finding\| : 363698007 \|Finding site\| = << 39057004 \|Pulmonary v…` |
| 6.5-02 | `<< 19829001 \|Disorder of lung\| MINUS ^ 700043003 \|Example problem list concepts refere…` |
| 6.7-01 | `<< (^ 700043003 \|Example problem list concepts reference set\| )` |
| 6.7-08 | `(< 404684003 \|Clinical finding\| : 363698007 \|Finding site\| = << 39057004 \|Pulmonary …` |
| 6.7-09 | `^ 700043003 \|Example problem list concepts reference set\| AND (< 404684003 \|Clinical f…` |
| 6.8-18 | `< 64572001 \|Disease\| {{ dialectId = 32570271000036106 \|Australian English language ref…` |
| 6.9-06 | `< 195967001 \|Asthma\| {{ C moduleId = 731000124108 \|US National Library of Medicine mai…` |
| 6.10-07 | `^ [targetComponentId] 900000000000527005 \|SAME AS association reference set\| {{ M refer…` |
| 6.10-08 | `^ 900000000000534007 \|Module dependency reference set\| {{ M moduleId = << 3257023100003…` |

### Member field selection (3)

Snowstorm: *ECL requesting specific refset member fields, can not return concept ids* — `^ [fields] refset` returns member rows, not concepts.

| Example | ECL |
|---|---|
| 6.1-12 | `^ [targetComponentId] 734138000 \|Anatomy structure and entire association reference set\|` |
| 6.1-13 | `^ [referencedComponentId, mapTarget] 816210007 \|SNOMED CT to MedDRA simple map reference…` |
| 6.1-14 | `^[*] 447562003 \|ICD-10 complex map reference set\|` |

### ECL 2.2 syntax not supported by Snowstorm 11.0.0 (11)

Syntax errors for `^R` (refsetContaining), alternate identifiers (`LOINC#...`), top/bottom (`!!>` / `!!<`), description `id` filters, and `active = "*"`.

| Example | ECL |
|---|---|
| 6.1-25 | `^R 73211009 \|Diabetes mellitus\|` |
| 6.1-26 | `LOINC#54486-6` |
| 6.1-27 | `LOINC#"54486-6"` |
| 6.8-44 | `^ 816080008 \|International Patient Summary\| {{ D active = "*" }}` |
| 6.8-45 | `*{{ D id = 3032638017 }}` |
| 6.8-46 | `< 195967001 \|Asthma (disorder)\| {{ D id = 3032638017 }}` |
| 6.8-47 | `< 195967001 \|Asthma (disorder)\| {{ D id = (1208972017 2674140012 3043971012) }}` |
| 6.8-48 | `(< 195967001 \|Asthma (disorder)\| {{+HISTORY}}) {{ D id = 264553015 }}` |
| 6.12-01 | `!!> ( < 386617003 \|Digestive system finding\| . 363698007 \|Finding site\| )` |
| 6.12-03 | `!!< ( >> 427089005 \|Diabetes mellitus due to cystic fibrosis\| AND ^ 816080008 \|Interna…` |
| 6.12-05 | `!!< ( >> 45133009 \|Neurotoxic shellfish poisoning\| AND ^ 991411000000109 \|Emergency ca…` |

### Empty effectiveTime filter (2)

`effectiveTime = ""` fails with *For input string: ""* (the same defect as in snomed-ecl-parser).

| Example | ECL |
|---|---|
| 6.8-39 | `< 125605004 \|Fracture of bone\| {{ D effectiveTime = "" }}` |
| 6.9-14 | `< 125605004 \|Fracture of bone\| {{ C effectiveTime = "" }}` |

### Unsupported or failing query (3)

Snowstorm: *Returning the attribute values of all concepts is not supported* (`R ... = *` with cardinality), an Elasticsearch query error for `* MINUS * {{ ... dialect = en-nz ... }}`, and a timeout for `^*` (here: 539,490 concepts, checked against the RF2 release).

| Example | ECL |
|---|---|
| 6.1-24 | `^*` |
| 6.3-22 | `< 105590001 \|Substance\| : [3..3] R 127489000 \|Has active ingredient\| = *` |
| 6.8-30 | `* MINUS * {{ type = syn, dialect = en-nz (prefer) }}` |

### Not supported by snomed-ecl-parser (5)

Valid ECL that the ECL parser (5.0.0-SNAPSHOT) rejects; `EclExampleCatalogTest.KNOWN_PARSER_GAPS` asserts they still
fail so we notice when it improves. All five are also rejected by Snowstorm (above): 6.1-25 `^R`, 6.1-27
`LOINC#"..."`, 6.8-44 `active = "*"`, and 6.8-39 / 6.9-14 `effectiveTime = ""`.

### Spec corrections

Some spec examples have typos that were corrected in the catalog (each has a `note`): 6.3-18 `[1..]` → `[1..*]`,
6.6-01 comment asterisks, 6.8-37 / 6.8-38 / 6.9-12 / 6.9-13 missing `)`.

## Tests

```bash
make test        # unit tests + checkstyle + spotbugs (~5 min; loads the 20261001 test OWL once, ~25 GB heap)
make test-data   # regenerate the test OWL src/test/data/snomed-inferred-20261001.owl from the RF2 release
```

- `src/test/resources/rf2/SnomedCT_MiniRF2_20190131`: a tiny RF2 release for the converter, store, translator and
  REST tests.
- `src/test/data/snomed-inferred-20261001.owl`: the converted 20261001 release (not in version control: `*owl` is
  ignored; generate it with `make test-data`).
- `src/main/resources/ecl-examples.json`: the ECL example catalog (generated by `tools/extract_ecl.py` and
  `tools/curate_ecl.py`, see `tools/README.md`).
- `src/test/resources/data/20261001/<id>.json`: the expected result of each example from a local Snowstorm 11.0.0
  loaded with the same release (concept ids, or Snowstorm's error). To regenerate them, see `snowstorm/README.md`:
  `make snowstorm-up snowstorm-import expected-results`.
- `EclExamplesTest` gives every executable example exactly one expected outcome: the Snowstorm result (count, then
  concept ids), a known parser gap, "unsupported" with its reason, or — where Snowstorm has no result — an
  equivalent example's result or a count reviewed against the RF2 release.

## Building

```bash
make clean build test install run
```

| Target | Description |
|---|---|
| `make build` | Compile and package (no tests) |
| `make test` | Unit tests, checkstyle, spotbugs |
| `make install` | Publish to the local Maven repository |
| `make run ARGS="--owl=..."` | Run with Gradle (`bootRun`) |
| `make owl` / `make serve` | Generate the OWL file / serve it (see Quick start) |
| `make test-data` / `make expected-results` | Regenerate the test OWL / the expected results from Snowstorm |
| `make snowstorm-up` / `snowstorm-import` / `snowstorm-down` | Local Snowstorm for expected results |

The build uses Gradle 9.1 (wrapper), Java 25, Spring Boot 4.1, Apache Jena 5.5 and OWL API 4.1 (versions from
`org.snomed:snomed-parent-bom`). It depends on the `develop` (Spring Boot 4) branches of snomed-owl-toolkit
(7.0.0-SNAPSHOT) and snomed-ecl-parser (5.0.0-SNAPSHOT), which must be installed to the local Maven repository first
(`mvn install -DskipTests -Ddependency-check.skip=true` in each). License headers are applied by Spotless
(`config/license-header.txt`); checkstyle and spotbugs configuration is in `config/`.

## Documents

- `doc/TODO.md`: the original project requirements.
- `doc/PLAN.md`: the implementation plan, decisions and progress log.
- `doc/ECL as SPARQL.docx`: the draft mapping of ECL to SPARQL that this project started from.
- `AGENTS.md`: notes for maintaining the project with AI coding agents.

## License

Apache License 2.0 — see `LICENSE.txt`. SNOMED CT content is subject to the SNOMED CT Affiliate License.
