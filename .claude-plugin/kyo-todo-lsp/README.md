# kyo-todo-lsp

Local LSP server for `.todo` files. Implementation: `kyo-lsp/jvm/src/test/scala/demo/TodoLsp.scala`.

`.todo` line format: each line starts with `TODO`, `DONE`, or `WAIT` followed by free text.

Supported LSP operations:

- `textDocument/hover`: returns the running tally of each state in the file.
- `textDocument/completion`: keyword completion at line start (`TODO`, `DONE`, `WAIT`).

The server reads the open file contents from kyo-lsp's built-in `LspDocumentRegistry`, which is fed by the standard `textDocument/didOpen` / `didChange` notifications.
