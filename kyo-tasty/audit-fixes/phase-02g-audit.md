# Phase 02g Audit — OnceCell `// Unsafe:` comment canonicalization

HEAD: `d7330d8f7` ("kyo-tasty Phase 02g: canonicalize OnceCell unsafe comments")

## Per-category verdict

1. **Comments-only diff in OnceCell.scala** — PASS.
   `git show` confirms +3/-6, all hunks inside leading `//` lines.
   No token outside comments changed. `ref.get()`, `compareAndSet`,
   `init().asInstanceOf[AnyRef]`, and the two `.asInstanceOf[A]` lines are
   identical pre/post. Behavior preserved.

2. **All 3 sites canonicalized** — PASS.
   `grep -c "// Unsafe:" OnceCell.scala` = 3 (lines 35, 38, 41), and
   `grep -c "asInstanceOf"` = 3. The legacy "AsInstanceOf justified: ..."
   prefix is gone. Each canonical line is single-line and concise per §415.

3. **OnceCellTest integrity** — PASS.
   Test reads OnceCell.scala via `Files.readString` from a build-root path
   derived from `user.dir` (handles sbt cross-project `kyo-tasty/jvm` cwd by
   going `getParent.getParent`). It scans lines, treats any `trim`-prefix
   `// Unsafe:` as a candidate, skips blank lines, and requires the next
   non-blank line to contain `asInstanceOf`. Asserts `count == 3`. The
   logic genuinely binds comment lines to their immediately-following
   `asInstanceOf` call and would not be satisfied by orphan `// Unsafe:`
   markers elsewhere in the file. Test pins A3.

## Phase 02 closure verdict — PASS

All 7 sub-phases (02a–02g) are committed. Remaining
`import AllowUnsafe.embrace.danger` occurrences in `Tasty.scala` (8 sites)
were spot-checked at lines 392, 553, 753, 769: every site is preceded by a
`// Unsafe:` comment that cites §839 case 3 (initialization/diagnostic-
boundary context: `show`, `_bodyOnce` init thunk, `computeFullName`,
`computeBinaryName`). None are routine accessor bodies that should have
been cleaned. Other `kyo-tasty` files retain `embrace.danger` only in
unpickler / orchestrator / example surfaces that are out of Phase 02 scope.
INV-001/002/011/025 closure intact.

## NOTE for Phase 03

None. Phase 03a (binary bounds checking) is unrelated to AllowUnsafe
restructure. No carry-over from 02g.

## Overall

READY.
