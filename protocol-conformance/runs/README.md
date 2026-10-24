# Recorded runs

Each subdirectory is one immutable, genuine capture of a live investigative run:
`request.txt`, `response.txt`, `invocation.json`, `run.json` (and `failure.txt` for a failed run),
plus the retained verdict. A run is never edited or replaced; a later subject or roster gets a new
run id. Replay (`scripts/run.sh --replay <run-id>`) pairs a recording only with the identical
regenerated request.

Recordings are not committed (they are ignored by git): a live run's session transcript carries the
environment of the machine the judge ran on. Their per-item results are published under
[`../results/`](../results). There is deliberately no synthetic "all PASS" recording: replay without a
real run is refused.
