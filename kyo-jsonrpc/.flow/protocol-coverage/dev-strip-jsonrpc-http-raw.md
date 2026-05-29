# DEV-marker strip report

Run id: protocol-coverage-jsonrpc-http
Root: /Users/fwbrasil/workspace/kyo/.claude/worktrees/crispy-swinging-lemur/kyo-jsonrpc-http

## Orphan check

SKIPPED (--no-orphan-check). Pass-1 will run without verifying that
every annotation traces to a VALIDATED_EXCEPTION verdict. This flag
is discouraged outside early-state campaigns.

## Per-file changes

### /Users/fwbrasil/workspace/kyo/.claude/worktrees/crispy-swinging-lemur/kyo-jsonrpc-http/src/main/scala/kyo/JsonRpcHttpTransport.scala

allow=4 dev=0

```
1:// flow-allow: PUBLIC kyo-http-backed WebSocket transport adapter lifting HttpWebSocket text frames to JsonRpcTransport
12:            // flow-allow: initUnscoped because lifetime is managed by the transport close() and Scope.ensure.
25:                            // flow-allow: RawJsonParser.encode converts Structure.Value to standard JSON-RPC wire text;
59:                                        // flow-allow: RawJsonParser.parse converts standard JSON-RPC wire text
```

## Final residual scan

PASS. Zero `// flow-allow:` or `// DEV:` markers remain.

## Totals

- allow conversions: 4
- DEV lines/blocks removed: 0
- files touched: 1

