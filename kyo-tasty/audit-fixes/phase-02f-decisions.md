# Phase 02f decisions

Decision 1: named-argument form `open(roots, strict = false)` chosen for the one-arg body
Rationale: INV-025 and prep §3 both require the named-arg form; positional `open(roots, false)` and default-param shim are explicitly ruled out.
Time: 2026-05-29T00:00:00Z

Decision 2: test assertion uses a regex-located body line rather than a whole-file `contains` for the negative check
Rationale: `openImpl(roots, strict = false)` appears in other methods (openCached); a whole-file negative assertion would be a false positive. Scoping the check to the line immediately following the one-arg signature avoids that false positive while still pinning INV-025.
Time: 2026-05-29T00:00:00Z

Decision 3: test placed in existing `TastyTest.scala` under `// Phase 02f` section header, not in a new file
Rationale: steering.md test-file naming rule and `feedback_test_placement` both require extending the existing prefix-matching test file for a modified source file. No new file was produced in this phase.
Time: 2026-05-29T00:00:00Z
