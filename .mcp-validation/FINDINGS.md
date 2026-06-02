# Live MCP + LSP host validation: findings

Five substantive bugs across `kyo-jsonrpc`, `kyo-schema`, and `kyo-mcp` blocked any
spec-conformant MCP / LSP host (Claude Code, VS Code, Neovim, the headless probe drivers
under this folder) from completing a handshake against the demo servers. All five are
fixed; the demos now drive end-to-end through the actual stdio wire.

## Fixed: kyo-mcp didn't accept Claude Code's `2025-11-25` protocolVersion

Claude Code's MCP host advertises `protocolVersion: "2025-11-25"`. `McpInitializeRoute`
echoes the client's version IF it appears in `McpConfig.supportedProtocolVersions` and
otherwise falls back to its own max version. The supported set was hardcoded to
`Set("2025-06-18")` only, so the reply echoed `2025-06-18` and Claude Code disconnected
without raising a wire-level error.

Fix (commit `2ad60c5db`): add `"2025-11-25"` to the supported set so the engine echoes
the client's requested version. The two MCP protocol versions are compatible at the
surface the demos exercise (initialize, tools, resources, prompts, completion); no
schema changes were required.

## Fixed: launcher classpath cache corrupted by partial sbt failure

When `sbt -error export <module>/Test/fullClasspath` runs while another sbt daemon holds
the socket, it prints a stack trace on stdout and exits non-zero. The prior
`.mcp-validation/run-demo.sh` piped sbt's stdout through `tail -1` and unconditionally
overwrote the classpath cache, leaving a 68-char error fragment in `kyo-mcp.classpath`.
The next launch produced `ClassNotFoundException` and Claude Code marked the server
permanently failed.

Fix (commit `f8e6ea747`): refresh runs only when the cached file is missing, empty, or
lacks a colon. sbt output goes to a temp file and only replaces the live cache if sbt
exits 0 AND the output contains a colon. A guard at exec time rejects launches with
non-classpath-shaped cache contents.

## Fixed: stdio transports stalled until stdin EOF

After both fixes above, the demos still hung for Claude Code's MCP host: stdin held
open by the spec-compliant client, so the transport's `Stream.unfold` over
`Console.readLine` (line-delimited) and over `InputStream.read` (Content-Length)
batched the first read's emission and never pushed it downstream until enough reads
filled the default chunk buffer. The first read blocked on the held-open pipe, the
handshake stalled, and the host TERM-killed the server.

Fix (`StdioWireTransport`: commit `ca51bb92d`; `ContentLengthStdioWireTransport`:
commit `c12c6c512`): pass `chunkSize = 1` to `Stream.unfold` so each read flushes a
single-element chunk immediately. The handshake completes the moment the client's
`initialize` arrives and the server stays responsive for the duration of the session.

## Fixed: jsonrpc decode rejected `Null` params

JSON-RPC 2.0 + MCP / LSP specs treat `params` as optional. Real clients send
`tools/list`, `resources/list`, `prompts/list` without a `params` field. kyo-jsonrpc's
request decoder passed the resulting `Structure.Value.Null` straight to
`Structure.decode[In]`, which failed with "expected Record or Variant but got Null".
Every spec-compliant client got `-32602 InvalidParams` for every list call and dropped
the connection.

Fix (commit `ab2204aaa`): coerce `Null` to `Structure.Value.Record(Chunk.empty)` in
both `RequestRoute.handle` and `NotificationRoute.handle` so kyo-schema's case-class
decoder can fall back to default field values.

## Fixed: `Schema[Structure.Value]` rejected plain-JSON tool arguments

`tools/call` with `{"name":"list_directory","arguments":{"path":"."}}` returned
`-32602 Invalid params: Unknown variant 'path'`. The auto-derived
`Schema[Structure.Value]` (from `enum Value derives Schema`) treats Structure.Value as
a discriminated sealed union and expects kyo-schema's tagged-union wrapper format
(`{"Record":{...}}`, `{"VariantCase":{...}}`). The wire's plain JSON object got read as
a variant with `path` as the discriminator, which isn't a known case name.

`tools/list`'s `inputSchema` had the same root cause from the other direction: the
server emitted `{"Obj":{"properties":[],...}}` instead of the spec
`{"type":"object","properties":{...},"required":[...]}`.

Fix (commit `cf78294ae`): replace `derives Schema` on `Structure.Value` with an
explicit shape-aware identity Schema on the companion. Writes emit each variant's
natural shape (Record -> object, Sequence -> array, scalars unwrapped) via
`Schema.writeStructureValue`; reads use a new `Reader.captureStructure()`
introspection point that StructureValueReader implements by returning the current
value tree directly and JsonReader implements by walking the JSON parser to
materialize a Structure.Value. `Schema[Json.JsonSchema]` got the same treatment
(commit `b4d0fcd9d`): explicit Schema emitting JSON Schema Draft 2020-12 shape on
write and dispatching on the `type` discriminator (or `oneOf`) on read.

## Fixed: `inputSchema` empty `properties` / `required`

After the wire-shape fix above, `tools/list` returned the right top-level shape but
`properties` and `required` were still empty for every tool. `McpHandler.tool[In]`
called `Json.jsonSchema[In]` from inside the factory body, where `In` is an abstract
type parameter and `Structure.of[In]` macro-resolves to an empty Product. The
properties list at registration time was always empty regardless of the user's
concrete tool input type.

Fix (commit `b4d0fcd9d`): make `tool` and `toolMulti` `inline` (mirroring the same
pattern in `kyo-http`'s `bodyJson[A]` at `HttpRoute.scala:308-314`) so the macro
expansion happens at the user's call site where `In` is concrete. The private
`ToolHandler` / `ToolMultiHandler` constructors gain `@scala.annotation.publicInBinary`
so inline factories can instantiate them across compilation units.

## Fixed: LSP `processId: null` / `rootUri: null` rejected

Standard LSP clients send `processId: null` (no parent process) and `rootUri: null`
(no workspace root) per the LSP spec. The case-class decoder macro cached
`Schema[T]` (the inner type, not `Schema[Maybe[T]]`) in the per-field sub-schema
array for `Maybe[T]` fields and unconditionally wrapped the read result in
`Present(...)`. The inner Schema for a non-nullable T (Int, String, ...) threw
"expected T but got Null" instead of materializing `Maybe.empty`.

Fix (commit `c12c6c512`): emit `if reader.isNil() then Maybe.empty else
Present(read)` at the field-read site when the inner type is not itself nullable.
Nested `Maybe[Maybe[T]]` / `Maybe[Option[T]]` defer to the inner schema's own nil
handling so `Present(Maybe.empty)` survives the round-trip.

Live-validated through the kyo-lsp TodoLsp demo:
- `initialize` with `processId: null, rootUri: null` returns capabilities + serverInfo.
- `textDocument/hover` at line 0 char 2 returns `TODO: 2  DONE: 1  WAIT: 0`.
- `textDocument/completion` at line 3 char 0 returns three CompletionItems
  (TODO, DONE, WAIT) with the right kind and detail.

## Artifacts left in the repo

- `.mcp-validation/run-demo.sh` ; hardened launcher (cache validation, kills buffered
  tees, only writes good classpath cache).
- `.mcp-validation/validate-launcher.py` ; simulates Claude Code's pipe-attached spawn
  for the initialize round-trip.
- `.mcp-validation/diagnose-handshake.py` ; full MCP handshake driver: initialize ->
  notifications/initialized -> tools/list.
- `.mcp-validation/FINDINGS.md` ; this file.

## What the validation work confirmed

- The kyo-mcp E-parameter campaign (Phases 1-5 across kyo-jsonrpc, kyo-mcp, kyo-lsp)
  is internally correct: 1596 + 202 + 364 + 765 tests pass across kyo-schema,
  kyo-jsonrpc, kyo-mcp, kyo-lsp JVM after all five wire-bug fixes ship.
- The kyo-mcp Filesystem demo drives a full live MCP handshake through stdio with the
  Claude Code wire shape (`protocolVersion: "2025-11-25"`, `arguments: {...}`) and
  returns the correct tool results (`list_directory({"path":"."}) -> hello.txt`,
  `read_file({"path":"hello.txt"}) -> "hi\n"`).
- The kyo-lsp TodoLsp demo drives a full live LSP handshake with the LSP-spec
  Content-Length framing AND null processId / rootUri, returns the right
  capabilities, and serves `textDocument/hover` and `textDocument/completion`
  against an open `.todo` file.
