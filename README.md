# snomed-sparql

A SPARQL-based implementation of the SNOMED CT Expression Constraint Language (ECL). Work in progress: see
`PLAN.md` for status. (The full README is written at the end of the project.)

## How the OWL file differs from the stock snomed-owl-toolkit OWL

The `--release` mode starts from the ontology snomed-owl-toolkit builds from RF2 (with descriptions), then
changes it so ECL can be answered with SPARQL:

| Change | Stock toolkit OWL | This project | Why |
|---|---|---|---|
| Class axioms | Stated axioms (from the OWL axiom refset) | **Inferred** axioms: the stated ontology is classified with ELK and the toolkit's normal form generator produces each concept's inferred relationships, written back as one `SubClassOf` (primitive) or `EquivalentClasses` (fully defined) axiom per concept, in the toolkit's OWL shape | ECL is evaluated against the inferred view (proximal parents and inferred attributes). For 20261001 this reproduces the release's inferred relationship file exactly (0 added, 0 removed) |
| Root concept | `SubClassOf(:138875005 owl:Thing)` | Declaration only | Has no relationships; avoids an `owl:Thing` parent |
| Refset membership | Not represented | `AnnotationAssertion(:isMemberOf :member :refset)` for every active member of every refset (`der2_*Refset_*` and the `sct2_` OWL refsets) whose referenced component is a concept | `^ refset` |
| Historical associations | Not represented | Each association refset (e.g. 900000000000527005 SAME AS) is declared as an annotation property, with `AnnotationAssertion(:refset :source :target)` for each active member | `{{ + HISTORY }}` supplements and member filters on `targetComponentId` |
| Inactive concepts | Not represented | Inactive concepts referenced by a membership or association get `AnnotationAssertion(owl:deprecated :id "true"^^xsd:boolean)` and their FSN as `rdfs:label`, but are **not** declared as classes | Snowstorm's `^ refset` returns inactive members (e.g. all SAME AS members), while `*` and the hierarchy only match active concepts |
| Labels | `rdfs:label` (FSN) and `skos:prefLabel` (preferred term) | Unchanged | Names in results |

The ontology is classified without the description annotations (the toolkit's taxonomy walker cannot handle
them), then the descriptions are added for output.

When the file is loaded for querying (`--owl`), one more thing is added in memory only: the class axioms map to
blank nodes in RDF (`:c rdfs:subClassOf [ owl:intersectionOf (:p1 :p2 ...) ]`), so each concept's named
(proximal) parents are also asserted directly as `:c rdfs:subClassOf :p`. These triples are entailed by the
axioms and let the hierarchy be queried with `rdfs:subClassOf+`.

## ECL examples set aside

The test catalog (`src/main/resources/ecl-examples.json`) holds every example from the ECL specification's
"Behaviour specification with examples" (sections 6.1–6.12). Expected results come from a local Snowstorm 11.0.0
loaded with the 20261001 International release (`src/test/resources/data/20261001/`). The examples below have no
usable expected result and are set aside for now; each result file records Snowstorm's status and error message.

### Not executable (catalog) (5)

Marked `executable: false` in `ecl-examples.json`; never sent to Snowstorm or tested.

| Example | ECL | Reason |
|---|---|---|
| 6.1-24 | `^*` | Skipped for now as a degenerate case: '^*' (members of any reference set) is too expensive to evaluate on Snowstorm (timed out). To be revisited at the end (see PLAN.md). |
| 6.4-09 | `< 19829001 \|Disorder of lung\| AND < 301867009 \|Edema of trunk\| OR ^ 700043003 \|Examp…` | The spec presents this as NOT valid: AND and OR mixed without brackets. |
| 6.8-01 | `{{ D term = "heart att" }}` | Fragment showing a description filter on its own (no focus concept); not an expression. |
| 6.11-03 | `[[+ecl @ecl_query]] {{ + HISTORY ( [[+ecl @history_refset_query]] ) }}` | Template showing how HISTORY expands; not an executable expression. |
| 6.11-04 | `[[+ecl @ecl_query]] OR ^ [[+ecl @history_refset_query]] {{ M targetComponentId = [[+ecl @…` | Template showing how HISTORY expands; not an executable expression. |

### Content not in the International edition (24)

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
| 6.4-18 | `< 404684003 \|Clinical finding\| : 363698007 \|Finding site\| = << 39057004 \|Pulmonary v…` |
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

### Unsupported or failing query (2)

Snowstorm: *Returning the attribute values of all concepts is not supported* (`R ... = *` with cardinality), and an Elasticsearch query error for `* MINUS * {{ ... dialect = en-nz ... }}`.

| Example | ECL |
|---|---|
| 6.3-22 | `< 105590001 \|Substance\| : [3..3] R 127489000 \|Has active ingredient\| = *` |
| 6.8-30 | `* MINUS * {{ type = syn, dialect = en-nz (prefer) }}` |

### Not supported by snomed-ecl-parser (5)

Valid ECL that the ECL parser (5.0.0-SNAPSHOT) rejects; `EclExampleCatalogTest.KNOWN_PARSER_GAPS` asserts they still
fail so we notice when it improves. All five are also rejected by Snowstorm (above): 6.1-25 `^R`, 6.1-27
`LOINC#"..."`, 6.8-44 `active = "*"`, and 6.8-39 / 6.9-14 `effectiveTime = ""`.

### Spec corrections

Some spec examples have typos that were corrected in the catalog (each has a `note`): 6.3-18 `[1..]` → `[1..*]`,
6.6-01 comment asterisks, 6.8-37 / 6.8-38 / 6.9-12 / 6.9-13 missing `)`.
