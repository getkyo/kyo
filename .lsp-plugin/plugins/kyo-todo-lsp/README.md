# kyo-todo-lsp

LSP server for `.todo` files, backed by the kyo-lsp `demo.TodoLsp` JVM
program. Drives `kyo-lsp` / `kyo-jsonrpc` end-to-end through Claude Code's
LSP plugin harness so we can prove the live wire path (Content-Length
framing, initialize / didOpen / hover / completion) works against the
real kyo server.

## Wire surface

- `.todo` syntax (one entry per line):
  - `TODO Buy milk`
  - `DONE Walk the dog`
  - `WAIT Review PR`
- `textDocument/hover`: hovering over a TODO keyword shows the
  per-state counts in the current file.
- `textDocument/completion`: line-start completion offers `TODO`,
  `DONE`, `WAIT` filtered by typed prefix.

## How it's wired

The plugin's marketplace.json declares a single LSP server bound to
`.todo`. The launcher (`run-demo.sh kyo-lsp demo.TodoLsp`) starts the
JVM with the precomputed kyo-lsp classpath and the demo's main class;
the demo binds to Content-Length stdio and serves the two handlers.
