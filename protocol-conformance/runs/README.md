# Recorded runs

Each subdirectory is one immutable, genuine capture of a live investigative run:
`request.txt`, `response.txt`, `invocation.json`, `run.json` (and `failure.txt` for a failed run),
plus the retained verdict. A run is never edited or replaced; a later subject or roster gets a new
run id. Replay (`scripts/run.sh --replay <run-id>`) pairs a recording only with the identical
regenerated request.

No recording exists yet. There is deliberately no synthetic "all PASS" recording: replay without a
real run is refused.
