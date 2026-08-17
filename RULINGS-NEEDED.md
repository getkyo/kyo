# Five decisions, each with a recorded default

> **Critical path.** Every probe promised below is done, all eight phases of the harness plan are
> closed, and the sweep replication has reported. **There is no unblocked work left in this campaign.**
> Five decisions remain: C4 (land the safepoint fix or leave the leak), DIS-3 (needs one `@unchecked`
> signed off, default proceed), and IN-3, C3, DIS-4 (default hold).
>
> **C4 is the one with a live consequence.** The other four are about whether to spend effort. C4 is
> about a defect that is in the proto kernel right now.
>
> Three of the four probes weakened their candidate and one strengthened it, so the defaults are not
> uniform and are not guesses.


Every candidate that remains in `optimization-plan.md` is owner-gated. Five are closed (DIS-1 and
DIS-2 measured, C1, IN-1 and IN-2 refuted from stored evidence), so this is the whole rest of the
stream. Each entry below states what the gate is, what the evidence now says that the plan did not
know when it was written, the decision needed in one line, and **the default I will take if you say
nothing**, so this is a flag rather than a stall.

Nothing here is started. No kernel source is landed anywhere; the throwaway worktree holds the C4 fix
and nothing else.

---

## C4, land the safepoint fix or leave the leak

**Not a candidate and not an optimization**: a bug fix, listed here because it is a decision and this
file is where decisions live.

**What is wrong today.** A throw escaping a root `eval` leaks one unit of safepoint depth,
permanently, on a slot that is per thread and therefore shared with every later unrelated computation.
Reproduced: 50 throws lost exactly 50 of 512, one per throw, linear, never recovered. The symptom is
not a wrong answer; `enter` returning false is the ordinary budget-exhausted path, so an affected
thread simply parks and allocates progressively more, forever, with nothing to point at. That is why
it survived this long.

**The fix.** One `try`/`finally` at `Eval.apply`, matching the guard `Eval.partial` already carries.
The delivery arms stay unguarded, deliberately: every normal path balances its `enter`/`exit` pairs
including a drained budget, and all eight catch sites in the drive rethrow, so an exception always
reaches a boundary. There are two boundaries and only one was guarded. 128 proto tests green.

**Cost, measured.** +26.2% on `evalFixedOverhead` (**0.005818 → 0.007342**, the arm means over 3 control
and 2 variant legs, about 1.5 ns per top-level
`eval`), every other row flat, A/A null clean. That row measures nothing but call overhead, so it is
the row that should move and the only one that did.

**Decision needed:** land it, keep it parked, or ask for the cheaper shape to be measured.

**Default if you say nothing:** *keep the fix as written, parked, not landed*. It is exactly correct,
its cost is confined to one row that measures only call overhead, and correctness at 1.5 ns is the
trade this project's rules take by default. A cheaper `catch`-based variant exists but is **not
equivalent**, since `reset` installs a fresh budget rather than the outer drive's exact value, which
differs whenever user code catches a throw from a nested `eval`. Settling that needs a three-sha
chain, not a pair, and I have not run one.

**Where it is.** `parked/c4-safepoint-root-guard` in the throwaway worktree. Not landed, so **the leak
is live in the proto kernel right now**.

---

## DIS-3, unify `Suspend` and `SuspendWith` into one arm

**Gate as written:** needs a cast (`@unchecked`) at the collapsed arm, and the skill requires sign-off
for every new cast.

**What changed since the plan.** This is no longer just candidate 7. DIS-1 is refuted in all three
constructible tier-split forms, and the ledger records what that refutation left open: *whether some
other shape recovers the win without enlarging the compilation unit.* DIS-3 is a direct answer to that
question. `Eval$::dispatch$1` is 607 bytes and refused `hot method too big`; DIS-3 deletes it outright
by collapsing the two suspension node kinds into one, rather than forcing it inline the way the
refuted fix did. The win DIS-1 chased came from inlining that body, and it cost
`trailingMapsStayLinear` 240,000 B/op of destroyed scalar replacement. Deleting the body instead of
inlining it is the one shape that could plausibly get the win without paying that.

**Decision needed:** approve one `@unchecked` typed pattern at the collapsed arm.

**Default if you say nothing:** *proceed*. This is the highest-value candidate left, it is the only
open lead on the campaign's central regression, and the cast is the ladder's step 2 (a typed pattern,
not a bare cast), which is the mildest form the skill's discipline allows. I will surface the exact
spelling for sign-off before it is measured, and it must not share a bracket with DIS-1, since it
subsumes the byte saving and would otherwise be credited for it.

---

## IN-3, take the VarHandle guard chain off `Safepoint.get`

**Gate as written:** raises a memory-model question.

**What changed since the plan.** Two things, pulling in opposite directions.

Against it: the candidate's headline, that one `AtomicReferenceArray.get` force-inlines 434 bytes into
the hot unit, **is not checkable against the compilation log**, which reports that callee at 12 bytes.
Both can be true, since 434 is the transitive VarHandle expansion the log does not show, but it means
the number has never been verified by any instrument in this campaign.

For it: the CPU profile of `nestedPayloadsUnwrapInMaps` puts the safepoint machinery at roughly
**8.4%** of samples in total (`Safepoint.enter` 3.25%, `home` 2.60%, `get` 1.08%,
`VarHandleReferences$Array.getVolatile` 1.08%, `AtomicReferenceArray.get` 0.43%). After `Nested`, that
is the largest kernel-owned block the profile shows. Everything above it belongs to the benchmark or
to boxing.

**Decision needed:** is the memory-model question answerable, and by whom?

**Default if you say nothing:** *hold*. This is the only one of the four where the default is not to
proceed, because a memory-model question is not one I can resolve by measurement, and getting it wrong
produces a bug that no benchmark row would ever show.

**The probe is done, and it re-bases the candidate.** Bytecode sizes, read through the harness:

    Safepoint.home    17B      Safepoint.get     28B
    Safepoint.enter   50B      Safepoint.exit    19B
    poll surface (get + enter + exit + home)     145B
    Eval$::loop                                  1703B   <- the hot unit

The candidate says the poll is **712 B, 41.7%** of a 1708-byte unit. The unit checks out at 1703 B.
The poll's *declared* surface is **145 B**, so the other ~567 B is C2 expanding the VarHandle chain
in place, exactly as the 434-byte claim implies. javap reports what a method declares, never what the
JIT expanded into it, so this **bounds the claim without settling it**, and the instrument that would
settle it (a compiled-code dump) does not exist in this harness.

Two consequences for the ruling. The byte-count story is **not verifiable with what we have**, and its
stated falsifier is already dead besides: the compilation log shows every safepoint method inlined at
23 to 24 sites, so there is no refusal for a smaller poll to flip. What survives is the CPU profile,
which puts the safepoint machinery at ~8.4% of samples and is the largest kernel-owned block after
`Nested`. **If IN-3 is revived it should be re-stated on the profile, not on the byte count**, and its
falsifier changed accordingly.

---

## C3, move the park trigger out of the delivery arm into the chain structure

**Gate as written:** wants a conversation first. A design change to preemption.

**What changed since the plan.** C1's refutation does *not* touch it, which is worth saying because
the two look adjacent. C1 targeted the first 16 KB/op, the `Nested` box, and the collapsed allocation
profile showed all 3,790 of those samples come from the benchmark's own `boxed` at the lift boundary,
so there was nothing for a kernel edit to remove. C3 targets the *second* 16 KB/op, the `Suspend`
node, which is a different allocation with a different origin and is untouched by that reading. The
plan calls it the highest ceiling of the allocation set and the only candidate reaching that second
16 KB, and nothing measured since contradicts that.

**Decision needed:** the conversation the plan asks for, about changing preemption.

**Default if you say nothing:** *hold, and prepare*. I will not start a preemption redesign on a
default.

**The probe is done, and it needed no run.** The Phase 5 collapsed profile of
`nestedPayloadsUnwrapInMaps` already carries the answer, and the second 16 KB/op has a name:

    kyo.kernel.bench.ProtoKernelBench$$anon$95   minted at ProtoKernelBench$.ask
        3676 samples, ~1.93 GB, 49.1% of the row's allocation

The plan's arithmetic checks out against it: the row is 32,080.04 B/op, the plan splits that into
`Nested` 16,043 and the suspension node 15,985, and 49.1% of 32,080 is 15,760. So the second 16 KB/op
is the **suspension node itself, minted at the benchmark's own `ask`**, which is `ArrowEffect.suspend`
expanding into an anonymous class instance at the suspend site.

That is not obviously something a park-trigger change can remove: a suspension has to be represented
by a value, and this is that value, allocated where the suspension is created rather than in the
delivery arm the candidate proposes to change. **I am not calling C3 refuted on that**, because unlike
C1 it rests on a design claim about preemption that I have not read closely enough to judge, and the
mechanism might be that fewer suspensions get minted rather than that the node gets cheaper.

What the reading does establish is the question the conversation should start from: *by what mechanism
does moving the park trigger stop `ask` from minting this node?* If there is no answer to that, the
candidate's stated field cannot move. Same caveat as C1: one row, and C3 names the suspension and
handler rows generally.

---

## DIS-4, replace the drive's linear `instanceof` chains with a node-kind switch

**Gate as written:** adds a field to every `Arrow`; API-adjacent. Cost high, touches every node type.

**What changed since the plan.** Nothing directly, but the campaign has learned that its own
allocation figures are exact to about 5e-5 relative, measured across four A/A sets. That matters here
because the candidate's own first field is `gc.alloc.rate.norm`, on the theory that a kind field may
cost a word per node. The harness can now resolve that to within about 128 bytes on a 2.5 MB row, so
the cheap half of this candidate is decisively answerable before the expensive half is attempted.

**Decision needed:** approve adding a field to every `Arrow`, or approve the cheap probe alone.

**Default if you say nothing:** *probe only*. Adding a field to every node type is the largest change
in the ten and is API-adjacent, so it does not happen on a default.

**The probe is done as layout arithmetic**, which is how the plan itself retired the analogous
`Frame`-field candidate, and it is labelled arithmetic rather than measurement. Declared instance
fields, read from the compiled classes:

    Arrow$Suspend       0 fields
    Arrow$SuspendWith   2 fields
    Arrow$Bind          2 fields
    Arrow$Chain         2 fields

Under the default 12-byte header with 8-byte alignment, a 2-reference instance occupies 12 + 8 = 20,
padded to **24 bytes**, and adding a 4-byte `int` lands in the existing padding, so it is **free** for
`SuspendWith`, `Bind` and `Chain`. `Arrow$Suspend` has no declared fields, so it occupies 12 padded to
**16 bytes**, and an added `int` fits the padding there too. On these four node types a kind field
therefore costs **nothing**, which is the opposite of the candidate's own stated risk that "a kind
field may cost a word".

Two honest limits. This is four node types and DIS-4 touches every one, so a node already sitting at a
multiple of 8 would pay the full 8 bytes; the four that matter most do not. And this is arithmetic, so
the confirming measurement is still `gc.alloc.rate.norm`, which the harness now resolves to about
128 bytes on a 2.5 MB row and would show a per-node word immediately.

**Consequence for the ruling:** the cheap half of DIS-4 is answered and it does *not* kill the
candidate. What remains is the expensive half, the drive rewrite, and the gate on it is unchanged.

---

## The pattern across all four

Three of the four defaults are "hold, and do the cheap reading that would kill it". That is not
caution for its own sake: it is what the last two days actually produced. C1, IN-1 and IN-2 were all
refuted from evidence already on disk, without a single benchmark run, and in two of those the
candidate's *stated field* turned out to be the wrong instrument. The gated candidates have not had
that treatment yet, and it costs nothing to give it to them first.

DIS-3 is the exception and the one I would spend a real session on, because it is the only open lead
on the regression this whole campaign was built around.
