verdict: BLOCKED
missing rows: 1
weak verdicts: 12

## Re-derivation (not a finding)

`flags.sh 31a7b4bde9 -- kyo-kernel/shared/src/main/scala/kyo/proto` in `kyo-root-impl` emits 76
rows over `Eval.scala` and `Stack.scala`. Re-running the same awk over `31a7b4bde9..a10624dfa4`
also emits 76 rows with the identical id assignment, so `ffc1819ecc` added no flag (its ten added
lines are five comments, two `Safepoint` vals, a `try` and a `finally`, none of which trip a
pattern) and the author's ids are the ids below. Eval.scala line numbers in `flags.md`'s prose are
8 lower than the current file because `ffc1819ecc` inserted 8 lines above them; that offset is
accounted for and is not a finding.

The union of every id listed in `flags.md` is 75 of the 76. F46, F49, F56 and F63 are listed twice
under different verdicts.

## D1 F61 has no row; the table's and the package's "every row has a verdict" is false
site: kyo-kernel/shared/src/main/scala/kyo/proto/kernel/internal/Stack.scala:21
quote: `private var states   = new Array[Any](8)`
why: `flags.sh` emits this line three times, as F61 (`carrier`), F62 (`mutability`) and F63
(`allocation`); F62 and F63 are listed, F61 appears in no group in `flags.md`, so the id union is
75 and the header sentence "76 rows. Every row has a verdict below" and `review.md`'s "Adjudication:
`flags.md`, 76 rows, every one with a verdict" are both untrue. The neighbouring group's prose
("`Array[Any]` for the state column") describes this construct while its id list (F69, F70, F71,
F73) omits it, so the fix is to add F61 to that list and re-check the reconciliation the table
claims to perform.

## D2 Eight allocation rows carry `measurement pending`, which is not a verdict, and the concession contract is unstated
site: kyo-kernel/shared/src/main/scala/kyo/proto/kernel/internal/Stack.scala:20
quote: `private var handlers = new Array[Handler[?, ?, ?, ?, ?]](8)`
why: F60, F63, F65, F67 (constructor) and F72, F74, F75, F76 (`grow`) are verdicted
"`justified: measurement pending`", which is neither a category from the closed set, nor a
measurement cited, nor `moved`, nor `REMOVE`, and the table says so itself: "**This row set blocks
the package until those numbers exist.**" `evidence.md` now carries the full 20-row class on both
legs plus the `-f 3` confirmation and asserts "This closes the `measurement pending` group in
`flags.md`", but `flags.md` was never updated, so the artifact under the gate still fails its own
gate while `review.md` is written as proposable. As an allocation concession this group also needs
all four parts and states none: no justification (pending), no minimal scope, no protective
measure, and no pinning test named by file.

## D3 F57's only verdict is `moved`, and the diff contradicts the provenance
site: kyo-kernel/shared/src/main/scala/kyo/proto/kernel/internal/Eval.scala:291
quote: `val cont    = stack.cont.asInstanceOf[Arrow[Y, A, S]]`
why: F57 appears only in the "Relocated without change (`moved`)" group, captioned "(Eval.scala 42
to 186, and the register-absorbing and rebuild blocks)" and justified as "the baseline's own code",
but this line is at 291 inside `recovered`, a method that does not exist at `31a7b4bde9`, and it
reads `stack`, which does not exist there either (`git show 31a7b4bde9:…/Eval.scala` contains no
`stack.` and there is no `Stack.scala`). The group's own line range excludes it. The cast is a real
storage-boundary read and the paragraph that describes it correctly is the one filed under F50, so
the ids need swapping, not the reasoning.

## D4 F63 carries two verdicts, and `moved` is false for a line in a new file
site: kyo-kernel/shared/src/main/scala/kyo/proto/kernel/internal/Stack.scala:21
quote: `private var states   = new Array[Any](8)`
why: F63 is listed both in the `moved` group ("the baseline's own code", Eval.scala 42 to 186) and
in the constructor-allocation group (`measurement pending`). `Stack.scala` is a new file in this
diff, so nothing in it is moved, and the `moved` list is the one place a row can hide with a
verdict that was never about it.

## D5 The `moved` group's checkable claim is false, and it is what 40 rows rest on
site: kyo-kernel/shared/src/main/scala/kyo/proto/kernel/internal/Eval.scala:130
quote: `def reenter[P, D, S3](k0: Arrow[P, AX, EX], x: P < S3, cont2: Arrow[Y, D, S3]): D < S3 =`
why: The group asserts "Two mechanical differences, both forced and neither semantic" and that
"`git diff -w --ignore-blank-lines` over these ranges shows only those two substitutions". Diffing
the two blocks ignoring indentation shows more: `reenter`'s parameter row went from
`Arrow[P, AX, EX & S]` to `Arrow[P, AX, EX]` and its result from `D < (S & S2)` to `D < S3`, which
is a row change and not the named `T`/`S2` rename; the type parameter renamed is `S2`→`S3`, not the
`A`/`S`→`T`/`S2` the group names; the binder renamed at Eval.scala:159 (`case kyo: Pending[…]` to
`case p: Pending[…]`); two comment lines were rewritten ("entry guard" to "eval's guard", "driven"
to "evaluated"); and the entry point changed shape from a `match` with a guard
(`case res: Kyo.Suspend[…] if !(res.tag.erased <:< kyo.handler.tag.erased)`) to a plain
`if` at Eval.scala:126 with a new `val rebuilt: Y < Any =` binding. The provenance is real, but the
sentence a reviewer would use to verify it does not hold, so the `moved` verdicts on F1 to F13,
F19, F21 to F33, F35, F36, F38, F40, F42, F44, F45 and F52 are currently unverifiable as written.

## D6 F50's entry adjudicates a different line
site: kyo-kernel/shared/src/main/scala/kyo/proto/kernel/internal/Eval.scala:267
quote: `val cont  = stack.cont.asInstanceOf[Arrow[Y, Any, Any]]`
why: `flags.md` writes "**F50** (`stack.cont.asInstanceOf[Arrow[Y, A, S]]`, in `recovered`)", but
F50 is this line at 267, in the settled arm that completes the innermost region, asserted at
`Arrow[Y, Any, Any]`; the quoted line is F57 at 291. F50 appears in no other group's id list, so
the cast at 267 has no verdict of its own.

## D7 F18 is verdicted as an array element re-typing, which it is not
site: kyo-kernel/shared/src/main/scala/kyo/proto/kernel/internal/Eval.scala:126
quote: `if !(susp.tag.erased <:< handler.tag.erased) then`
why: The group "New: reading the innermost region (`cast`)" lists F15, F17, F18 at "116 to 118"
and quotes a three-line block of `stack.handler` / `stack.state` / `stack.cont` casts, verdicting
them "erasure-forced, the closed set's 'array element re-typing at the storage boundary
(`Stack`)'". Line 118 (now 126) is not in that block: it is the foreign-tag test, and `.erased` is
the `Tag` accessor, which the closed set covers under a different instance ("`Tag` storage outside
its opaque scope"). The third line the block quotes is F57/F50's, not F18's, so F18's construct was
never adjudicated. The same group's "F54, F55, F56 (281 to 283)" has the same off-by-one: 283 is
F57, F56 is at 282.

## D8 F58's verdict is an appeal to who asked for it
site: kyo-kernel/shared/src/main/scala/kyo/proto/kernel/internal/Stack.scala:18
quote: `final private[kyo] class Stack:`
why: The verdict reads "`justified`: the carrier the reviewer specified, and no existing type holds
a growable heterogeneous sequence of open regions." "The carrier the reviewer specified" is not a
category from the closed set, a measurement, a `moved` provenance, or a `REMOVE`; it is the same
shape as "consistent with the existing code", which the skill names as not a verdict. The second
clause is a structural argument and can stand alone; the first should go.

## D9 `recovered` is a new method the derivation's surface does not declare
site: kyo-kernel/shared/src/main/scala/kyo/proto/kernel/internal/Eval.scala:286
quote: `@tailrec def recovered(ex: Throwable): A < S =`
why: `derivation.md`'s Surface lists five things inside `Eval.apply`, the last being "`run`:
carries the extent guard". `recovered` is a separate new local method, and by the skill's scaling
table "a new private helper" is its own tier. The `@tailrec` itself checks out: the self-call is
the `case Absent` arm's result, which is tail position, and the `Present` arm crosses into `run`
rather than recursing, matching the comment above it. Add the method to the declared surface.

## D10 Three file-level imports are outside the declared surface
site: kyo-kernel/shared/src/main/scala/kyo/proto/kernel/internal/Eval.scala:16
quote: `import scala.annotation.tailrec`
why: Both derivations restrict `Eval.scala` to "inside `apply` only" and
`reviews/proto-eval-budget/derivation.md` adds "Nothing else changes, and no other file is
touched", but the diff also adds `import kyo.Maybe.Absent`, `import kyo.Maybe.Present` and this
line at file scope. Entailed by the declared work and harmless, and the fix is one line in the
Surface section rather than a code change, but the surface as written does not cover the diff.
