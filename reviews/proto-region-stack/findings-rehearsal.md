verdict: BLOCKED
repeats: 0

## R1 "neither does one that recovers"? This line is a frame, and it lives until the eval ends.
site: kyo-kernel/shared/src/main/scala/kyo/proto/kernel/internal/Eval.scala:298 (against review.md:82-83)
quote: `run(r.chain(cont), outer)`

The package's edit-6 sentence: "so a region that declines costs no frame and neither does one that
recovers." Declines are `@tailrec`, true. A recovery is not: `recovered` is entered from `run`'s
catch (Eval.scala:309), and its `Present` arm calls `run` again, which evaluates the entire rest of
the eval inside that nested invocation. The outer `run` frame, the `recovered` frame, and the new
`run` frame all stay live until the eval settles. So every recovered panic adds Java frames that
never unwind mid-eval, and N sequential throw-recover cycles inside one eval are N nested frames.
The baseline did not have this: its per-region catch resolved `res` and continued via a tail call of
`loop` at the enclosing depth (`loop(res, kyo.cont, contA.chain(contB), ctx)`, baseline line 226),
net zero frames per recovery. This change's whole thesis is that recursion depth must not track
what the user's program does; the recover path now tracks the count of recovered panics. Either
restructure so `run` consumes the recovered continuation in its own tail loop (one cold allocation
per recovery is fine), or show the measurement and the test that says this depth is bounded, and
fix the sentence either way. As written, the package claims the opposite of what the code does.

## R2 A recover that throws now skips every region outside it. Where is that declared?
site: kyo-kernel/shared/src/main/scala/kyo/proto/kernel/internal/Eval.scala:294 (with 309)
quote: `handler.recover(state, ex) match`

`recovered` runs with no guard around it: it is invoked from `run`'s catch handler, and a catch
handler's own throws are not caught by its try. So when a region's `recover` itself throws, the
panic escapes `apply` with the remaining stacked regions never consulted. The baseline's guards
were nested per region: a throw out of region k's `recover` landed in region k-1's catch and was
consulted there (baseline lines 219-224 nest through each Handle arm's try). The package says
`recover` reading the working state is "the one behavioural change in the set". This is a second
one, on the panic path, and it is declared nowhere: not in the derivation's "Ruled, not open", not
in the open questions. Either wrap the consultation so a throwing recover keeps unwinding through
the outer entries as before, or put the change on the table as its own ruled item with the reason.

## R3 The change is "entering a region is a tail call", and the compiler is not asked to keep it one.
site: kyo-kernel/shared/src/main/scala/kyo/proto/kernel/internal/Eval.scala:47
quote: `def loop[T, B, C, S2](v: T < S2, contA: Arrow[T, B, S2], contB: Arrow[B, C, S2], ctx: Context): A < S =`

Every self-call of `loop` is in tail position (51, 56, 61, 205, 216, 224, 232, 257, 270, 274, 280),
which is the load-bearing property of the whole change, and nothing pins it: one future arm written
as `loop(...) match` or under a `try` silently rebuilds the defect this change exists to remove,
and only the 100k-depth test would notice. `recovered` got `@tailrec`; the loop that is the point
of the change did not. Correct by construction: annotate it, or say in one sentence why the
annotation does not compile here.

## R4 "outlives it by nothing" is only true if the eval ends. Evals do not have to end soon.
site: kyo-kernel/shared/src/main/scala/kyo/proto/kernel/internal/Stack.scala:37-41
quote: `def pop(): Unit = size -= 1`

The scaladoc's defense is "the whole stack is garbage when the eval ends, so what a dropped entry
still points at outlives it by nothing". For an eval that runs long after a deep spike of nesting,
the popped slots pin peak-depth handlers, states, conts, and ctxs, and the grown arrays, for the
eval's entire remaining life; handlers and states reach user objects. And the reason given for not
clearing, "four stores on the path every region exit takes", is a performance claim with no number
in a package whose own standard is that a claim without a number is a hypothesis. Clear the four
slots in `pop` and show the row that says it costs something, or bound the comment's claim to what
is actually true.

## R5 Where is the drift band?
site: reviews/proto-region-stack/evidence.md:34 (against reviews/proto-region-stack/derivation.md:163)
quote: `Full class, both legs back to back on the same machine, `-f 1 -wi 5 -i 5`. Control `31a7b4bde9`,`

The derivation's own evidence contract promises the run "in the form the skill's reporting section
dictates". The delivered table has no drift band in the header, and the skill's sentence is
"Without the band a percentage is uninterpretable": five rows sit at +3% to +7% and are waved off
as "error exceeds delta" against a band the reader is never given. The status-marker vocabulary
and per-leg design markers the section dictates are absent too. The screening protocol itself
(-f 1 wide, -f 3 on the one suspect) is correct; the report's form is not the one the package
promised.

## R6 `Y < S` to `Y < Any` is not a renamed type parameter.
site: reviews/proto-region-stack/review.md:76-78
quote: `the loop's renamed type parameters.`

Edit 5's sentence says `git diff -w` over the relocated ranges shows only the entry reads and the
renamed type parameters. The diff over those ranges also shows the rows erased: `EX & S` to `EX`
on `reenter` and the rebuild patterns, `Y < S` to `Y < Any` on the rebuilt nodes, `D < (S & S2)`
to `D < S3`. Those are adjudicated honestly in flags.md (F20 group, "forced by reading the handler
from the entry"), so the substance is fine; the walk sentence claims a smaller diff than the one I
will be reading. Say the widening in the sentence, since it is the first thing visible in the
range.
