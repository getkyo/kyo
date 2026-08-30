# kernel-rehearsal, round 3

verdict: BLOCKED
repeats: 1

## R1 [REPEAT] The surface says "recover's scaladoc only". Why is release's scaladoc changing?
site: kyo-kernel/shared/src/main/scala/kyo/proto/kernel/internal/Handler.scala:27
quote: `/** The abandonment notification: consulted when a holder gives up on an extent's continuation, with the state the region has reached. A`
ruling: "you should only update the regions handling?" Standing consequence: "changes stay inside the derivation's declared surface. An improvement outside it is still a finding, because nobody agreed to it."

derivation.md:107 declares "`kyo/proto/kernel/internal/Handler.scala`, `recover`'s scaladoc only",
and that line was itself a correction ("the first version of this derivation wrongly listed
`Handler` as untouched"). The diff rewrites `release`'s scaladoc too, four lines, and no edit in the
sequence and no sentence in the package covers it. The rewrite may well be a correct doc fix, which
is exactly what the ruling says does not matter.

## R2 The package reviews a tree that is not the worktree's
site: reviews/proto-region-stack/review.md:6
quote: `Worktree kyo-root-impl, four commits off 31a7b4bde9, tip 7a7cd22ad8.`

The worktree is five commits off, tip `d197133298` ("pin that a context update outlives an
operation answered after it"). review.md:176 now says of the context change "Pinned by a test that
fails at the baseline", and flags.md's semantic-change section names the test, but that test lives
only in the undeclared fifth commit: it is not in the diff `31a7b4bde9..7a7cd22ad8` the package
names, so the reviewer walking this package can never see the pin he is being told exists.
evidence.md:35 still calls `7a7cd22ad8` "the shipped tip". Either the header, the diff range, and
the evidence all move to `d197133298`, or the pin claim comes out; as written the package claims
evidence its own diff cannot show.

## R3 The suite evidence was measured on a commit that is not what ships, again
site: reviews/proto-region-stack/review.md:150
quote: `| EvalTest | 51 of 54 | 51 of 54 |`

EvalTest defines 54 tests at the baseline, 55 from `1f47d6f590` on (the 10000-cycle guard test),
56 at `d197133298`. "51 of 54" and "205 tests, 202 passing" (review.md:151, 88+63+54) are therefore
impossible outputs of any run at the shipped tip; they were captured at or before `ffc1819ecc`, the
same commit whose benchmark numbers this package says were "discarded rather than carried forward"
for not being what ships. The benchmarks were re-run on the tip; the suite tables were not. And the
summary now contradicts its own detail file: review.md:152 says "1400 passing" (a five-commit-tree
number) while evidence.md:94 says "1399 passing" (four commits). Three different trees are being
reported as one result.

## R4 Edit 7 cannot be applied to the tree edits 1 through 6 just built
site: reviews/proto-region-stack/review.md:111
quote: `try run(v, Context.empty)`

Edit 6 replaced `run` with the guard loop; the shipped tip has no `run`, and its `try` wraps the
`while !settled` loop with the `finally` at Eval.scala:355. The snippet is the intermediate shape
from `ffc1819ecc`, which the final guard rewrite deleted. A live review applies these edits one at
a time with the Edit tool, in this sequence; edit 7's old text does not exist at that point in the
sequence. The same gap in the other direction: no numbered edit installs the Handler.scala doc
change or either EvalTest addition, so applying the nine edits as sequenced does not produce the
diff being reviewed.

## R5 The summary miscounts its own adjudication table
site: reviews/proto-region-stack/review.md:161
quote: `Adjudication: flags.md, 88 rows, every one with a verdict, rebuilt after kernel-discipline`

flags.md says 89 rows, and `flags.sh 31a7b4bde9..7a7cd22ad8` emits 89; I regenerated the table and
all 89 rows match flags.md exactly on id, site, and class. The table is right and the sentence
about it is wrong, in the package whose last two versions were blocked for exactly this kind of
drift between statement and artifact.

## R6 "flags.md names all five differences" — it names none of them
site: reviews/proto-region-stack/review.md:79-80
quote: `The rebuild block and reenter are the baseline's code, relocated, but not byte-identical, and`

flags.md contains no enumeration of the rebuild's differences; the word does not appear in the
file. What it has is the F19 note (pattern guard restructured to an if) and `moved` groups that
lean on "the declared renames", which are themselves declared nowhere in the package, so the moved
test ("comparing the line, with the declared renames applied") cannot be re-run by the reader.
Either list the five differences where the sentence says they are, or point the sentence at where
they actually live.

## R7 The derivation says five locals; the guard has six
site: reviews/proto-region-stack/derivation.md:138
quote: `five locals in apply, none escaping. Protected because out is initialised from v so there is no`

The code has `curr`, `ctx`, `out`, `settled`, `ex`, `unwinding`. flags.md already corrected this
("six locals, not the five an earlier version claimed"); the derivation, which is folded into the
package as its foundation, still carries the stale count.

## R8 A +4.9% on the row this change is most exposed on, and no -f 3 behind the claim
site: reviews/proto-region-stack/evidence.md:46
quote: `| trailingMapsStayLinear | 670.531 ± 45.381 | 703.402 ± 38.423 | +4.9% | error exceeds delta |`

The stated drift band is 3 to 4%; +4.9% is outside it, and the change touches the loop head, which
makes every row mandatory. The skill's own procedure is full class at -f 1 to screen, then -f 3 on
any row outside the band, and "-f 3 for a claim, -f 1 for diagnosis. Never report a -f 1 number as
a result." The follow-up a flagged row is allowed is confirmation at -f 3, a mechanism, or "open";
"error exceeds delta" is a reason to suspect noise, not one of the three. The headline "No row
regressed beyond the drift band or beyond its own error" (evidence.md:67, review.md:158) rests on
the error-bar disjunct of an -f 1 run for exactly the row a region-stack change should be
interrogated on. Run trailingMapsStayLinear (and deepRecursionPaysRescuesOnly) at -f 3 and let the
tighter numbers carry the sentence.

## R9 F67 sits in a group whose justification does not describe it
site: reviews/proto-region-stack/flags.md:87
quote: `| F67 | kernel/internal/Eval.scala:327 | val cont    = stack.cont.asInstanceOf[Arrow[Y, A, S]] | cast |`

The group's justification reads "The row is asserted at Any, the subtype end, so it is widened out
of the way rather than claimed." Every other row in the group is asserted at `Any`; F67 asserts
`Arrow[Y, A, S]`, the type the guard's resume needs, which is a claim, not a widening. The cast is
defensible (the guard resumes at the eval's own type and the loop re-erases it), but the sentence
next to it defends a different cast.

---

## Round 2 items, verified

- **`finally Safepoint.restore` missing from the tree**: fixed. Present at Eval.scala:355 of
  `7a7cd22ad8`, with the no-pinning-test rationale carried in the comment and in flags.md.
- **Table ids misassigned to lines**: fixed. I re-ran `flags.sh 31a7b4bde9..7a7cd22ad8` and
  diffed all 89 (id, site, class) triples against flags.md: identical. Spot-checked the quoted
  lines against the tip file: they match.
- **Benchmarks measured on an unshipped commit**: fixed for the benchmarks (control `31a7b4bde9`,
  variant `7a7cd22ad8`, discarded pair disclosed), but the same provenance defect survives in the
  suite tables (R3), and the fifth commit has since made `7a7cd22ad8` not the tip (R2).
- **Undeclared context-resume semantic change**: declared in all three package files and now
  pinned, but the pin lives in a commit the package does not declare and the named diff does not
  contain (R2).
