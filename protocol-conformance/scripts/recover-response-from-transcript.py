#!/usr/bin/env python3
"""Recover a live run's verbatim response from the judging agent's own Claude Code session transcript.

Used only when the harness's capture of an otherwise completed run failed after the agent answered
(run 001: the Agent Eval bridge refused the native snapshot above its 1 MiB bound and propagated).
The response is the concatenation, in order, of every assistant text block in the session JSONL;
nothing is edited, reordered or filtered. The transcript is copied next to the run and its digest
recorded, and run.json gains an explicit `responseSource`. A run that already has response.txt is
never touched. Run with `python3 -I`.
"""
import hashlib
import json
import shutil
import sys
from pathlib import Path


def main() -> None:
    if len(sys.argv) != 3:
        sys.exit("usage: recover-response-from-transcript.py <run-dir> <session.jsonl>")
    run = Path(sys.argv[1])
    transcript = Path(sys.argv[2])
    response = run / "response.txt"
    if response.exists():
        sys.exit(f"{response} exists; a recording is never rewritten")
    if not (run / "request.txt").exists() or not (run / "run.json").exists():
        sys.exit(f"{run} is not a captured run directory")
    raw = transcript.read_bytes()
    blocks, models, usage, first, last, assistant, tools = [], {}, {}, None, None, 0, {}
    for line in raw.decode("utf-8").splitlines():
        try:
            d = json.loads(line)
        except json.JSONDecodeError:
            continue
        ts = d.get("timestamp")
        if ts:
            first = first or ts
            last = ts
        if d.get("type") != "assistant":
            continue
        assistant += 1
        m = d.get("message", {})
        models[m.get("model")] = models.get(m.get("model"), 0) + 1
        for k, v in (m.get("usage") or {}).items():
            if isinstance(v, int):
                usage[k] = usage.get(k, 0) + v
        for c in m.get("content", []):
            if isinstance(c, dict) and c.get("type") == "text":
                blocks.append(c["text"])
            elif isinstance(c, dict) and c.get("type") == "tool_use":
                tools[c.get("name")] = tools.get(c.get("name"), 0) + 1
    if not blocks:
        sys.exit("no assistant text in transcript")
    text = "\n".join(blocks)
    copied = run / "transcript.jsonl"
    shutil.copyfile(transcript, copied)
    response.write_text(text, encoding="utf-8")
    meta = json.loads((run / "run.json").read_text(encoding="utf-8"))
    meta["state"] = "recovered-from-transcript"
    meta["responseSource"] = {
        "kind": "claude-code-session-transcript",
        "originalPath": str(transcript),
        "copiedTo": copied.name,
        "transcriptSha256": hashlib.sha256(raw).hexdigest(),
        "responseSha256": hashlib.sha256(text.encode("utf-8")).hexdigest(),
        "assembly": "all assistant text blocks in transcript order, joined with one newline; unedited",
        "assistantMessages": assistant,
        "textBlocks": len(blocks),
        "toolUses": tools,
        "models": models,
        "usageFromTranscript": usage,
        "firstTimestamp": first,
        "lastTimestamp": last,
        "why": "the harness capture failed after the agent answered: the Agent Eval bridge refused the native "
               "snapshot above its 1 MiB capture bound (see failure.txt); the agent's output is taken from the CLI's own log",
    }
    (run / "run.json").write_text(json.dumps(meta, indent=2) + "\n", encoding="utf-8")
    print(f"recovered {len(text)} chars from {len(blocks)} blocks into {response}")


if __name__ == "__main__":
    main()
