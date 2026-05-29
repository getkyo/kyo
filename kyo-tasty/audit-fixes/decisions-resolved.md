# Decisions resolved by safety-first research

Resolved via codebase research applying the principle "safety of APIs is a
major goal". Two of the four defaults were overturned by the safety
analysis. No items remain that need your input; this file is a record of
what changed and why, not a question.

## Fork 1: Subtyping under-determination signal

**Decision: B** (overrides previous default A).

Use a tri-state enum `SubtypeVerdict { Sub, NotSub, Unknown }` for the
return type of `Type.isSubtypeOf`, instead of `Maybe[Boolean]`.

Rationale: `Maybe[Boolean]` re-creates the exact bug the change is meant
to fix. The idiomatic recovery is `.getOrElse(false)`, which silently
collapses `Unknown` back to "not a subtype". The tri-state enum forces the
caller to pattern-match all three cases; Scala 3 exhaustiveness checks
catch any forgotten arm at the use site. The enum is also self-documenting
(`SubtypeVerdict.Unknown` vs `Absent`), so a reader does not have to look
up what `Absent` means in this context. Existing `Maybe[Boolean]` uses in
the codebase (`kyo-schema/.../Json.scala:152,161`,
`kyo-stats-otlp/.../OTLPModel.scala:151`) carry a different semantic
("absent = not specified by user"); reusing the type here would overload
its meaning. Kyo already has many small public tri-state enums
(`SymbolKind`, `Constant`, `Access`, `OS`), so adding one more is
precedented.

Evidence:
- `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala:1091` (current signature)
- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/type_/Subtyping.scala:18,144` (budget + partial-cp source of Unknown)
- `kyo-data/shared/src/main/scala/kyo/Maybe.scala:193` (`getOrElse` foot-gun)
- `kyo-schema/.../Json.scala:152`, `kyo-stats-otlp/.../OTLPModel.scala:151` (existing semantic mismatch)

## Fork 2: Tree decoder coverage decomposition

**Decision: A** (matches previous default).

Split `Symbol.body` decoder coverage into four sub-phases by TASTy spec
category: Category 1 modifiers, Category 2 tag+Nat, Category 3 tag+AST,
Category 4 tag+Nat+AST, Category 5 length-prefixed.

Rationale: `TastyFormat.scala` partitions the tag space into five
contiguous, named numeric ranges with explicit boundary constants
(`firstASTtag`, `firstLengthTreeTag`, etc.). Each sub-phase's acceptance
test reduces to "no `Tree.Unknown` arm fires for any tag in this numeric
range", which is a one-line property test. Semantic-group decomposition
crosses these numeric boundaries (literals span Category 2 and 3), so
coverage tests would need hand-maintained tag lists. Pure numeric-range
decomposition is mechanically equivalent but discards the spec-aligned
names that make code review tractable; the category split keeps both.

Evidence:
- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/reader/TastyFormat.scala:36,80,105,120,132-133`
- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/reader/TreeUnpickler.scala`

## Fork 3: TastyError byteOffset enrichment scope

**Decision: A** (matches previous default).

Add `byteOffset: Long` to every malformed-section TastyError case
(`MalformedSection`, `ClassfileFormatError`, `SnapshotFormatError`).

Rationale: every site that constructs one of these errors already has a
byte-cursor in scope, so plumbing `byteOffset` adds no new state to
propagate. Audit: `JarCentralDirectory.scala` (10+ sites operating on a
ByteView cursor), `ClassfileUnpickler.scala` (cursor loop),
`ConstantPool.scala` (`idx` + view cursor), `ModuleInfoReader.scala`
(classfile reader cursor), `Tasty.scala:190,192,730,735` (decode catch
blocks with cursor available). The alternative (enrich only
structured-payload cases) leaves callers debugging
`"MalformedSection: jar: empty file"` with no anchor, which is the exact
safety gap the enrichment is supposed to close.

Evidence:
- `kyo-tasty/shared/src/main/scala/kyo/TastyError.scala:1-22`
- `kyo-tasty/jvm/src/main/scala/kyo/internal/tasty/query/JarCentralDirectory.scala:89,114,158,169,207,232,247,260,334,341`
- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/classfile/ConstantPool.scala:68-303`
- `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala:730-735`

## Fork 4: Examples package placement

**Decision: C** (overrides previous default A).

Extract the four example files to a sibling `kyo-tasty-examples` sbt
module. They no longer ship in the main `kyo-tasty` artifact.

Rationale: at the current location `kyo.tasty.examples`, a user writing
`import kyo.tasty.examples.*` (or autocompleting under `kyo.tasty.*`) picks
up example code that ships in the main jar. Examples exist to demonstrate
internals, so any unintended re-export through the main artifact widens
the public surface against the safety goal. The internal-package
alternative is semantically wrong because the examples ARE meant to be
user-facing demonstrations; only their distribution should be separate.
The extract is precedented: `kyo-examples` already exists as a separate
module with its own `package examples` (`build.sbt:163,1045`), and
`kyo-tasty` already spawns sibling modules (`kyo-tasty-fixtures`,
`kyo-tasty-bench` at `build.sbt:508,518`). One additional `lazy val`
entry, paid once.

Evidence:
- `kyo-tasty/shared/src/main/scala/kyo/tasty/examples/CodegenExample.scala:1` (current placement)
- `kyo-examples/jvm/src/main/scala/examples/ledger/Models.scala:1`, `kyo-examples/jvm/src/main/scala/examples/leetcode/NQueens.scala` (precedent)
- `build.sbt:163,1045` (kyo-examples module entry)
- `build.sbt:508,518` (kyo-tasty-fixtures, kyo-tasty-bench sibling modules)

## Summary of changes from the defaults

- Fork 1: was `Maybe[Boolean]`, now `enum SubtypeVerdict { Sub, NotSub, Unknown }`.
- Fork 2: unchanged (TASTy spec category split).
- Fork 3: unchanged (byteOffset on every malformed-section case).
- Fork 4: was "stay at `kyo.tasty.examples`", now "extract to sibling `kyo-tasty-examples` sbt module".

## Items requiring your input

None. The safety principle settled all four. Flagging Forks 1 and 4 here
as override notifications so you can object if either flip seems wrong.
