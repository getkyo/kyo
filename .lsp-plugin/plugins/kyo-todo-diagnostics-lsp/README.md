# kyo-todo-diagnostics-lsp

LSP server for `.todo-diag` files backed by the kyo-lsp `demo.TodoDiagnostics` JVM
program. Drives the LSP server-initiated `publishDiagnostics` flow and the
`textDocument/documentSymbol` response shape through Claude Code's LSP plugin
harness so we can prove the live wire path against real `kyo-lsp` code.

## Wire surface

- `.todo-diag` syntax: same per-line `TODO/DONE/WAIT body` format as the
  TodoLsp demo. Bound to a different extension to avoid colliding with the
  hover/completion server.
- Diagnostics (published on didOpen + didChange):
  - **Warning**: line starts with an unknown keyword.
  - **Information**: duplicate `keyword body` of an earlier line.
  - **Hint**: keyword line with no body.
- `textDocument/documentSymbol`: returns one outline entry per non-blank line.

## How it's wired

The plugin's marketplace.json declares a single LSP server bound to `.todo-diag`.
The launcher (`run-demo.sh kyo-lsp demo.TodoDiagnostics`) starts the JVM with
the precomputed kyo-lsp classpath and the demo's main class; the demo binds to
Content-Length stdio and serves three handlers.
