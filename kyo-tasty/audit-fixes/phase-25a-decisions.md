# Phase 25a Decisions

## Doctest pattern chosen: text-scan assertions

Doctests were added as standard scaladoc `{{{ ... }}}` fenced blocks on five public API entry points.

Test strategy: **text-scan assertions only** (no compile-eval). The tests read `kyo/Tasty.scala` via `TestResourceLoader.readText` and assert that:
1. A `{{{` opening and `}}}` closing are present in the scaladoc region surrounding each entry point.
2. A key expression from the doctest body (e.g. `n.asString`, `Tasty.Flags.empty.bits == 0L`, `findClass(`, `topLevelClasses`, `packages`) appears in that region.

Rationale for text-scan over compile-eval: the `Classpath` entry points (`findClass`, `topLevelClasses`, `packages`) require a live classpath instance that is not trivially constructable from a doctest block without full effect-runtime wiring. Compile-eval of such blocks would require a Scala compiler on the test classpath plus Kyo effect infrastructure, violating the no-new-dependency steering rule. Text-scan assertions confirm the structural presence of each doctest block and provide a regression gate if someone removes or misformats a block.

## Pre-existing doctests reconciled

Two of the five target entry points already carried doctest blocks from earlier phases:

- **`Name.apply`** (lines 49-54 in Tasty.scala before this phase): existing block uses `n.asString == "scala.Predef"`. No change needed; the existing doctest satisfies the requirement.
- **`Flags.empty`** (lines 81-84): existing block uses `Tasty.Flags.empty.bits == 0L`. The plan's `isEmpty` predicate does not exist on `Flags` (only `bits` and `contains` are defined), so the existing `bits == 0L` expression is the correct form. No change needed.

## New doctests added

Three doctest blocks were added in this phase:

- **`Classpath.findClass`**: example showing `cp.findClass("scala.Predef")` with `sym.isPresent == true`.
- **`Classpath.packages`**: example showing `cp.packages` with `pkgs.nonEmpty == true`.
- **`Classpath.topLevelClasses`**: example showing `cp.topLevelClasses` with `classes.nonEmpty == true`.

All three examples include `import kyo.AllowUnsafe.embrace.danger` to satisfy the `(using AllowUnsafe)` requirement in scope.

## Convention sweep

- Zero em-dashes / en-dashes.
- No semicolons.
- No `asInstanceOf`.
- No default parameters.
- No `Maybe` / `Present` / `Absent` misuse (not applicable here).
- All new code in `shared/` (cross-platform).
