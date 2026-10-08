#!/usr/bin/env python3
"""Deterministic import of the pinned ACP v1 specification into a reviewed RFC2119 roster.

Subcommands
  retain  --protocol-repo DIR --jsonrpc-html FILE   copy exact pinned bytes into spec/official/
  check                                             verify hashes, manifest, coverage; compare rendered files
  write                                             like check, but (re)write the rendered files

The manifest under spec/manifest/ is the reviewed authority for what counts as a requirement.
This script never guesses a clause: every imported clause must be found verbatim (modulo
whitespace) inside the declared line range of the retained official bytes, and every RFC2119
keyword occurrence in the selected pages must be claimed by a requirement or an explicit
non-normative disposition. Run with `python3 -I`.
"""
from __future__ import annotations

import argparse
import hashlib
import html as htmllib
import json
import re
import subprocess
import sys
import tomllib
from collections import OrderedDict
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MANIFEST = ROOT / "spec" / "manifest"
OFFICIAL = ROOT / "spec" / "official"
SPEC = ROOT / "spec"

KEYWORD = re.compile(
    r"\b(MUST NOT|MUST|SHOULD NOT|SHOULD|MAY|REQUIRED|SHALL NOT|SHALL|"
    r"NOT RECOMMENDED|RECOMMENDED|OPTIONAL)\b"
)
NORMALIZE = {
    "MUST": "MUST", "MUST NOT": "MUST NOT", "SHOULD": "SHOULD", "SHOULD NOT": "SHOULD NOT",
    "MAY": "MAY", "REQUIRED": "MUST", "SHALL": "MUST", "SHALL NOT": "MUST NOT",
    "RECOMMENDED": "SHOULD", "NOT RECOMMENDED": "SHOULD NOT", "OPTIONAL": "MAY",
}
NATIVE = {"MUST", "MUST NOT", "SHOULD", "SHOULD NOT", "MAY"}
ROLES = {"agent", "client", "both"}
RESPONSIBILITIES = {"sdk", "shared", "application"}
ID_RE = re.compile(r"^ACP-V1-[A-Z0-9]+(-[A-Z0-9]+)+$")


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def ws(text: str) -> str:
    return " ".join(text.split())


def loose(text: str) -> str:
    """Whitespace-normalized text with MDX emphasis, code ticks and link brackets removed."""
    text = re.sub(r"\[([^\]]*)\]\([^)]*\)", r"\1", text)
    text = text.replace("**", "").replace("`", "").replace("\\[", "[").replace("\\]", "]")
    text = text.replace("[", "").replace("]", "")
    return ws(text)


def render_plain(mdx: str) -> str:
    """Rendered plain text of an MDX clause: emphasis, code ticks, links and tags removed."""
    text = re.sub(r"\[([^\]]*)\]\([^)]*\)", r"\1", mdx)
    text = re.sub(r"<[^>]+>", "", text)
    text = text.replace("**", "").replace("`", "").replace("\\[", "[").replace("\\]", "]")
    return ws(text)


def fail(msg: str) -> None:
    print("ERROR: " + msg, file=sys.stderr)
    sys.exit(1)


def load_toml(path: Path) -> dict:
    with path.open("rb") as f:
        return tomllib.load(f)


def parse_lines(spec: str) -> tuple[int, int]:
    m = re.fullmatch(r"(\d+)(?:-(\d+))?", spec)
    if not m:
        fail(f"bad line range {spec!r}")
    a = int(m.group(1))
    b = int(m.group(2) or a)
    if b < a:
        fail(f"bad line range {spec!r}")
    return a, b


def line_selector(a: int, b: int) -> str:
    return f"L{a}" if a == b else f"L{a}-L{b}"


# ----------------------------------------------------------------------------- retain

def retain(args: argparse.Namespace) -> None:
    scope = load_toml(MANIFEST / "scope.toml")
    pin = scope["protocol"]["commit"]
    repo = Path(args.protocol_repo)
    actual = subprocess.check_output(["git", "-C", str(repo), "rev-parse", pin + "^{commit}"]).decode().strip()
    if actual != pin:
        fail(f"{repo} does not resolve {pin}")
    sums = []
    for rel in scope["protocol"]["retained_paths"]:
        data = subprocess.check_output(["git", "-C", str(repo), "show", f"{pin}:{rel}"])
        target = OFFICIAL / "agent-client-protocol" / rel
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
        sums.append((sha256(data), f"agent-client-protocol/{rel}"))
    js = Path(args.jsonrpc_html).read_bytes()
    target = OFFICIAL / "jsonrpc" / "specification.html"
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(js)
    sums.append((sha256(js), "jsonrpc/specification.html"))
    expected = scope["jsonrpc"]["sha256"]
    if sha256(js) != expected:
        fail(f"jsonrpc html sha256 {sha256(js)} != scope.toml {expected}")
    (OFFICIAL / "SHA256SUMS").write_text("".join(f"{h}  {p}\n" for h, p in sums))
    print(f"retained {len(sums)} files under {OFFICIAL}")


# ----------------------------------------------------------------------------- loading

class Sources:
    def __init__(self, scope: dict):
        self.scope = scope
        self.pin = scope["protocol"]["commit"]
        self.bytes: dict[str, bytes] = {}
        sums = (OFFICIAL / "SHA256SUMS").read_text().splitlines()
        self.sums = OrderedDict()
        for line in sums:
            h, p = line.split("  ", 1)
            self.sums[p] = h
        for rel in scope["protocol"]["retained_paths"]:
            key = f"agent-client-protocol/{rel}"
            data = (OFFICIAL / key).read_bytes()
            if key not in self.sums or self.sums[key] != sha256(data):
                fail(f"retained bytes changed or unlisted: {key}")
            self.bytes[rel] = data
        js = (OFFICIAL / "jsonrpc" / "specification.html").read_bytes()
        if self.sums.get("jsonrpc/specification.html") != sha256(js) or sha256(js) != scope["jsonrpc"]["sha256"]:
            fail("jsonrpc/specification.html does not match the recorded digest")
        self.jsonrpc_html = js.decode("utf-8")
        self.jsonrpc_text = jsonrpc_plain(self.jsonrpc_html)
        self.schema = json.loads(self.bytes[scope["protocol"]["schema_path"]])
        schema_sha = sha256(self.bytes[scope["protocol"]["schema_path"]])
        if schema_sha != scope["protocol"]["schema_sha256"]:
            fail(f"schema sha256 {schema_sha} != scope {scope['protocol']['schema_sha256']}")

    def page_lines(self, rel: str) -> list[str]:
        return self.bytes[rel].decode("utf-8").split("\n")

    def digest(self, rel: str) -> str:
        return sha256(self.bytes[rel])


def jsonrpc_plain(html: str) -> str:
    t = re.sub(r"<script.*?</script>", "", html, flags=re.S)
    t = re.sub(r"<style.*?</style>", "", t, flags=re.S)
    t = re.sub(r"<[^>]+>", " ", t)
    t = htmllib.unescape(t)
    return ws(t)


def schema_descriptions(schema: dict) -> list[tuple[str, str]]:
    out = []

    def walk(o, p):
        if isinstance(o, dict):
            for k, v in o.items():
                if k == "description" and isinstance(v, str):
                    out.append((p, v))
                else:
                    walk(v, p + "/" + k.replace("~", "~0").replace("/", "~1"))
        elif isinstance(o, list):
            for i, v in enumerate(o):
                walk(v, p + "/" + str(i))

    walk(schema, "")
    return out


def pointer_get(schema: dict, pointer: str):
    o = schema
    for part in pointer.split("/")[1:]:
        part = part.replace("~1", "/").replace("~0", "~")
        o = o[int(part)] if isinstance(o, list) else o[part]
    return o


# ----------------------------------------------------------------------------- manifest

class Manifest:
    def __init__(self):
        self.scope = load_toml(MANIFEST / "scope.toml")
        self.requirements: list[dict] = []
        self.non_normative: list[dict] = []
        self.prose: list[dict] = []
        self.schema_pointers: list[dict] = []
        self.jsonrpc_sections: list[dict] = []
        self.derived: list[dict] = []
        self.duplicates: list[dict] = []
        self.files: list[str] = []
        for path in sorted(MANIFEST.glob("*.toml")):
            if path.name == "scope.toml":
                continue
            self.files.append(path.name)
            data = load_toml(path)
            for r in data.get("requirement", []):
                r["_file"] = path.name
                self.requirements.append(r)
            for n in data.get("non_normative", []):
                n["_file"] = path.name
                self.non_normative.append(n)
            for p in data.get("prose", []):
                p["_file"] = path.name
                self.prose.append(p)
            for p in data.get("schema_pointer", []):
                p["_file"] = path.name
                self.schema_pointers.append(p)
            for s in data.get("jsonrpc_section", []):
                s["_file"] = path.name
                self.jsonrpc_sections.append(s)
            for d in data.get("derived", []):
                d["_file"] = path.name
                self.derived.append(d)
            for d in data.get("duplicate", []):
                d["_file"] = path.name
                self.duplicates.append(d)


# ----------------------------------------------------------------------------- building

def build(manifest: Manifest, sources: Sources) -> dict:
    scope = manifest.scope
    pin = sources.pin
    roster_revision = scope["roster"]["revision"]
    ids: dict[str, dict] = {}
    out: list[dict] = []
    problems: list[str] = []
    # keyword occurrence index for the prose pages: a whitespace-joined stream of loosened lines, so a
    # keyword wrapped across two source lines ("SHOULD\nNOT") is one occurrence, attributed to its first line
    pages = [p for p in scope["protocol"]["selected_pages"] if p != scope["protocol"]["schema_page"]]
    occurrences: dict[tuple[str, int], list[str]] = OrderedDict()  # (page, stream offset) -> claimants
    occurrence_line: dict[tuple[str, int], int] = {}
    occurrence_keyword: dict[tuple[str, int], str] = {}
    page_lines: dict[str, list[str]] = {}
    page_stream: dict[str, str] = {}
    line_offsets: dict[str, list[tuple[int, int]]] = {}  # per line: (start, end) offsets in the stream
    for page in pages:
        lines = sources.page_lines(page)
        page_lines[page] = lines
        parts, offsets, pos = [], [], 0
        for line in lines:
            l = loose(line)
            if not l:  # blank lines contribute nothing, so a paragraph break is a single space
                offsets.append((pos, pos))
                continue
            if parts:
                pos += 1
            offsets.append((pos, pos + len(l)))
            parts.append(l)
            pos += len(l)
        stream = " ".join(parts)
        page_stream[page] = stream
        line_offsets[page] = offsets
        for m in KEYWORD.finditer(stream):
            ln = next(i + 1 for i, (a, b) in enumerate(offsets) if a <= m.start() <= b)
            occurrences[(page, m.start())] = []
            occurrence_line[(page, m.start())] = ln
            occurrence_keyword[(page, m.start())] = m.group(1)

    def claim(page: str, a: int, b: int, claimant: str, clause_loose: str | None) -> int:
        """Claim keyword occurrences in lines a..b; with a clause, only those inside the clause span."""
        lines = page_lines[page]
        if b > len(lines):
            problems.append(f"{claimant}: line range {a}-{b} beyond {page} ({len(lines)} lines)")
            return 0
        start, end = line_offsets[page][a - 1][0], line_offsets[page][b - 1][1]
        if clause_loose is None:
            lo, hi = start, end
        else:
            window = page_stream[page][start:end]
            idx = window.find(clause_loose)
            if idx < 0:
                problems.append(f"{claimant}: clause not found verbatim in {page}:{a}-{b}")
                return 0
            if window.find(clause_loose, idx + 1) >= 0:
                problems.append(f"{claimant}: clause occurs more than once in {page}:{a}-{b}; narrow the range")
            lo, hi = start + idx, start + idx + len(clause_loose)
        n = 0
        for (pg, off), claimants in occurrences.items():
            if pg == page and lo <= off < hi:
                claimants.append(claimant)
                n += 1
        return n

    def add(req: dict) -> None:
        rid = req["id"]
        if rid in ids:
            problems.append(f"duplicate id {rid}")
        if not ID_RE.match(rid) or ":" in rid:
            problems.append(f"invalid id {rid!r}")
        if len(rid) > 64:  # Agent Eval CompositeNames: at most 64 Unicode scalars
            problems.append(f"id longer than 64 characters: {rid}")
        ids[rid] = req
        out.append(req)

    # ---- imported prose requirements
    for r in manifest.requirements:
        rid = r.get("id", "?")
        for key in ("id", "keyword", "role", "responsibility", "source", "lines", "clause"):
            if key not in r:
                problems.append(f"{rid}: missing {key}")
        if r.get("role") not in ROLES:
            problems.append(f"{rid}: bad role {r.get('role')!r}")
        if r.get("responsibility") not in RESPONSIBILITIES:
            problems.append(f"{rid}: bad responsibility {r.get('responsibility')!r}")
        kw_original = r.get("keyword")
        if kw_original not in NORMALIZE:
            problems.append(f"{rid}: bad keyword {kw_original!r}")
            continue
        kw = NORMALIZE[kw_original]
        page = r["source"]
        if page not in page_lines:
            problems.append(f"{rid}: source {page} is not a selected prose page")
            continue
        a, b = parse_lines(str(r["lines"]))
        clause = ws(r["clause"])
        clause_loose = loose(clause)
        if kw_original not in clause:
            problems.append(f"{rid}: declared keyword {kw_original} absent from clause")
        claim(page, a, b, rid, clause_loose)
        plain = render_plain(clause)
        reason = r.get("reason") or (
            f"Source: {page}:{line_selector(a, b)} at agent-client-protocol@{pin[:12]}; "
            "the specification supplies no separate rationale for this clause."
        )
        if r.get("context"):
            reason += " Context (quoted from the same source): " + ws(r["context"])
        add({
            "id": rid,
            "revision": f"{roster_revision}.{r.get('revision', 1)}",
            "kind": "imported",
            "keyword": kw,
            "original_keyword": kw_original,
            "role": r["role"],
            "responsibility": r["responsibility"],
            "applicability": ws(r["applicability"]) if r.get("applicability") else None,
            "clause_mdx": clause,
            "text": plain,
            "reason": reason,
            "source": {
                "repository": scope["protocol"]["repository"],
                "commit": pin,
                "path": page,
                "selector": line_selector(a, b),
                "sha256": sources.digest(page),
            },
            "mirrors": list(r.get("mirrors", [])),
            "notes": ws(r["notes"]) if r.get("notes") else None,
        })

    # ---- non-normative dispositions of keyword occurrences
    ledger_non_normative = []
    for n in manifest.non_normative:
        page = n["source"]
        if page not in page_lines:
            problems.append(f"non_normative: {page} is not a selected prose page")
            continue
        a, b = parse_lines(str(n["lines"]))
        count = claim(page, a, b, f"non-normative ({n['_file']})", None)
        if count == 0:
            problems.append(f"non_normative {page}:{n['lines']} claims no keyword occurrence")
        ledger_non_normative.append({"source": page, "lines": line_selector(a, b), "reason": ws(n["reason"]), "count": count})

    # ---- duplicate statements in prose pages mapped to a canonical requirement
    ledger_duplicates = []
    for d in manifest.duplicates:
        page = d["source"]
        if page not in page_lines:
            problems.append(f"duplicate: {page} is not a selected prose page")
            continue
        a, b = parse_lines(str(d["lines"]))
        count = claim(page, a, b, f"duplicate of {d['of']}", loose(ws(d["clause"])))
        if count == 0:
            problems.append(f"duplicate {page}:{d['lines']} claims no keyword occurrence")
        ledger_duplicates.append({"source": page, "lines": line_selector(a, b), "clause": ws(d["clause"]), "of": d["of"]})

    # ---- schema descriptions: every keyword occurrence by JSON pointer
    descs = schema_descriptions(sources.schema)
    schema_path = scope["protocol"]["schema_path"]
    pointer_dispositions: dict[str, dict] = {}
    for p in manifest.schema_pointers:
        if "pointer" in p:
            pointer_dispositions[p["pointer"]] = p
    suffix_rules = [p for p in manifest.schema_pointers if "pointer_suffix" in p]
    ledger_schema = []
    schema_kw_total = 0
    for pointer, text in descs:
        kws = KEYWORD.findall(text)
        if not kws:
            continue
        schema_kw_total += len(kws)
        disp = pointer_dispositions.get(pointer)
        if disp is None:
            for rule in suffix_rules:
                if pointer.endswith(rule["pointer_suffix"]):
                    disp = rule
                    break
        if disp is None:
            problems.append(f"schema description without disposition: {pointer} ({kws})")
            continue
        ledger_schema.append({"pointer": pointer, "keywords": kws, "disposition": disp["disposition"],
                              "of": list(disp.get("of", [])) + [r["id"] for r in disp.get("requirements", [])],
                              "reason": ws(disp.get("reason", ""))})
        if disp["disposition"] == "requirement":
            # a requirement whose clause is taken from this schema description
            for r in disp["requirements"]:
                rid = r["id"]
                kw_original = r["keyword"]
                clause = ws(r["clause"])
                if loose(clause) not in loose(text):
                    problems.append(f"{rid}: clause not found in schema description {pointer}")
                if kw_original not in NORMALIZE or kw_original not in clause:
                    problems.append(f"{rid}: keyword {kw_original!r} absent from clause")
                    continue
                reason = (f"Source: {schema_path}#{pointer} at agent-client-protocol@{pin[:12]} "
                          "(schema description text); the specification supplies no separate rationale.")
                if r.get("context"):
                    reason += " Context (quoted from the same source): " + ws(r["context"])
                add({
                    "id": rid,
                    "revision": f"{roster_revision}.{r.get('revision', 1)}",
                    "kind": "imported",
                    "keyword": NORMALIZE[kw_original],
                    "original_keyword": kw_original,
                    "role": r["role"],
                    "responsibility": r["responsibility"],
                    "applicability": ws(r["applicability"]) if r.get("applicability") else None,
                    "clause_mdx": clause,
                    "text": render_plain(clause),
                    "reason": reason,
                    "source": {
                        "repository": scope["protocol"]["repository"],
                        "commit": pin,
                        "path": schema_path,
                        "selector": pointer,
                        "sha256": sources.digest(schema_path),
                    },
                    "mirrors": list(r.get("mirrors", [])),
                    "notes": ws(r["notes"]) if r.get("notes") else None,
                })
        elif disp["disposition"] == "duplicate":
            if not disp.get("of"):
                problems.append(f"duplicate disposition without 'of': {pointer}")
        elif disp["disposition"] != "non-normative":
            problems.append(f"unknown disposition {disp['disposition']!r} for {pointer}")

    # ---- schema.mdx is a generated mirror of schema.json descriptions: prove it
    schema_page = scope["protocol"]["schema_page"]
    mirror_unmapped = []
    desc_loose = [(p, loose(t)) for p, t in descs]
    mdx_lines = sources.page_lines(schema_page)
    mdx_kw_total = 0
    for ln, line in enumerate(mdx_lines, 1):
        kws = KEYWORD.findall(line)
        if not kws:
            continue
        mdx_kw_total += len(kws)
        probe = loose(line)
        if not any(probe in t for _, t in desc_loose):
            mirror_unmapped.append((ln, probe))
    for ln, probe in mirror_unmapped:
        problems.append(f"{schema_page}:{ln} keyword line is not mirrored from any schema.json description: {probe[:80]}")

    # ---- derived structural requirements from schema.json
    for d in manifest.derived:
        rid = d["id"]
        pointers = list(d["pointers"])
        members = []
        for ptr in pointers:
            try:
                node = pointer_get(sources.schema, ptr)
            except (KeyError, IndexError, ValueError):
                problems.append(f"{rid}: pointer {ptr} not in schema")
                continue
            if "properties" in node:
                req = node.get("required") or []
                props = list(node["properties"].keys())
                members.append(f"{ptr}: required {req or 'none'}; declared properties {props}")
            else:
                members.append(f"{ptr}: {node.get('type') or ('oneOf' if 'oneOf' in node else 'anyOf' if 'anyOf' in node else 'schema')}")
        text = ws(d["statement"]) + " Derived structure (generated from the pinned schema): " + " | ".join(members)
        reason = (f"Derived from {schema_path} at agent-client-protocol@{pin[:12]}, JSON Pointers {pointers}. "
                  "This is a structural derivation labelled as such; the schema itself carries no RFC2119 keyword here.")
        add({
            "id": rid,
            "revision": f"{roster_revision}.{d.get('revision', 1)}",
            "kind": "derived",
            "keyword": d["keyword"],
            "original_keyword": d["keyword"],
            "role": d["role"],
            "responsibility": d["responsibility"],
            "applicability": ws(d["applicability"]) if d.get("applicability") else None,
            "clause_mdx": None,
            "text": text,
            "reason": reason,
            "source": {
                "repository": scope["protocol"]["repository"],
                "commit": pin,
                "path": schema_path,
                "selector": pointers[0],
                "sha256": sources.digest(schema_path),
            },
            "pointers": pointers,
            "mirrors": [],
            "notes": ws(d["notes"]) if d.get("notes") else None,
        })

    # ---- JSON-RPC 2.0 clauses incorporated by reference
    jr_text_loose = loose(sources.jsonrpc_text)
    ledger_jsonrpc = []
    for s in manifest.jsonrpc_sections:
        anchor = s["anchor"]
        if f'id="{anchor}"' not in sources.jsonrpc_html and f"id='{anchor}'" not in sources.jsonrpc_html:
            problems.append(f"jsonrpc anchor #{anchor} not found in retained html")
        entry = {"anchor": anchor, "disposition": s["disposition"], "reason": ws(s.get("reason", "")), "ids": []}
        for r in s.get("requirements", []):
            rid = r["id"]
            clause = ws(r["clause"])
            if loose(clause) not in jr_text_loose:
                problems.append(f"{rid}: clause not found in retained JSON-RPC text")
            kw_original = r["keyword"]
            if kw_original not in NORMALIZE or kw_original not in clause:
                problems.append(f"{rid}: keyword {kw_original!r} absent from clause")
                continue
            incorporated_by = ws(r["incorporated_by"])
            reason = (f"Incorporated by reference: {incorporated_by}. Source: JSON-RPC 2.0 specification "
                      f"section #{anchor}, retained bytes sha256 {scope['jsonrpc']['sha256'][:12]}; "
                      "the specification supplies no separate rationale.")
            add({
                "id": rid,
                "revision": f"{roster_revision}.{r.get('revision', 1)}",
                "kind": "incorporated",
                "keyword": NORMALIZE[kw_original],
                "original_keyword": kw_original,
                "role": r["role"],
                "responsibility": r["responsibility"],
                "applicability": ws(r["applicability"]) if r.get("applicability") else None,
                "clause_mdx": clause,
                "text": render_plain(clause),
                "reason": reason,
                "source": {
                    "repository": scope["jsonrpc"]["url"],
                    "commit": None,
                    "path": "jsonrpc/specification.html",
                    "selector": "#" + anchor,
                    "sha256": scope["jsonrpc"]["sha256"],
                },
                "mirrors": list(r.get("mirrors", [])),
                "notes": ws(r["notes"]) if r.get("notes") else None,
            })
            entry["ids"].append(rid)
        ledger_jsonrpc.append(entry)

    # ---- lowercase prose review: each reviewed sentence is covered, imported, or non-normative
    ledger_prose = []
    for p in manifest.prose:
        page = p["source"]
        a, b = parse_lines(str(p["lines"]))
        lines = page_lines.get(page)
        if lines is None:
            problems.append(f"prose: {page} not selected")
            continue
        probe = ws(p["text"])
        joined = ws(" ".join(lines[a - 1:b]))
        if probe not in joined:
            problems.append(f"prose {page}:{p['lines']}: text not found verbatim")
        disp = p["disposition"]
        if disp not in {"covered", "import", "non-normative"}:
            problems.append(f"prose {page}:{p['lines']}: unknown disposition {disp!r}")
        of = list(p.get("of", []))
        if disp == "import":
            rid = p["id"]
            kw = p["keyword"]
            if kw not in NATIVE:
                problems.append(f"{rid}: prose import keyword must be native, got {kw!r}")
            reason = (f"Source: {page}:{line_selector(a, b)} at agent-client-protocol@{pin[:12]}. "
                      "The source states this in lowercase prose without a BCP 14 keyword; it is imported at the "
                      f"strength {kw} as a reviewed interpretation: {ws(p['reason'])}")
            add({
                "id": rid,
                "revision": f"{roster_revision}.{p.get('revision', 1)}",
                "kind": "prose-imported",
                "keyword": kw,
                "original_keyword": "(lowercase prose)",
                "role": p["role"],
                "responsibility": p["responsibility"],
                "applicability": ws(p["applicability"]) if p.get("applicability") else None,
                "clause_mdx": probe,
                "text": render_plain(probe),
                "reason": reason,
                "source": {
                    "repository": scope["protocol"]["repository"],
                    "commit": pin,
                    "path": page,
                    "selector": line_selector(a, b),
                    "sha256": sources.digest(page),
                },
                "mirrors": list(p.get("mirrors", [])),
                "notes": ws(p["notes"]) if p.get("notes") else None,
            })
            of = [rid]
        ledger_prose.append({"source": page, "lines": line_selector(a, b), "text": probe,
                             "disposition": disp, "of": of, "reason": ws(p.get("reason", ""))})

    # ---- mirrors must refer to known ids / pointers
    for r in out:
        for m in r.get("mirrors", []):
            if m.startswith("#") or m.startswith("/"):
                continue
            if ":" not in m and m not in ids:
                problems.append(f"{r['id']}: mirror {m!r} unknown")
    for e in ledger_schema:
        for rid in e["of"]:
            if rid not in ids:
                problems.append(f"schema pointer {e['pointer']} duplicates unknown id {rid}")
    for d in ledger_duplicates:
        if d["of"] not in ids:
            problems.append(f"duplicate {d['source']}:{d['lines']} refers to unknown id {d['of']}")
    for p in ledger_prose:
        for rid in p["of"]:
            if rid not in ids:
                problems.append(f"prose {p['source']}:{p['lines']} refers to unknown id {rid}")

    # ---- unclaimed keyword occurrences
    for key, v in occurrences.items():
        if not v:
            problems.append(f"unclaimed keyword {occurrence_keyword[key]} at {key[0]}:{occurrence_line[key]}")

    if len(out) > 256:  # Agent Eval AuditJuryDescription.MAX_REQUIREMENTS
        problems.append(f"roster has {len(out)} requirements; the producer's single-roster limit is 256")
    # ---- ordering: roster order follows manifest file order then declaration order (already)
    counts = {}
    for r in out:
        counts[r["keyword"]] = counts.get(r["keyword"], 0) + 1
    ledger_occurrences = []
    for key, claimants in occurrences.items():
        ledger_occurrences.append({"source": key[0], "line": occurrence_line[key],
                                   "keyword": occurrence_keyword[key], "claimed_by": claimants})
    return {
        "problems": problems,
        "roster": {
            "kind": "derived-acp-v1-testing-requirements",
            "label": scope["roster"]["label"],
            "revision": roster_revision,
            "protocol": scope["protocol"],
            "jsonrpc": scope["jsonrpc"],
            "keyword_normalization": NORMALIZE,
            "count": len(out),
            "counts_by_keyword": counts,
            "requirements": out,
        },
        "ledger": {
            "prose_pages": pages,
            "occurrences": ledger_occurrences,
            "non_normative": ledger_non_normative,
            "duplicates": ledger_duplicates,
            "schema": ledger_schema,
            "schema_keyword_occurrences": schema_kw_total,
            "schema_mdx_keyword_occurrences": mdx_kw_total,
            "jsonrpc": ledger_jsonrpc,
            "prose": ledger_prose,
            "exclusions": scope["exclusions"],
        },
    }


# ----------------------------------------------------------------------------- rendering

def render_requirements_md(roster: dict) -> str:
    lines = [f"# {roster['label']}", ""]
    lines.append(f"Derived ACP v1 testing requirements, revision `{roster['revision']}`. "
                 f"Pinned `{roster['protocol']['repository']}` commit `{roster['protocol']['commit']}`; "
                 f"stable schema sha256 `{roster['protocol']['schema_sha256']}`. "
                 "This file is generated from `spec/manifest/` by `scripts/import-spec.py`; do not edit it by hand. "
                 "It is not an official replacement specification.")
    lines.append("")
    lines.append(f"Requirements: **{roster['count']}** — " + ", ".join(f"{k}: {v}" for k, v in sorted(roster["counts_by_keyword"].items())))
    lines.append("")
    lines.append("| # | ID | Keyword | Role | Responsibility | Kind | Requirement | Source |")
    lines.append("|---|---|---|---|---|---|---|---|")
    for i, r in enumerate(roster["requirements"], 1):
        src = r["source"]
        where = f"`{src['path']}` `{src['selector']}`"
        text = r["text"].replace("|", "\\|")
        if r.get("applicability"):
            text += " *(Applies when: " + r["applicability"].replace("|", "\\|") + ")*"
        lines.append(f"| {i} | `{r['id']}` | {r['keyword']} | {r['role']} | {r['responsibility']} | {r['kind']} | {text} | {where} |")
    lines.append("")
    return "\n".join(lines)


def render_ledger_md(ledger: dict, roster: dict) -> str:
    L = ["# Coverage ledger", ""]
    L.append("Every RFC2119 keyword occurrence in the selected stable v1 pages, every normative schema "
             "description, the generated schema mirror, the incorporated JSON-RPC sections and the reviewed "
             "lowercase prose, each with exactly one disposition. Generated by `scripts/import-spec.py`.")
    L.append("")
    L.append("## Selected pages and exclusions")
    L.append("")
    for p in roster["protocol"]["selected_pages"]:
        L.append(f"- `{p}`")
    L.append("")
    for e in ledger["exclusions"]:
        L.append(f"- Excluded: `{e['path']}` — {e['reason']}")
    L.append("")
    L.append("## Keyword occurrences in prose pages")
    L.append("")
    by_page: dict[str, list] = OrderedDict()
    for o in ledger["occurrences"]:
        by_page.setdefault(o["source"], []).append(o)
    for page, occ in by_page.items():
        L.append(f"### `{page}` ({len(occ)} occurrences)")
        L.append("")
        L.append("| Line | Keyword | Disposition |")
        L.append("|---|---|---|")
        for o in occ:
            disp = ", ".join(f"`{c}`" if c.startswith("ACP-") else c for c in o["claimed_by"])
            L.append(f"| {o['line']} | {o['keyword']} | {disp} |")
        L.append("")
    L.append("## Non-normative keyword occurrences")
    L.append("")
    L.append("| Source | Lines | Occurrences | Reason |")
    L.append("|---|---|---|---|")
    for n in ledger["non_normative"]:
        L.append(f"| `{n['source']}` | {n['lines']} | {n['count']} | {n['reason']} |")
    L.append("")
    L.append("## Duplicate statements mapped to a canonical requirement")
    L.append("")
    L.append("| Source | Lines | Statement | Canonical |")
    L.append("|---|---|---|---|")
    for d in ledger["duplicates"]:
        L.append(f"| `{d['source']}` | {d['lines']} | {render_plain(d['clause']).replace('|', '\\|')} | `{d['of']}` |")
    L.append("")
    L.append("## Schema descriptions carrying keywords")
    L.append("")
    L.append(f"`schema/v1/schema.json` descriptions contain {ledger['schema_keyword_occurrences']} keyword occurrences; "
             f"`docs/protocol/v1/schema.mdx` contains {ledger['schema_mdx_keyword_occurrences']}, every one of which "
             "was matched to a schema.json description, so the generated page is treated as a mirror and dispositioned by JSON Pointer.")
    L.append("")
    L.append("| JSON Pointer | Keywords | Disposition | Canonical IDs / reason |")
    L.append("|---|---|---|---|")
    for e in ledger["schema"]:
        of = ", ".join(f"`{i}`" for i in e["of"]) or e["reason"]
        L.append(f"| `{e['pointer']}` | {', '.join(e['keywords'])} | {e['disposition']} | {of} |")
    L.append("")
    L.append("## JSON-RPC 2.0 sections")
    L.append("")
    L.append("| Section | Disposition | IDs / reason |")
    L.append("|---|---|---|")
    for e in ledger["jsonrpc"]:
        ids = ", ".join(f"`{i}`" for i in e["ids"]) or e["reason"]
        L.append(f"| `#{e['anchor']}` | {e['disposition']} | {ids} |")
    L.append("")
    L.append("## Lowercase prose reviewed for normative force")
    L.append("")
    L.append("| Source | Lines | Text | Disposition |")
    L.append("|---|---|---|---|")
    for p in ledger["prose"]:
        disp = p["disposition"] + (": " + ", ".join(f"`{i}`" for i in p["of"]) if p["of"] else "") + (" — " + p["reason"] if p["reason"] else "")
        L.append(f"| `{p['source']}` | {p['lines']} | {p['text'].replace('|', '\\|')} | {disp} |")
    L.append("")
    return "\n".join(L)


def rendered(result: dict) -> dict[str, str]:
    roster = result["roster"]
    return {
        "requirements.json": json.dumps(roster, indent=2, ensure_ascii=False) + "\n",
        "requirements.md": render_requirements_md(roster),
        "coverage-ledger.md": render_ledger_md(result["ledger"], roster),
    }


def check(args: argparse.Namespace, write: bool) -> None:
    manifest = Manifest()
    sources = Sources(manifest.scope)
    result = build(manifest, sources)
    for p in result["problems"]:
        print("PROBLEM: " + p)
    if result["problems"]:
        fail(f"{len(result['problems'])} problem(s)")
    files = rendered(result)
    files["requirements.sha256"] = sha256(files["requirements.json"].encode("utf-8")) + "  requirements.json\n"
    changed = []
    for name, content in files.items():
        target = SPEC / name
        if write:
            target.write_text(content)
        else:
            if not target.exists() or target.read_text() != content:
                changed.append(name)
    r = result["roster"]
    print(f"roster {r['revision']}: {r['count']} requirements "
          + ", ".join(f"{k}={v}" for k, v in sorted(r["counts_by_keyword"].items())))
    print(f"prose keyword occurrences claimed: {len(result['ledger']['occurrences'])}; "
          f"schema.json keyword occurrences dispositioned: {result['ledger']['schema_keyword_occurrences']}; "
          f"schema.mdx mirror occurrences: {result['ledger']['schema_mdx_keyword_occurrences']}")
    if changed:
        fail("rendered files are stale: " + ", ".join(changed) + " (run `write`)")
    print("written" if write else "rendered files are current")


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    r = sub.add_parser("retain")
    r.add_argument("--protocol-repo", required=True)
    r.add_argument("--jsonrpc-html", required=True)
    sub.add_parser("check")
    sub.add_parser("write")
    args = ap.parse_args()
    if args.cmd == "retain":
        retain(args)
    elif args.cmd == "check":
        check(args, write=False)
    else:
        check(args, write=True)


if __name__ == "__main__":
    main()
