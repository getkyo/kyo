# Phase 02g Decisions

## Change summary

Rewrote the three inline `// AsInstanceOf justified: ...` comments in
`OnceCell.scala` to the canonical `// Unsafe: <one-line rationale>` prefix
per CONTRIBUTING.md §415.

## Files modified

- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/symbol/OnceCell.scala`
  - Line 35 (was multi-line inline comment): `// Unsafe: AnyRef-sentinel pattern; ne-Unset guarantees the stored value is A.`
  - Line 39 (was multi-line inline comment): `// Unsafe: we store A as AnyRef to coexist with the Unset sentinel in AtomicReference[AnyRef].`
  - Line 42 (was multi-line inline comment): `// Unsafe: same sentinel pattern; ref.get() now holds the CAS-winning value.`

## Files produced

- `kyo-tasty/shared/src/test/scala/kyo/OnceCellTest.scala` (new)
  - Test 1: counts `// Unsafe:` comment lines whose next non-blank line contains `asInstanceOf`; asserts count == 3. Pins A3.

## Design decisions

- Kept the rationale text short (one logical clause each) to match the
  `// Unsafe:` convention observed across the codebase.
- Test uses `buildPath` (same pattern as `TastyTest`) so the cross-project
  `user.dir` is resolved correctly on all platforms.
- No public API changes; purely a comment-style cleanup.

## Verification

- `kyo-tasty/Test/compile`: success
- `kyo-tasty/testOnly kyo.OnceCellTest`: 1 test, 1 passed, 0 failed
- HEAD: `6af3b39ee` (unchanged)
