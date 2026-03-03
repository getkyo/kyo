# Interaction Test Results — bugfix-val

## Summary
- UIs tested: 1 / 7 (in progress)
- Tests executed: 3 / 40
- JFX pass: 3, JFX fail: 0
- Web pass: 3, Web fail: 0
- Bugs found: 0
- Session crashes: 1 (transient, on first render of signals)

## Per-UI Results

### GenericAttrUI (`attrs`)

**Coverage**: Attribute cycling, .on() event handler

| # | Test | JFX Expected | JFX Actual | JFX | Web Expected | Web Actual | Web | Notes |
|---|------|-------------|------------|-----|-------------|------------|-----|-------|
| 28 | Cycle attr | "data-updated" | "data-updated" | PASS | "data-updated" | "data-updated" | PASS | |
| 29 | .on('click') btn | event log populated | "1. on('click') fired" | PASS | N/A | N/A | N/A | Bug 4 FIXED |

### SignalCombinatorUI (`signals`)

**Coverage**: Signal[UI] rendering, combined signals, filter tags

| # | Test | JFX Expected | JFX Actual | JFX | Web Expected | Web Actual | Web | Notes |
|---|------|-------------|------------|-----|-------------|------------|-----|-------|
| 1 | Fill first name | "John" | "John" | PASS | "John" | "John" | PASS | Bug 1 FIXED on web |
