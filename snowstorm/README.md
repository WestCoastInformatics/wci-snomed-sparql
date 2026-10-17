# Local Snowstorm for expected ECL results

The unit tests compare the SPARQL implementation against the results of the SNOMED browser's ECL engine,
[Snowstorm](https://github.com/IHTSDO/snowstorm). The public browsers refuse programmatic access, so the expected
results are generated from a local Snowstorm (11.0.0, which also supports a newer ECL version than the public
browser) loaded with the same RF2 release.

```bash
make snowstorm-up                       # Elasticsearch 8.19.8 + Snowstorm 11.0.0, API on http://127.0.0.1:8081
make snowstorm-import                   # import the RF2 snapshot (VERSION=20261001 by default), ~30-60 min
make expected-results                   # write src/test/resources/data/<VERSION>/<exampleId>.json
make snowstorm-down                     # stop (the Elasticsearch volume keeps the imported data)
```

For another release: `make snowstorm-import expected-results VERSION=20270101 RELEASE_DIR=<release folder>`.

Notes:
- Elasticsearch is not published on the host (only Snowstorm talks to it), so it doesn't clash with another
  Elasticsearch on port 9200.
- Both services are pinned to `linux/arm64` (Apple silicon). Under amd64 emulation Elasticsearch fails to start
  ("seccomp unavailable"); on an amd64 host change `platform` to `linux/amd64`.
- The import zips the release's `Snapshot` folder into `snowstorm/import/` (git-ignored).
