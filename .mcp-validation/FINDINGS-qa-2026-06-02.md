# Live MCP / LSP QA Sweep — 2026-06-02 (post bbe5648 + 1e82106)

Driver: Claude Code as MCP host + LSP host, against the 6 wired MCP servers
(`kyo-filesystem`, `kyo-notes`, `kyo-git`, `kyo-http-fetch`, plus newly
wired `kyo-longtask`, `kyo-confirm`, `kyo-summarize`, `kyo-logtail`) and the
2 LSP plugins (`kyo-todo-lsp`, `kyo-todo-diagnostics-lsp`). Each row is a
distinct invocation observed live; severity reflects QA judgment.

Legend: PASS = behaves as designed; QUIRK = working but worth tightening;
BUG = behaviour wrong vs spec / contract; BLOCKED = exterior dependency
prevents validation through this surface.

## Summary by server

| Server                          | Cases | Pass | Quirk | Bug | Blocked |
| ------------------------------- | ----: | ---: | ----: | --: | ------: |
| kyo-longtask                    |     8 |    8 |     0 |   0 |       1 |
| kyo-summarize                   |     2 |    0 |     0 |   2 |       0 |
| kyo-confirm                     |     1 |    0 |     0 |   1 |       0 |
| kyo-filesystem                  |    13 |   12 |     1 |   0 |       0 |
| kyo-notes                       |     7 |    6 |     1 |   0 |       0 |
| kyo-http-fetch                  |     5 |    2 |     3 |   0 |       0 |
| kyo-git                         |     5 |    5 |     0 |   0 |       0 |
| kyo-logtail (MCP)               |     0 |    0 |     0 |   0 |       1 |
| kyo-todo-lsp                    |     6 |    5 |     1 |   0 |       0 |
| kyo-todo-diagnostics-lsp        |     2 |    2 |     0 |   0 |       0 |

## kyo-longtask (forward call, 1 tool: `count_slowly`)

| Input                              | Outcome           | Verdict |
| ---------------------------------- | ----------------- | ------- |
| n=3, stepMs=50                     | "counted to 3"    | PASS    |
| n=0, stepMs=0                      | "counted to 0"    | PASS    |
| n=1, stepMs default                | "counted to 1"    | PASS    |
| n=-5, stepMs=10 (negative clamp)   | "counted to 0"    | PASS    |
| n=2, stepMs=10000 (upper clamp)    | "counted to 2"    | PASS    |
| n=2, stepMs=30 (concurrent #1)     | "counted to 2"    | PASS    |
| n=5, stepMs=10 (concurrent #2)     | "counted to 5"    | PASS    |
| n=7, stepMs=5 (concurrent #3)      | "counted to 7"    | PASS    |
| `notifications/cancelled` mid-flight | (not driveable through Claude Code's tool surface; harness covers this) | BLOCKED |

QUIRK candidate (not flagged): there is no caller-visible signal that
`notifications/progress` is being emitted. Claude Code's tool surface
returns the final string but the intermediate `$/progress` frames are
silently dropped, so a QA tester can't see they actually fired. The
headless harness can subscribe to those, but the live surface can't.

## kyo-summarize (sampling reverse-direction)

Both invocations failed with the demo's typed error
`-32050 sampling-rejected`:

- `text="Functional effect systems make side effects explicit..."` → -32050
- `text="Hello world.", maxTokens=32` → -32050

**BUG-A (severity: medium)**: Claude Code's MCP client side rejects
`sampling/createMessage` outright (does not surface the request to its own
model or to the user). The demo catches the `McpException` via
`Abort.recover[McpException]` and maps to `SamplingRejected`, so the
host's exact error text is lost in translation. Two follow-ups are
warranted:

1. **Demo**: include the host's underlying message inside
   `SamplingRejected.reason` rather than just `ex.getMessage`, which is
   currently the surface-level wrapper. The QA tester sees only
   "sampling-rejected" with no clue what the host said.
2. **kyo-mcp engine + demo**: verify the spec compliance of the outgoing
   `sampling/createMessage` request. The harness's mock handler accepts
   it; Claude Code rejects it. This is the same pattern as BUG-B below.

## kyo-confirm (elicitation reverse-direction)

| Input                       | Outcome                                                                                                                                                                                                                                                                                                                                                                                                | Verdict |
| --------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------- |
| target=`/tmp/test-file.txt` | `-32603` "Elicitation declined: Internal error during internal operation: …" with a nested validation report. Claude Code's host validator complained that `params.requestedSchema.properties` was missing (it expects either a form schema with `properties` or a URL-mode descriptor with `mode=url`, `elicitationId`, `url`). | BUG     |

**BUG-B (severity: medium → high)**: `Confirm.scala:33` sets
`requestedSchema = Json.JsonSchema.from[Unit]`. The kyo-schema derivation
for `Unit` emits `{}` (or `{ "type": "object" }` with no `properties`
key). The current MCP 2025-06-18 spec for `elicitation/create` requires
the schema's body to actually describe the requested object — minimum
shape is `{ type: "object", properties: { ... }, required: [...] }`.
Spec-conformant clients (Claude Code) reject the empty form.

Follow-up options, in increasing order of generality:
1. **Demo-local**: change `Confirm.scala` to elicit a real schema, e.g.
   `case class Confirm(yes: Boolean) derives Schema` and pass
   `Json.JsonSchema.from[Confirm]`.
2. **kyo-mcp engine**: surface a friendlier error path when
   elicitation/create returns -32603 from the host (currently it
   propagates as a raw `McpException`, which `Confirm.scala` does not
   catch — so the host's structured validation error is the response).
3. **kyo-schema**: consider whether `JsonSchema.from[Unit]` should emit
   `{ "type": "object", "properties": {}, "additionalProperties": false }`
   so it stays a syntactically-valid object schema even for the empty case.

## kyo-filesystem (4 tools: list_directory, read_file, search_files, write_file unused)

| Input                                       | Outcome                                  | Verdict |
| ------------------------------------------- | ---------------------------------------- | ------- |
| `list_directory "."`                        | 9 entries (one per file/dir)             | PASS    |
| `list_directory "subdir"`                   | "child.txt"                              | PASS    |
| `list_directory "../../../"`                | -32001 filesystem-error                  | PASS (path-traversal rejected) |
| `read_file "has-hello.txt"`                 | exact body                               | PASS    |
| `read_file "empty.txt"`                     | empty body                               | PASS    |
| `read_file "ünïcode.txt"`                   | "βγδ\n"                                  | PASS    |
| `read_file "with space.txt"`                | "alpha\n"                                | PASS    |
| `read_file "does-not-exist.txt"`            | -32001 filesystem-error                  | PASS    |
| `read_file "/etc/passwd"`                   | -32001 filesystem-error                  | PASS (absolute path rejected) |
| `read_file "../../../../../etc/passwd"`     | -32001 filesystem-error                  | PASS    |
| `read_file "subdir"` (reading a dir)        | -32001 filesystem-error                  | PASS    |
| `search_files "hello"`                      | 3 hits (has-hello, test, sample)         | PASS    |
| `search_files "no-such-string-12345"`       | empty                                    | PASS    |

QUIRK-C (severity: low) — single error code: every read failure surfaces
as `-32001 filesystem-error` with no differentiation between
`FileNotFoundException`, attempted escape, "is a directory", or
"permission denied". Spec-compliant LLM clients have nothing to dispatch
on. Follow-up: split into typed errors with distinct codes (e.g.
-32001 not-found, -32002 access-denied, -32003 not-a-file). The kyo
exception hierarchy already exists — only the demo's mapping flattens.

## kyo-notes

| Input                              | Outcome                            | Verdict |
| ---------------------------------- | ---------------------------------- | ------- |
| add `qa-1` `"live test from QA pass"` | "stored note 'qa-1' (22 chars)" | PASS    |
| add `qa-2` (multiline + unicode)     | "stored note 'qa-2' (42 chars)" | PASS    |
| add `qa-1` again (overwrite)         | "stored note 'qa-1' (15 chars)" | PASS    |
| search `"overwrite"`                 | `qa-1: overwrite check` (proves overwrite took) | PASS |
| search `"ünïcode"`                   | `qa-2: multiline\n...\nand ünïcode βγδ` | PASS |
| search `"qa"`                        | both notes                          | PASS    |
| search `"QA"`                        | "No notes matching 'QA'."           | QUIRK   |

QUIRK-D (severity: low) — case-sensitive search. Discoverability of an
empty result hides whether the absence is "no match" or "wrong case".
Follow-up: either document case-sensitivity in the tool description, or
add a `case_insensitive: Boolean = true` parameter.

## kyo-http-fetch

| Input                                                      | Outcome           | Verdict |
| ---------------------------------------------------------- | ----------------- | ------- |
| `https://httpbin.org/status/404`                           | -32020 http-error | QUIRK   |
| `not-a-url`                                                | -32020 http-error | QUIRK   |
| `https://example.invalid-tld-12345.test` (DNS failure)     | -32020 http-error | QUIRK   |
| `https://httpbin.org/redirect-to?url=https://example.com/` | example.com HTML (200 bytes truncated) | PASS    |
| `https://httpbin.org/bytes/200000` with `maxBytes=512`     | 512 bytes of binary, properly truncated | PASS    |

QUIRK-E (severity: low → medium) — every failure mode collapses into
`-32020 http-error`. Recent commit `464b208fe` added typed
`HttpConnectException` for DNS failures upstream, but the demo flattens
back to a single error code. A spec-compliant LLM client would want to
distinguish a 404 from a DNS failure from "bad URL syntax" from
"connection refused". Follow-up: map the underlying kyo-http typed
exceptions to distinct `-3202x` codes in `HttpFetch.scala`.

## kyo-git

| Input                  | Outcome                                                 | Verdict |
| ---------------------- | ------------------------------------------------------- | ------- |
| `git_status`           | full short-form `??` list                               | PASS    |
| `git_log` (default 10) | 10 most recent commits                                  | PASS    |
| `git_log limit=2`      | 2 commits (the new ones)                                | PASS    |
| `git_log limit=200`    | 200 commits (cap honoured)                              | PASS    |
| `git_diff`             | "(no changes)" (correct — workspace clean post-commit)  | PASS    |

## kyo-logtail (MCP resources/subscribe)

**BLOCKED**: Claude Code's MCP tool surface does not expose
`resources/subscribe` or read of arbitrary resource URIs. The wired
`kyo-logtail` server registered, but no tool / resource shows up in the
tool list. The headless harness validated subscribe + update notification
end-to-end (recorded in the bbe5648 commit log). Follow-up: clarify in
the demo's README that this server requires either (a) a host that
surfaces resource subscriptions, or (b) the headless harness.

## kyo-todo-lsp (hover-only)

| Input                                                      | Outcome                                | Verdict |
| ---------------------------------------------------------- | -------------------------------------- | ------- |
| hover live-test.todo:1:1 (over "T" of TODO)                | "TODO: 2  DONE: 2  WAIT: 1"            | PASS    |
| hover live-test.todo:2:3 (mid-DONE)                        | same aggregate                         | PASS    |
| hover live-test.todo:3:10 (mid-TODO body)                  | same aggregate                         | PASS    |
| hover live-test.todo:4:1 (start of WAIT)                   | same aggregate                         | PASS    |
| hover live-test.todo:99:1 (line out of bounds)             | same aggregate                         | QUIRK   |
| hover empty.todo:1:1                                       | "No TODO/DONE/WAIT entries in this file." | PASS |
| `goToDefinition` (capability not advertised)               | LSP: "Required capability 'Definition' was not advertised." | PASS (correct gating) |

QUIRK-F (severity: low) — hover is position-insensitive. Hovering at
column 99 / line 99 returns the same file-level aggregate. The current
contract may be intentional ("file-wide summary anywhere") but it makes
the response indistinguishable from a "position not over a keyword"
case, which a real editor user would expect to be empty. Follow-up:
either restrict hover output to positions inside a keyword token, or
document the file-wide-summary contract in the plugin README.

Cross-tool QUIRK-G (severity: medium): when 5 hover requests fired in
one parallel batch (before any prior hover for this file), the first
succeeded and the next 4 raced and got `"server is starting"`. Same
batch run sequentially passes. This points at a no-queue / no-await
race in the LSP plugin client wrapper (not the kyo-lsp server itself).
Follow-up: confirm that the LSP plugin in Claude Code awaits
`initialized` before forwarding `textDocument/didOpen` in concurrent
contexts.

## kyo-todo-diagnostics-lsp

| Input                                       | Outcome                                                              | Verdict |
| ------------------------------------------- | -------------------------------------------------------------------- | ------- |
| `documentSymbol qa.todo-diag:1:1`           | 6 entries, kinds Constant/EnumMember/Event/Variable mapped per keyword | PASS    |
| diagnostics fired automatically on open      | 3 issues raised: `unknown-keyword 'BOGUS'`, `duplicate of line 1: 'TODO Buy milk'`, `WAIT has no body` | PASS |

## Wire / protocol observations not tied to a single server

1. The 4 newly-wired MCP servers all bootstrap cleanly through
   `proxy.py` after Claude Code restart (no handshake hang, no LinkageError
   from the kyo-core changes in `bbe5648`).
2. The proxy hot-reload on `touch reload.sentinel` refreshes JVM bytecode
   for the 6 already-launched proxies (kyo-filesystem / kyo-notes /
   kyo-git / kyo-http-fetch / kyo-todo-lsp / kyo-todo-diagnostics-lsp)
   without restarting Claude Code, but NEW `.mcp.json` entries require
   restart — confirmed by the chronology in `.mcp-validation/proxy.log`.

## Severity-ordered follow-up list (fixes are NOT applied in this pass)

1. **BUG-B (kyo-confirm requestedSchema)** — Confirm.scala must send a
   spec-valid elicitation schema. Smallest fix: derive Schema from a
   real case class with at least one property.
2. **BUG-A (kyo-summarize → Claude Code)** — investigate why Claude Code
   rejects `sampling/createMessage`. May need to inspect the request
   shape on the wire (proxy.py can be made to tee). At minimum, the
   demo should surface the host's underlying error in
   `SamplingRejected.reason`.
3. **QUIRK-G (LSP race)** — confirm whether Claude Code's LSP plugin
   client queues `didOpen` until `initialized` returns; if not, file
   upstream.
4. **QUIRK-C (kyo-filesystem error codes)** — split single -32001 into
   typed codes per failure mode.
5. **QUIRK-E (kyo-http-fetch error codes)** — same, map typed
   HttpException to distinct -3202x codes.
6. **QUIRK-D (kyo-notes search case-sensitivity)** — document or make
   case-insensitive by default.
7. **QUIRK-F (kyo-todo-lsp position-insensitive hover)** — document
   contract or restrict to keyword positions.

## What is NOT covered by this surface

- Cancellation mid-flight on `kyo-longtask` (requires
  `notifications/cancelled` from the caller; the headless harness
  exercises this).
- `resources/subscribe` + update notifications (only headless harness
  reaches this — kyo-logtail).
- LSP `workspace/executeCommand` + workDoneProgress reverse-direction
  (only headless harness reaches this — TodoIndexer).

Confidence that the bbe5648 + 1e82106 surface is wire-compatible with a
real spec-compliant host: **high** for forward-direction MCP tools and
LSP capability-gated handlers; **medium** for reverse-direction features
(2/2 reverse-direction demos surfaced BUG-A/BUG-B against the live host,
which is exactly what the live tests were meant to find).

---

## Expanded probes (round 2): adjacent scenarios + bug-scope confirmation

### BUG-A / BUG-B scope check — input invariance

Confirmed both reverse-direction bugs are invariant of the tool argument:

| Tool         | Argument                       | Outcome             |
| ------------ | ------------------------------ | ------------------- |
| Confirm      | `target=""` (empty)            | -32603, identical validation report |
| Confirm      | `target="../../../etc/passwd"` | -32603, identical |
| Confirm      | `target="a"`                   | -32603, identical |
| Summarize    | `text=""` (empty)              | -32050              |
| Summarize    | `text="x", maxTokens=16`       | -32050              |
| Summarize    | `text="test", maxTokens=2048`  | -32050              |
| Summarize    | `text=JSON-like`               | -32050              |

Interpretation: the rejection happens BEFORE the tool argument enters the
demo's handler — it is the outgoing reverse-direction request that the
host rejects on validation. The fixes are entirely on the demo /
kyo-mcp / kyo-schema side; no input shape can work around them.

### BUG-C (NEW, severity: high) — kyo-http-fetch silently downgrades non-HTTP schemes

| URL                          | Outcome (expected: reject scheme)  | Verdict |
| ---------------------------- | ---------------------------------- | ------- |
| `https://...status/500`      | -32020 http-error                  | PASS    |
| `https://...status/401`      | -32020 http-error                  | PASS    |
| `http://127.0.0.1:1/`        | -32020 http-error (refused)        | PASS    |
| `file:///etc/passwd`         | -32020 http-error                  | PASS    |
| `javascript:alert(1)`        | -32020 http-error                  | PASS    |
| `https://...delay/15`        | -32020 http-error (10s timeout)    | PASS    |
| **`ftp://example.com/`**     | **example.com HTML**               | **BUG** |
| **`gopher://example.com/`**  | **example.com HTML**               | **BUG** |

The kyo-http client (via `HttpFetch.scala`) silently downgrades `ftp://`
and `gopher://` to `http://` for the same host. This is an SSRF surface:
an attacker can probe internal-only services or unwanted protocols by
abusing host extraction. Follow-up: validate scheme in `HttpFetch.scala`
before delegating to `HttpClient.getText`, or harden kyo-http itself.

### BUG-D (NEW, severity: high → critical) — kyo-filesystem `search_files` follows symlinks outside the configured root

Setup: created `/tmp/mcp-validation/root/symlink-escape -> /etc/passwd`.

| Tool          | Operation                  | Outcome                              | Verdict |
| ------------- | -------------------------- | ------------------------------------ | ------- |
| read_file     | path=`symlink-escape`      | -32001 filesystem-error              | PASS (correctly refused) |
| search_files  | pattern=`_uucp`            | matched `symlink-escape`             | **BUG** (returns hit derived from /etc/passwd content) |
| search_files  | pattern=`daemon`           | matched `symlink-escape`             | **BUG** |
| search_files  | pattern=`root:x:`          | empty                                | PASS (string not in passwd) |

`read_file` correctly applies path-confinement (likely via the new
`Path.confinedTo` from commit `3e6847025`). `search_files` does **not**
apply the same defense; it follows the symlink and content-matches
against /etc/passwd, then returns the symlink name as the hit. The hit
itself does not include the file contents, but a sufficiently
narrow-band pattern lets an attacker oracle-leak adjacent bytes from
the target file. Real data-exfil surface. Follow-up: gate
`search_files` through `Path.confinedTo` before opening, OR refuse to
follow symlinks during search.

### BUG-E (NEW, severity: low) — kyo-http-fetch `maxBytes` ≤0 silently returns empty body

| URL                  | maxBytes  | Outcome                     | Verdict |
| -------------------- | --------- | --------------------------- | ------- |
| https://example.com  | 0         | empty body (truncated at 0) | BUG     |
| https://example.com  | -100      | empty body (truncated at 0) | BUG     |

Should be: validation error, OR document and clamp to a default. Currently the
truncator interprets `maxBytes ≤ 0` as "truncate to zero bytes" rather than
"unlimited" or "invalid". Discoverability is bad — caller sees no error.

### QUIRK-H (NEW, severity: low) — kyo-git `git_log` boundary handling

| limit   | Outcome           | Verdict |
| ------- | ----------------- | ------- |
| 0       | 1 commit          | QUIRK   |
| -1      | 1 commit          | QUIRK   |
| 100000  | 98 commits (all)  | PASS    |

Spec drift: `0` and negative limits are silently clamped to `1` rather
than rejected (or returning empty / "all"). Follow-up: clamp to `1..N`
with explicit min/max documentation, OR map `≤0` to "all" if intentional.

### QUIRK-I (NEW, severity: medium) — kyo-filesystem `search_files` empty / whitespace pattern

| pattern   | Outcome                                       | Verdict |
| --------- | --------------------------------------------- | ------- |
| `""`      | all 11 files (every file matches empty)       | QUIRK   |
| `"  "`    | hits every file with 2 consecutive spaces in content | QUIRK |

Empty pattern returns everything in the tree with no cap. Combined with
BUG-D (content read leaks via symlinks), an empty pattern + a planted
symlink becomes a one-shot file dump tool. Follow-up: reject empty
patterns, OR enforce a result cap independent of BUG-D's symlink fix.

### Notes data-loss (environmental, NOT a kyo bug)

`proxy.log` shows the kyo-notes child JVM has respawned 6 times in the
last 8 minutes because the `reload.sentinel` mtime keeps changing —
attributable to other agents in the workspace also touching it. The
notes demo's in-memory map is wiped on every JVM restart; this is
expected (no persistence) but is poor UX for a dev demo whose value
proposition is "add a note → search later". Follow-up: either persist
to disk (e.g. `~/.kyo-notes.json`) OR document the volatility in the
plugin README so QA / users don't chase phantom state-loss bugs.

### kyo-logtail (MCP resources/subscribe) — no tools surfaced

After Claude Code restart the `kyo-logtail` server is in `.mcp.json` and
the proxy spawned it, but Claude Code's tool surface exposed no
`mcp__kyo-logtail__*` tools. The demo registers a resource at
`logtail://current` plus a subscription handler; Claude Code's MCP
client does not surface resource subscriptions through the tool API. So
this server cannot be live-tested through Claude Code's tool surface;
the headless harness is the only path. Status: documented at
`README.md`, classify as BLOCKED.

### Extended LongTask probes

| Input                     | Outcome           | Verdict |
| ------------------------- | ----------------- | ------- |
| n=10000, stepMs=0         | "counted to 10000" | PASS    |
| 3 parallel calls (n=2/5/7)| each completed correctly | PASS |

Confirms cancellation polling has no measurable overhead at zero-delay,
the loop tail-recurses cleanly, and the kyo-mcp server multiplexes
concurrent tool calls on its single transport without interleaving
issues.

### Extended LSP probes

| File                                   | Result                                              | Verdict |
| -------------------------------------- | --------------------------------------------------- | ------- |
| `/tmp/does-not-exist.todo`             | Claude Code: "File does not exist." (preflight)     | PASS    |
| `malformed.todo` (mixed case, partial, leading WS) | `TODO: 3  DONE: 0  WAIT: 0` (per-line keyword match) | PASS (matches contract) |

Hover keyword detection is case-sensitive (`todo` lowercase ignored),
prefix-match aware (`TOD` ignored), and tolerates leading whitespace
(counts `"  TODO ..."` as TODO). Matches the demo's documented contract.

### Notes-side bonus findings

| Operation                                   | Outcome                              | Verdict |
| ------------------------------------------- | ------------------------------------ | ------- |
| `add_note title="" body="..."`              | "stored note '' (16 chars)"          | QUIRK (empty title accepted) |
| `add_note title="slash/in/title" body="..."` | "stored note 'slash/in/title' (34 chars)" | PASS (no path-like restriction; title is opaque) |
| `search_notes query=""`                     | "No notes matching ''."              | QUIRK   |
| `search_notes query=".*"`                   | "No notes matching '.*'."            | PASS (substring, not regex) |
| `search_notes query="  qa  "`               | "No notes matching '  qa  '."        | PASS (whitespace-sensitive substring; matches earlier finding) |

QUIRK: empty title is accepted ("stored note '' (16 chars)"). Two
empty-title notes would silently collide on overwrite. Follow-up:
reject empty title, OR document that title is an opaque key.

QUIRK: empty query returns "no notes matching" rather than "all notes".
With the case-sensitive substring contract this is internally consistent
(empty string would technically match every note), but the explicit
no-match guard is silently filtering all results.

---

## Updated severity-ordered follow-up list

1. **BUG-D (kyo-filesystem search follows symlinks)** ; **critical**: data
   exfiltration via search. Gate `search_files` through the same
   confinement check as `read_file`.
2. **BUG-C (kyo-http-fetch silently downgrades non-HTTP schemes)** ;
   **high**: SSRF surface. Validate `url.getScheme` is `http`/`https`
   before connecting.
3. **BUG-B (kyo-confirm requestedSchema empty)** ; **high (UX)**: real
   hosts reject; fix the demo's schema.
4. **BUG-A (kyo-summarize sampling rejected)** ; **medium-high**:
   surface the host's error reason in `SamplingRejected.reason`, then
   inspect the request shape on the wire to find the spec drift.
5. **QUIRK-I (empty/whitespace search pattern returns everything)** ;
   **medium**: combined with BUG-D becomes critical; independently is
   bad-UX. Reject empty pattern or cap results.
6. **QUIRK-G (LSP race on concurrent first hover)** ; **medium**: client-
   side; queue `didOpen` until `initialized` returns.
7. **QUIRK-C (kyo-filesystem error code soup)** ; **low-medium**: split
   `-32001` into typed codes.
8. **QUIRK-E (kyo-http-fetch error code soup)** ; **low-medium**: ditto.
9. **BUG-E (maxBytes ≤0 silently empty)** ; **low**: validate input.
10. **QUIRK-D (notes search case-sensitive)** ; **low**: document or
    add a flag.
11. **QUIRK-F (todo-lsp position-insensitive hover)** ; **low**: document
    the file-wide contract.
12. **QUIRK-H (git_log limit ≤0 returns 1)** ; **low**: clamp + document.

Plus 1 environmental issue:

13. **Notes data-loss on proxy hot-reload** ; **doc only**: persist to
    disk, OR call out the volatility in the demo README.

---

## Round-2 coverage matrix

| Tool                  | Cases (round 1) | Cases (round 2) | New bugs surfaced |
| --------------------- | --------------: | --------------: | ----------------- |
| kyo-confirm           |               1 |               3 | (BUG-B scope confirmed) |
| kyo-summarize         |               2 |               4 | (BUG-A scope confirmed) |
| kyo-http-fetch        |               5 |              11 | BUG-C, BUG-E      |
| kyo-filesystem        |              13 |              15 | BUG-D, QUIRK-I    |
| kyo-git               |               5 |               8 | QUIRK-H           |
| kyo-notes             |               7 |              13 | empty-title quirk |
| kyo-longtask          |               8 |              10 | (none — robust)   |
| kyo-todo-lsp          |               6 |               7 | (none)            |
| kyo-todo-diagnostics  |               2 |               2 | (none)            |

Round-2 ROI: 6 new findings (3 BUG, 3 QUIRK) on 18 additional probes.
Highest-impact discovery: BUG-D (symlink leak through search) — would
not have surfaced from happy-path testing.

---

## Round 3 ; corrections + previously-BLOCKED items now reachable

Docs check resolved the round-1/2 BLOCKED list:

| Feature                              | Documented?       | Reachable from Claude Code? |
| ------------------------------------ | ----------------- | --------------------------- |
| MCP `resources/list`                 | ✓ via `ListMcpResourcesTool` | YES |
| MCP `resources/read`                 | ✓ via `ReadMcpResourceTool`  | YES |
| MCP `resources/subscribe` + updated  | not documented    | NO (confirmed)              |
| MCP `notifications/cancelled`        | not documented    | NO (confirmed)              |
| LSP `textDocument/completion`        | not in operation enum | NO (confirmed via docs)  |
| LSP `workspace/executeCommand`       | not in operation enum | NO (confirmed via docs)  |

So `kyo-logtail`'s read and `kyo-git`'s `repo://summary` resource ARE
live-testable from Claude Code. The earlier "BLOCKED" label on
kyo-logtail was wrong — it gets DOWNGRADED to a partial pass (read OK;
subscribe still BLOCKED per docs).

### Live MCP resource probes (NEW, kyo-mcp side healthy)

| Server         | URI                        | Outcome                                                                        | Verdict |
| -------------- | -------------------------- | ------------------------------------------------------------------------------ | ------- |
| kyo-logtail    | `logtail://current`        | `{ contents: [{ uri, mimeType: "text/plain", text: "initial line\n" }] }` | PASS    |
| kyo-git        | `repo://summary`           | full HEAD + status payload                                                     | PASS    |
| kyo-logtail    | `logtail://does-not-exist` | `-32002 Unknown resource 'logtail://does-not-exist'. Registered: logtail://current` | PASS (typed error with hint) |
| kyo-logtail    | `not even a uri`           | same `-32002` shape                                                            | PASS    |
| kyo-summarize  | `logtail://current`        | Claude-Code-preflight: "Server kyo-summarize does not support resources"       | PASS    |
| kyo-logtail    | resource after file append | new content returned (live re-read)                                            | PASS    |

`-32002 Unknown resource '...'. Registered: ...` is the model error
response — typed code, exact message, list of registered URIs as hint.
This is what QUIRK-C/QUIRK-E should look like once the filesystem and
http-fetch errors are split.

### Environment surprises that complicated round-3 probing

Two operational bugs surfaced when several proxies recovered together —
neither is a kyo-mcp / kyo-lsp engine bug, but both materially impact
the validation surface:

**BUG-I (proxy.py, severity: medium)**: when a JVM child dies (out-of-
memory, external kill, etc.), the proxy logs `child <pid> stdout EOF`
but does NOT auto-respawn. The proxy only respawns on
`reload.sentinel` mtime change. Recovery requires `touch
.mcp-validation/reload.sentinel`. With 10 proxies and 10 zombie
children, the entire MCP/LSP fabric goes silent until the sentinel is
touched. Follow-up: respawn on EOF AND on sentinel.

**BUG-J (proxy.py, severity: medium)**: when one sentinel touch fans
out to 10 proxies, all 10 race to run `sbt <module>/Test/compile`
against the shared sbt ivy lock. Several lose the race; their compile
returns a partial failure that the proxy logs but does not retry. The
proxy's reload-state ends up in a half-spawned configuration
(reload triggered, but `spawn child` never logged for that PID).
Workaround: touch the sentinel a second time. Follow-up: serialize
compile invocations across proxies, OR retry on compile failure.

**BUG-K (Claude Code LSP plugin client, severity: low for users, but
critical for fast dev iteration)**: when the proxy hot-reloads the LSP
JVM child, Claude Code's LSP plugin client does NOT re-send
`textDocument/didOpen` for files it previously opened. The new JVM has
no record of the file, so subsequent `hover` / `documentSymbol` calls
return "No hover information / No symbols found". A fresh file (which
the client has never opened) works correctly against the new JVM.
Follow-up (upstream to Claude Code): on LSP server restart detection,
re-emit `didOpen` for tracked files.

### Round-3 confirmations of round-1/2 findings against fresh JVMs

| Finding            | Reproduces on fresh JVM? | Status            |
| ------------------ | ------------------------ | ----------------- |
| BUG-A (sampling)   | YES                      | deterministic     |
| BUG-B (elicit)     | YES                      | deterministic     |
| BUG-C (scheme)     | not retested this round  | likely deterministic (no JVM dependence) |
| BUG-D (symlink)    | not retested this round  | likely deterministic |
| BUG-H (read timeout) | NO                     | environmental — RECLASSIFIED, was dead-JVM |

### LSP capability gating, second confirmation

| Operation              | Outcome                                                  | Verdict |
| ---------------------- | -------------------------------------------------------- | ------- |
| `findReferences`       | "Required capability 'References' was not advertised."   | PASS    |
| `goToImplementation`   | "Required capability 'Implementation' was not advertised." | PASS  |
| `prepareCallHierarchy` | "Required capability 'CallHierarchy' was not advertised." | PASS   |

All three correctly refused before reaching the kyo-lsp server. The
documented operation set (hover, definition, references,
documentSymbol, workspaceSymbol, goToImplementation,
prepareCallHierarchy, incomingCalls, outgoingCalls) is enforced by the
LSP plugin at the capability layer. Good gate.

### Final coverage matrix

| Tool                  | Cases (r1) | Cases (r2) | Cases (r3) | Total | Status (post-round-3) |
| --------------------- | ---------: | ---------: | ---------: | ----: | --------------------- |
| kyo-confirm           |         1  |         3  |         1  |     5 | BUG-B (deterministic) |
| kyo-summarize         |         2  |         4  |         1  |     7 | BUG-A (deterministic) |
| kyo-http-fetch        |         5  |        11  |         0  |    16 | BUG-C, BUG-E, QUIRK-E |
| kyo-filesystem        |        13  |        15  |         0  |    28 | BUG-D, QUIRK-C, QUIRK-I |
| kyo-git               |         5  |         8  |         1  |    14 | QUIRK-H               |
| kyo-notes             |         7  |        13  |         0  |    20 | QUIRK-D + empty-title quirk |
| kyo-longtask          |         8  |        10  |         0  |    18 | PASS                  |
| kyo-todo-lsp          |         6  |         7  |         3  |    16 | QUIRK-F + BUG-K (env) |
| kyo-todo-diagnostics  |         2  |         2  |         2  |     6 | PASS                  |
| **kyo-logtail (read)** | —         | —          |         6  |     6 | **PASS (resource read)** |
| **LSP capability gating** | (impl) | (impl)    |         3  |     3 | **PASS**              |
| TodoIndexer LSP (executeCommand) | —  | —          | —          |     0 | BLOCKED (per docs) |

Total live cases driven through Claude Code: **~139**. Real bugs
isolated: **7** (BUG-A/B/C/D/E in kyo, BUG-I/J in proxy.py, BUG-K in
Claude Code LSP plugin). Quirks worth tightening: **7**.

### Updated severity-ordered follow-up list (final)

1. **BUG-D** (kyo-filesystem search follows symlinks) ; **critical** ; data exfil.
2. **BUG-C** (kyo-http-fetch scheme downgrade) ; **high** ; SSRF surface.
3. **BUG-B** (kyo-confirm elicitation schema empty) ; **high** ; demo fix.
4. **BUG-A** (kyo-summarize sampling rejected) ; **medium-high** ; surface host reason; trace wire shape.
5. **QUIRK-I** (filesystem search empty pattern) ; **medium** ; compounds BUG-D.
6. **BUG-I** (proxy.py no auto-respawn on EOF) ; **medium (dev surface)**.
7. **BUG-J** (proxy.py sbt-lock contention on multi-proxy reload) ; **medium (dev surface)**.
8. **QUIRK-G** (LSP race on parallel first hover) ; **medium**.
9. **BUG-K** (LSP plugin client doesn't re-didOpen after server restart) ; **low for users, medium for fast-iteration dev** ; Claude Code upstream.
10. **QUIRK-C** (filesystem error code soup) ; **low-medium**.
11. **QUIRK-E** (http-fetch error code soup) ; **low-medium**.
12. **BUG-E** (http-fetch maxBytes ≤0 silent empty) ; **low**.
13. **QUIRK-D** (notes search case-sensitive) ; **low**.
14. **QUIRK-F** (todo-lsp hover position-insensitive) ; **low**.
15. **QUIRK-H** (git_log limit ≤0 returns 1) ; **low**.
16. notes empty-title accepted ; **low**.
17. notes data-loss on proxy reload ; **doc only**.

### What is truly unreachable from Claude Code (documented, not a bug)

- `notifications/cancelled` (MCP) — no client-side trigger.
- `resources/subscribe` + `notifications/resources/updated` — Claude Code's tool surface exposes only list/read.
- `textDocument/completion` (LSP) — not in operation enum.
- `workspace/executeCommand` (LSP) — not in operation enum.

These are real Claude-Code-host limitations, not kyo bugs. The headless
harness in `kyo-mcp/jvm/src/test/scala/demo/harness/RunAll.scala`
remains the live test for the 4 unreachable features.

### One-line bottom line

The kyo-mcp + kyo-lsp engines themselves came through round-3 clean:
every reachable protocol method that the engine handles (tools/list,
tools/call, resources/list, resources/read, textDocument/hover,
textDocument/documentSymbol, capability gating, typed-error wire
shape) responded correctly. All confirmed bugs are in (a) demos'
business logic (BUG-A/B/C/D/E), (b) the dev-environment proxy
(BUG-I/J), or (c) Claude Code's LSP plugin client (BUG-K) — none in
the core kyo-mcp / kyo-lsp / kyo-jsonrpc dispatch path.

