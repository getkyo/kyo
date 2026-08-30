# kernel-discipline, round 3

verdict: BLOCKED
missing rows: 1
weak verdicts: 8

Re-derived with `flags.sh 31a7b4bde9 -- kyo-kernel/shared/src/main/scala/kyo/proto` in
`.claude/worktrees/kyo-root-impl`, clean tree at `7a7cd22ad8`. The script emits **89** rows.

## What is now correct, verified rather than assumed

**The mechanical completeness check passes, for the first time.** All 89 ids appear in `flags.md`
exactly once, and for every one of them the site, the added line and the class match the script's
output character for character (line prefixes compared after whitespace normalisation, since the
table re-truncates some cells). Zero missing, zero extra, zero duplicated, zero line mismatches,
zero class mismatches. The claim that the table is generated from the script's output in one pass
is consistent with the artifact.

**`finally Safepoint.restore(slot, saved)` is present at the tip**, `Eval.scala:355`, inside the
`try` that wraps the guard loop, with `val saved = Safepoint.save(slot)` at `Eval.scala:48`. Round
two's blocking finding is closed. The compiled `Eval.tasty` under
`kyo-kernel/jvm/target/scala-3.8.4/classes/` carries the names `tailrec`, `restore`, `unwinding` and
`settled`, so the shipped source, `@tailrec` included, compiles. The structure check on `@tailrec`
passes: every self-call of `loop` is in tail position inside a match arm, none is under a cast or
inside a `try`, and `reenter`'s `try` contains no call to `loop`.

**The first `moved` group is true.** All 18 rows (F2 to F14, F45, F46, F53, F54, F60) are present in
`31a7b4bde9`'s `Eval.scala` under the substitution `A`->`T`, `S`->`S2`, `S2`->`S3`; F60 is verbatim
without any substitution. I re-ran this as a comparison against the baseline file, not as a reading
of the table.

**Every pinning test the concession contracts name exists**: `ArrowEffectTest.scala:285` "handles
nested per recursion step in bounded stack", `EvalTest.scala:586` "a nested eval shares the thread's
stack and sees none of the outer regions", `EvalTest.scala:120` "the captured continuation is
multi-shot", `EvalTest.scala:168` "each shot of a multi-shot capture resumes from capture-time
state", `EvalTest.scala:626` "regions that fail and recover in sequence cost no stack". Both
concession contracts carry all four parts (justification, scope, protection, pinning test by file).

## D1 The second `moved` group fails the exact test the table says decides `moved`

site: kyo-kernel/shared/src/main/scala/kyo/proto/kernel/internal/Eval.scala:156
quote: `new Kyo.SuspendArrow[IY, OY, EY, VY, Y, Any] with Arrow.Transform[OY[VY], Y, Any]:`

why: The table's preamble says a `moved` verdict "is decided by comparing the line, with the
declared renames applied and whitespace stripped, against the baseline file; it is a test rather
than a recollection". I ran that test on F23, F24, F27, F28, F31 and F32 and it fails for all six.
The baseline lines are `Eval.scala:124`, `:141` and `:158` at `31a7b4bde9`:

```
new Kyo.SuspendArrow[IY, OY, EY, VY, Y, S] with Arrow.Transform[OY[VY], Y, S]:
new Kyo.SuspendContext[VX, CX, Y, S] with Arrow.Transform[VX, Y, S]:
new Kyo.SuspendContextDefault[VX, CX, Y, S] with Arrow.Transform[VX, Y, S]:
```

The shipped lines carry `Any` where the baseline carries `S`. `S` is declared to become `S2`, not
`Any`, so `S`->`Any` is not a rename under any declared mapping: it is a row erased to the top type,
which the same derivation calls out as a failure mode elsewhere ("Erasing the result to a carrier
type instead is the failure `rulings.md` records under Types and safety", derivation.md:86-87). The
group's header sentence, "One of the baseline's own allocations, retyped by the declared renames",
is the hand-written escape hatch the preamble claims to have removed, and it is false.

The consequence is worse for the three `carrier` rows than for the three `allocation` rows. F24, F28
and F32 flag the allocation, and the allocation genuinely is the baseline's, so `moved` is defensible
for them. F23, F27 and F31 flag the `Any`, and that `Any` is **new in this change**, so those three
carriers have never been adjudicated at all. The correct verdict for them is almost certainly the one
already written two groups down for F21 and F42 ("the row half of the storage-boundary read"), which
would have been checkable; `moved` conceals it.

## D2 `Handler.release`'s contract changed, and no derivation declares it and no row carries it

site: kyo-kernel/shared/src/main/scala/kyo/proto/kernel/internal/Handler.scala:27
quote: `/** The abandonment notification: consulted when a holder gives up on an extent's continuation, with the state the region has reached. A`

why: `7a7cd22ad8` rewrites `release`'s scaladoc from "with the state the region was installed with"
to "with the state the region has reached". `derivation.md:107` declares "`kyo/proto/kernel/internal/
Handler.scala`, `recover`'s scaladoc **only**", and `proto-eval-budget/derivation.md` declares
"Nothing else changes, and no other file is touched" for the commits that actually made this edit.
By the region-stack derivation's own standard, stated one line later, "a contract in a doc comment is
still a contract". `flags.md`'s single Handler.scala row, F70, is dismissed as a comment false
positive, so the only stated contract of `release` changed with no declaration and no row. This is
the second time this file has escaped the surface: derivation.md:110 records that "the first version
of this derivation wrongly listed `Handler` as untouched".

## D3 The no-regression claim is contradicted by its own table, and the whole class is `-f 1`

site: reviews/proto-region-stack/evidence.md
quote: `**No row regressed beyond the drift band, and none regressed beyond its own error.**`

why: The same page states "**Drift band on this machine is 3 to 4%**" and lists
`trailingMapsStayLinear` at `670.531 ± 45.381` against `703.402 ± 38.423`, **+4.9%**. That is beyond
the stated band. The second clause is true (the delta, 32.9, is smaller than either error), but the
first is false as written, and `review.md` hardens it into "No row regressed beyond the drift band or
beyond its own error", asserting both. Separately, the header records `-f 1 -wi 5 -i 5`, and the
standard says "`-f 3` for a claim, `-f 1` for diagnosis. Never report a `-f 1` number as a result",
and requires a flagged row to be "confirmed at `-f 3` with the tighter numbers, or diagnosed with its
mechanism, or listed as open". `trailingMapsStayLinear` got none of the three; "error exceeds delta"
states that the run cannot resolve the row, which is a reason to re-run it at `-f 3`, not a
disposition. This is the citation the eight `justified: measurement` verdicts (F73, F76, F78, F80,
F85, F87, F88, F89) rest on. Their own wording, "no confirmed regression", is careful and correct;
the evidence they point at overstates itself.

## D4 `representation assertion` is not the category F47 belongs to

site: kyo-kernel/shared/src/main/scala/kyo/proto/kernel/internal/Eval.scala:266
quote: `if stack.isEmpty then res.asInstanceOf[A < S]`

why: The skill defines the category narrowly: "this value is already union-represented; do not lift
again" at settled re-delivery sites, and "These casts actively *block* the conversion from
corrupting; they are load-bearing". At F47 the scrutinee `res` is bound in the default arm of
`v match` where `v: T < S2`, so it is already spelled as a pending type and no conversion can fire:
there is nothing for the cast to block. The reason the group actually gives, "The machine finished
with no region left, so what it holds is the eval's answer", is a type-identity argument about
`loop`'s re-instantiated parameters, which is not a member of the closed set. For F15 the category is
defensible on the skill's own terms (the scrutinee is a `Kyo.Suspend` node, so the cast does block a
re-lift), but the sentence written under the heading argues the other thing for both.

Worth knowing while re-verdicting: these are not new casts. The baseline carries
`kyo.asInstanceOf[C < S]` at `Eval.scala:45` and `res.asInstanceOf[C < S]` at `Eval.scala:229`, so a
`moved`-plus-retarget provenance was available and would have been checkable against the file.

## D5 "the declared renames" are declared nowhere

site: reviews/proto-region-stack/flags.md:9
quote: `is decided by comparing the line, with the declared renames applied and whitespace stripped, against`

why: No artifact in either change directory declares a rename. `derivation.md:82` gives the new
signature `loop[T, B, C, S2](...)` against the baseline's `loop[A, B, C, S](...)`, from which
`A`->`T` and `S`->`S2` can be reconstructed, but the third leg, `S2`->`S3`, appears in no artifact and
is what F8, F11 and F14 depend on. Eighteen rows' provenance therefore rests on a mapping the reader
has to infer from a signature and then apply by hand. I did infer it and the group is true (see
above), so the defect is that the test is not reproducible from what is written, not that its answer
is wrong. Declaring the three substitutions in one line makes `moved` checkable instead of trusted.

## D6 `review.md` makes two false statements about `flags.md`

site: reviews/proto-region-stack/review.md
quote: `Adjudication: `flags.md`, 88 rows, every one with a verdict, rebuilt after `kernel-discipline``

why: `flags.md` carries 89 rows, says so in its own first paragraph, and `flags.sh` emits 89. The
second: "The rebuild block and `reenter` are the baseline's code, relocated, but not byte-identical,
and `flags.md` names all five differences". `flags.md` names no differences of the rebuild block and
never mentions `reenter` at all; `reenter`'s signature change (`Eval.scala:136`,
`def reenter[P, D, S3](k0: Arrow[P, AX, EX], x: P < S3, cont2: Arrow[Y, D, S3]): D < S3`, against the
baseline's `Arrow[P, AX, EX & S]` and `D < (S & S2)` at `Eval.scala:108`) is described only in
`review.md` itself. A pointer to an adjudication that does not exist is worse than no pointer,
because the reviewer will believe the row was written.

## D7 Two more numbers in `review.md` do not match the tree

site: reviews/proto-region-stack/review.md
quote: `try run(v, Context.empty)`

why: Edit step 7 shows a `run` method that does not exist at the tip: `1f47d6f590` folded `run` into
the guard loop, and the shipped shape is `try` around `while !settled do ... ` with
`finally Safepoint.restore(slot, saved)` at `Eval.scala:355`. A live review applies the sequence as
written, so a step showing a call to a deleted method will stop the review. Second: the evidence
table reports "35 suites, 0 aborted, **1400 passing**" where `evidence.md` and both commit messages
(`1f47d6f590`, `7a7cd22ad8`) say 1399.

## D8 `flags.md` claims an absence that holds only for the path the scan was narrowed to

site: kyo-kernel/shared/src/test/scala/kyo/proto/kernel/internal/EvalTest.scala:603
quote: `private object Boom extends RuntimeException("boom", null, false, false)`

why: `flags.md` closes with "The constructs `rulings.md` names, an `Any` or `Null` **value** carrier,
a `var` outside the engine room, a new type without an argument, a placeholder body, banned
vocabulary, are absent **from the diff** rather than justified in it". The diff of the four commits
adds this line, which carries a `null` and an `object ... extends`, both of which `flags.sh` flags.
They produced no rows because the scan is narrowed to `.../scala/kyo/proto` under `main`, which
excludes the test tree. The `null` itself is defensible (it is `RuntimeException`'s four-argument
cause parameter, the only way to say "no cause" at that Java constructor), so the fix is either to
scope the sentence to the path scanned or to give the line a row.

## Verdicts I accepted that are not literally in the closed set, and why

Recorded so the coordinator can see these were considered rather than missed.

- **`false positive`** on F1, F20 and F70. I opened all three: they are a `//` comment, a `//`
  comment and a `*` scaladoc line, so the flagged token is not a construct. The closed set has no
  member for "the pattern matched prose", and `flags.sh`'s own header says it is "recall-tuned and
  false-positive tolerant on purpose". A verifiable statement of fact is the honest verdict here, and
  demanding a cast category for a comment would be ceremony.
- **`justified: typed pattern`** on F22, F25, F26, F29, F30, F33, F36 to F41. This names step 2 of
  the preference ladder, which sits above the cast tier, so no cast category applies. I checked every
  one of the twelve lines: all are `case x: T @unchecked =>` binders, so the claim is true.
- **`justified`** with a prose argument on F71, the `Stack` type. `new-type` is not a cast, and the
  skill's requirement for a new type is exactly the argument given ("must earn itself against the ones
  already here"). The argument is substantive and checkable.

## The three checks, stated plainly

- **completeness, script-emitted**: PASS. 89 of 89, correct line, correct site, correct class.
- **completeness, reader-applied**: FAIL. One surface item has no row (D2). One `claim` (D3), plus
  three artifact statements contradicted by the tree (D6, D7, D8).
- **verdict quality**: FAIL. Eight weak verdicts: F23, F24, F27, F28, F31, F32 (D1) and F15, F47
  (D4). The remaining 81 carry a category, a citation, a verified `moved`, or a verified fact.
