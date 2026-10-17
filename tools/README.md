# tools

Scripts used to build `src/main/resources/ecl-examples.json`, the catalog of ECL examples from the ECL
specification's "Behaviour specification with examples" pages (sections 6.1–6.12).

1. Download the markdown version of each page (GitBook serves `<page-url>.md`; the page list is in
   https://docs.snomed.org/llms.txt under `behaviour-specification-with-examples`).
2. `python3 tools/extract_ecl.py 6.*.md > tools/ecl-examples-raw.json` — pulls every code block, pairing the
   "Brief syntax" / "Long syntax" tabs of each example.
3. `python3 tools/curate_ecl.py tools/ecl-examples-raw.json > src/main/resources/ecl-examples.json` — splits
   multi-expression blocks, applies corrections for spec typos, marks non-executable examples, adds supplementary
   examples, and tags each example with the ECL features it uses. Every correction carries a `note`.

`EclExampleCatalogTest` checks every executable example parses with snomed-ecl-parser.
