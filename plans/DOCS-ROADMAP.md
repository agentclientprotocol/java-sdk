# Roadmap: ACP Java SDK 0.9.0 Documentation

> **Created**: 2026-02-10
> **Design version**: 0.9.0

## Overview

Documentation ships in three stages, ordered by launch criticality. Stage 1 creates the Mintlify documentation site (blocks the 0.9.0 blog post). Stage 2 updates tutorial READMEs and SDK metadata for the release. Stage 3 completes remaining pages post-launch. All documentation follows the code-first workflow: verify tutorial code compiles, then write docs based on working code.

## Stage 1: Mintlify Site (Launch-Critical)

### Step 1.1: Navigation and Scaffolding

**Entry criteria**:
- [ ] Read: Claude Agent SDK Mintlify structure (`~/community/mintlify-docs/claude-agent-sdk/`)
- [ ] Read: `~/community/mintlify-docs/mint.json`

**Work items**:
- [ ] UPDATE `~/community/mintlify-docs/mint.json` with ACP Java SDK section under Incubating Projects
- [ ] CREATE directory: `~/community/mintlify-docs/acp-java-sdk/`
- [ ] CREATE directory: `~/community/mintlify-docs/acp-java-sdk/reference/`
- [ ] CREATE directory: `~/community/mintlify-docs/acp-java-sdk/tutorial/`

**Exit criteria**:
- [ ] mint.json validates and includes ACP Java SDK navigation
- [ ] Directory structure created
- [ ] Update `ROADMAP.md` checkboxes

**Deliverables**: Site navigation and directory scaffolding

---

### Step 1.2: Index Page

**Entry criteria**:
- [ ] Step 1.1 complete
- [ ] Read: `~/community/mintlify-docs/claude-agent-sdk/index.md` (template)
- [ ] Read: `~/acp/acp-java/README.md` (source material)

**Work items**:
- [ ] CREATE `~/community/mintlify-docs/acp-java-sdk/index.md` (~120 lines)
- [ ] Content: Overview, three-API-styles table, quick start (client + annotation-based agent), CardGroup to Reference + Tutorial, resource links

**Exit criteria**:
- [ ] Index page renders in dev preview
- [ ] Code examples match SDK README
- [ ] Update `ROADMAP.md` checkboxes

**Deliverables**: `acp-java-sdk/index.md`

---

### Step 1.3: Tutorial Index Page

**Entry criteria**:
- [ ] Step 1.1 complete
- [ ] Read: `~/community/mintlify-docs/claude-agent-sdk/tutorial/index.md` (template)

**Work items**:
- [ ] CREATE `~/community/mintlify-docs/acp-java-sdk/tutorial/index.md` (~60 lines)
- [ ] Content: Overview, prerequisites, 3-part structure table, getting the code

**Exit criteria**:
- [ ] Tutorial index renders and links resolve
- [ ] Update `ROADMAP.md` checkboxes

**Deliverables**: `acp-java-sdk/tutorial/index.md`

---

### Step 1.4: Priority Tutorial Pages (10 Pages)

**Entry criteria**:
- [ ] Step 1.3 complete
- [ ] Read: `~/community/mintlify-docs/claude-agent-sdk/tutorial/01-hello-world.md` (template)
- [ ] VERIFY: `cd ~/projects/acp-java-tutorial && ./mvnw compile -pl module-01-first-contact,module-05-streaming-updates,module-12-echo-agent,module-13-agent-handlers,module-14-sending-updates,module-15-agent-requests,module-16-in-memory-testing,module-28-zed-integration,module-29-jetbrains-integration,module-30-vscode-integration -q`

**Work items**:
- [ ] CREATE `tutorial/01-first-contact.md` — ACP client basics (module-01)
- [ ] CREATE `tutorial/05-streaming-updates.md` — Receiving real-time updates (module-05)
- [ ] CREATE `tutorial/12-echo-agent.md` — Building your first agent (module-12)
- [ ] CREATE `tutorial/13-agent-handlers.md` — All handler types (module-13)
- [ ] CREATE `tutorial/14-sending-updates.md` — Agent-side streaming (module-14)
- [ ] CREATE `tutorial/15-agent-requests.md` — File and permission requests (module-15)
- [ ] CREATE `tutorial/16-in-memory-testing.md` — Testing without subprocesses (module-16)
- [ ] CREATE `tutorial/28-zed-integration.md` — Running agents in Zed (module-28)
- [ ] CREATE `tutorial/29-jetbrains-integration.md` — Running agents in JetBrains (module-29)
- [ ] CREATE `tutorial/30-vscode-integration.md` — Running agents in VS Code (module-30)

Each page follows template structure:
- What You'll Learn
- The Code (with explanation)
- Source Code GitHub link
- Run Command
- Next Module

**Exit criteria**:
- [ ] All 10 pages render in dev preview
- [ ] Code examples match actual tutorial source
- [ ] All cross-links resolve
- [ ] Update `ROADMAP.md` checkboxes

**Deliverables**: 10 tutorial pages in `acp-java-sdk/tutorial/`

---

### Step 1.5: API Reference Page

**Entry criteria**:
- [ ] Step 1.1 complete
- [ ] Read: `~/community/mintlify-docs/claude-agent-sdk/reference/java.md` (template)
- [ ] Read: `~/acp/acp-java/README.md` (source material)
- [ ] Read: `~/acp/acp-java/acp-agent-support/README.md` (source material)

**Work items**:
- [ ] CREATE `~/community/mintlify-docs/acp-java-sdk/reference/java.md` (~500 lines)
- [ ] Sections: Installation, Three-API comparison, Client API, Agent API (annotation/sync/async), Protocol types, Capabilities, Transports, Errors, Test utilities

**Exit criteria**:
- [ ] Reference page renders in dev preview
- [ ] All code examples verified against SDK
- [ ] Update `ROADMAP.md` checkboxes

**Deliverables**: `acp-java-sdk/reference/java.md`

---

### Step 1.6: Stage 1 Review

**Entry criteria**:
- [ ] Steps 1.1-1.5 complete

**Work items**:
- [ ] RUN `~/community/mintlify-docs/dev-preview.sh` to verify all pages render
- [ ] VERIFY all cross-links work (index → tutorial → reference)
- [ ] VERIFY code examples match actual tutorial source
- [ ] CHECK for forbidden marketing language
- [ ] VERIFY no internal implementation details exposed

**Exit criteria**:
- [ ] All pages render without errors
- [ ] Zero forbidden-language violations
- [ ] All code examples match working tutorial code
- [ ] Update `ROADMAP.md` checkboxes

---

## Stage 2: Tutorial READMEs + SDK Updates

### Step 2.1: Lightweight Module READMEs (10 Priority Modules)

**Entry criteria**:
- [ ] Stage 1 complete
- [ ] Read: `~/community/claude-agent-sdk-java-tutorial/module-01-hello-world/README.md` (template)

**Work items**:
- [ ] CREATE README.md for module-01-first-contact (5-6 lines)
- [ ] CREATE README.md for module-05-streaming-updates
- [ ] CREATE README.md for module-12-echo-agent
- [ ] CREATE README.md for module-13-agent-handlers
- [ ] CREATE README.md for module-14-sending-updates
- [ ] CREATE README.md for module-15-agent-requests
- [ ] CREATE README.md for module-16-in-memory-testing
- [ ] UPDATE README.md for module-28 — add Mintlify link at top
- [ ] UPDATE README.md for module-29 — add Mintlify link at top
- [ ] UPDATE README.md for module-30 — add Mintlify link at top

**Exit criteria**:
- [ ] All 10 modules have README.md files
- [ ] Mintlify links point to correct pages
- [ ] Update `ROADMAP.md` checkboxes

**Deliverables**: 7 new READMEs, 3 updated READMEs

---

### Step 2.2: Fix Tutorial README

**Entry criteria**:
- [ ] Step 2.1 complete

**Work items**:
- [ ] UPDATE `~/projects/acp-java-tutorial/README.md`
- [ ] MOVE modules 03, 04, 06, 09, 11 from "Coming Soon" to active (they have source code)
- [ ] ADD Mintlify docs link at top
- [ ] REORGANIZE into 3-part structure: Client → Agent → IDE Integration

**Exit criteria**:
- [ ] No modules with source code listed as "Coming Soon"
- [ ] Mintlify link works
- [ ] Update `ROADMAP.md` checkboxes

**Deliverables**: Updated tutorial README

---

### Step 2.3: SDK README Updates

**Entry criteria**:
- [ ] Step 2.2 complete

**Work items**:
- [ ] UPDATE `~/acp/acp-java/README.md`
- [ ] ADD Mintlify docs link at top of Overview
- [ ] UPDATE Installation: change `0.9.0-SNAPSHOT` to `0.9.0`, remove snapshots repository XML

**Exit criteria**:
- [ ] Version references updated
- [ ] Mintlify link present
- [ ] Update `ROADMAP.md` checkboxes

**Deliverables**: Updated SDK README

---

### Step 2.4: CHANGELOG for 0.9.0

**Entry criteria**:
- [ ] Step 2.3 complete

**Work items**:
- [ ] UPDATE `~/acp/acp-java/CHANGELOG.md`
- [ ] REPLACE "[Unreleased]" with "[0.9.0] - 2026-02-XX"
- [ ] EXPAND with full feature list from SDK development

**Exit criteria**:
- [ ] CHANGELOG reflects 0.9.0 release
- [ ] All major features listed
- [ ] Update `ROADMAP.md` checkboxes

**Deliverables**: Updated CHANGELOG

---

### Step 2.5: Stage 2 Review

**Entry criteria**:
- [ ] Steps 2.1-2.4 complete

**Work items**:
- [ ] VERIFY GitHub rendering of all module READMEs
- [ ] CLICK all cross-links (SDK README → tutorial modules → Mintlify)
- [ ] CONFIRM `./mvnw compile` passes for tutorial project

**Exit criteria**:
- [ ] All links resolve
- [ ] Tutorial compiles
- [ ] Update `ROADMAP.md` checkboxes

---

## Stage 3: Post-Launch Completion (Not Blocking Release)

### Step 3.1: Remaining Mintlify Tutorial Pages (14 Pages)

**Work items**:
- [ ] CREATE pages for modules: 02, 03, 04, 06, 07, 08, 09, 10, 11, 17, 18, 19, 21, 22
- [ ] UPDATE mint.json navigation with expanded tutorial groups

---

### Step 3.2: Remaining Tutorial Module READMEs (14 Modules)

**Work items**:
- [ ] CREATE READMEs for all remaining modules with source code

---

### Step 3.3: SDK Module READMEs

**Work items**:
- [ ] CREATE lightweight READMEs for: acp-core, acp-annotations, acp-test, acp-websocket-jetty

---

### Step 3.4: Enhancements

**Work items**:
- [ ] ADD architecture diagram to Mintlify index
- [ ] ADD Gradle installation instructions to reference page

---

## Execution Order (Stage 1 Priority)

1. mint.json + directory scaffolding (unblocks everything)
2. Index page + tutorial index (site structure)
3. Tutorial pages: 12 (echo agent), 28 (Zed), 01 (first contact) — highest impact first
4. API reference page (largest single item)
5. Remaining 7 tutorial pages
6. Stage 1 review

## Verification

- `~/community/mintlify-docs/dev-preview.sh` — all pages render
- Every code example matches actual tutorial source
- All cross-links: SDK README → tutorial modules → Mintlify
- `./mvnw compile` passes for tutorial project

## Writing Agents

| Agent | Role |
|-------|------|
| `~/.claude/agents/technical-writer.md` | Primary — writes Mintlify pages and READMEs |
| `~/.claude/agents/doc-reviewer.md` | Review — validates against style guide |
| `~/.claude/agents/tutorial-code-sync.md` | Sync — ensures code examples match tutorial source |

## Style Principles

- Direct, plain-spoken, unadorned
- Assume reader competence
- Structure: context, mechanism, consequence
- Forbidden: exciting, game-changing, best-in-class, seamlessly, powerful, intuitive, revolutionary, cutting-edge
- Short paragraphs (3-4 sentences max), tables for comparisons, code blocks liberally
- Accuracy over aesthetics

## Conventions

### Commit Convention

```
Step X.Y: Brief description of what was done
```

### Code-First Workflow

1. Verify tutorial code compiles: `./mvnw compile -pl module-XX-* -q`
2. THEN write docs based on working code
3. Code in docs must match working tutorial code exactly
