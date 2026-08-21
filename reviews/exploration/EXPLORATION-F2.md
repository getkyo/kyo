# F2: suspension-family attribution and levers

Worktree `agent-a205e32a91b7ad22f`, base `26f14ecdd4`, 976 tests green on the base before any change.

## Attribution (base kernel2, -f 1 profiles, alloc exact / cycles directional)

### Allocation sites

`suspensionBaseline` (640,137 B/op): `Effect$$anon$2` (two-arg Defer) 38.0%, bench composition
transforms 62% (anon$229 37.1%, anon$284 24.9%). **No `AndThen` in the profile**: the dump fold's
nodes are below the noise floor on this row. The allocation is composition (one Transform plus one
Defer per `.map` on a pending value), the same category the old kernel pays (560,081 B/op); the row's
red is time, not bytes.

`foreignCrossingsPayRotation` (1,760,310 B/op): `Effect$$anon$2` 27.2%, bench transforms ~46%,
**`Arrow$Chain` 13.6% and `Effect$$anon$3` (three-arg Defer) 13.2%**: the per-crossing fold of a
range that contains the foreign handler (a Region cannot ride an `AndThen`, so those folds build
Chains), and the Chain-apply deferrals that re-enter the eval on resume. Old kernel alloc is at
parity (1,680,202): it pays one continuation re-wrap per crossing where kernel2 pays fold nodes.

### Cycles (inclusive, coarse: ~460 samples per row)

| frame | suspensionBaseline | foreignCrossings |
|---|---:|---:|
| `boxToInteger` (benchmark dilution, the skill's caveat) | 50.3% | 56.4% |
| `Stack.dump` + fold loop | 8.8% | 10.8% |
| `Stack.find` (+ its walk loop on foreign) | 3.5% | 9.6% |
| `Eval$.loop$1` own dispatch (leaf) | 10.3% | 15.0% |
| `Stack.push`/`ensure`/`size`/`put` | ~5.9% | ~4.8% |
| `Safepoint.get` | 6.6% | 2.6% |
| `Tag.<:<` + `checkTypes` | ~0 | 0.8% |

Named contributors to the time gap, in order: (1) boxing at un-inlined boundaries (absolute boxing
time far above the old kernel's at similar counts; the systemic fix is the per-call-site clause
binding, the F1 sibling's subject), (2) eval dispatch round trips per suspension (loop$1 leaf +
push/pop churn), (3) the fold/walk pair per crossing on foreign (dump + find + flatten), (4) not
the Tag comparison (sub-1%), (5) not the AndThen fold allocation on plain suspension rows.

## Levers

### L1: a fold applies by running its first step, not by deferring

`AndThen.apply(v)` was `Effect.defer(v, t, cont)`: every clause resume allocated a Defer and made a
full eval round trip (Defer arm, push, settled pop, no-arg dump) before the first step ran.
`t(v, cont)` is the same delivery by the law the eval itself applies (the eval's own round trip ends
in exactly `t(v, cont)`), budget-governed by the Step's own apply, and the Boxed payload handling is
unchanged (the same lift the deferring form used fires on the same argument). Same change to the
two-arg form (the Defer removed; the `cont.chain(next)` it already built stays), and to
`Chain.apply(v)` = `a(v, b)` (total for every arrow kind; a Chain head re-defers, which is today's
behavior, so progress is preserved).

### Results

Three-way, same session, `-f 2 -wi 5 -i 5 -prof gc`, mutex-serialized; old kernel re-measured in the same
session (`.f2logs/*.json`, committed).

| row | base us | L1 us | vs base | k1 us | L1/k1 | base B/op | L1 B/op |
|---|---:|---:|---:|---:|---:|---:|---:|
| trailingMapsStayLinear | 863.48 | 692.0 | **0.80x** | 449,013 | 0.0015x | 2,560,982 | **2,320,957** |
| sharedHandlerPaysDispatch | 188.89 | 160.1 | 0.85x* | 127.94 | 1.25x | 240,457 | 240,457 |
| fusionAfterSuspensionRunOnly | 0.99 | 0.81 | **0.82x** | 0.27 | 3.0x | 1,264 | **1,240** |
| fusionAfterSuspension | 219.63 | 197.8 | **0.90x** | 83.65 | 2.37x | 736,810 | **712,785** |
| suspensionBaseline | 188.08 | 181.1 | 0.96x | 122.52 | 1.48x | 640,137 | 640,137 |
| suspensionFusesContinuation | 99.50 | 112.5 -> f3: **101.60 +-3.5** | ~1.00x | 68.76 (f3) | 1.48x | 240,097 | 240,097 |
| continuationBodiesFuse | 32.93 | 32.9 | 1.00x | 24.24 | 1.36x | 64,136 | 64,136 |
| foreignCrossingsPayRotation | 871.32 | 885.6 | 1.02x | 323.90 | 2.73x | 1,760,310 | 1,760,310 |
| emittingClausesPayRegionRebuild (guard) | 149.18 | 152.9 | 1.02x | - | - | 184,313 | 184,313 |

\* my base leg's 188.89 is a high fork (the parent HEAD reference and the post-L1 screen both sit ~160);
the sharedHandler "win" is at least partly drift and is not claimed.

The allocation deltas equal the mechanism exactly: one 24-byte Defer removed per AndThen application
(trailing: 10,001 answers = -240,025 B; fusionAS: -24,025; RunOnly: -24). The 1.13x on
suspensionFusesContinuation was adjudicated by an `-f 3` bracket on the same binary pair: 101.60 +- 3.53
against the base band 98-102 at byte-identical allocation on an untouched path: fork drift, not a
regression.

**Full both-board `-f 1` screen after L1** (34 rows vs the HEAD `-f 2` reference): no row above 1.04x;
flagged movers are all wins (trailingMaps 0.73x on both boards, statefulAnswers 0.89x, fusionAS 0.93x,
RunOnly 0.94x). 976 tests green on a clean batch build.

### L2 (push under the inline threshold): dropped on measurement

The flat spelling compiled to **187 bytes**, 2.4x the 77 it was meant to shrink: the `Maybe`
(`getOrElse`/`Present`) expansions dominate. Target unreachable in this shape; reverted before any
benchmark (commit 6ac7680d8c keeps the diff in history).

### A law learned, and pinned

`Chain.apply(v)` must defer: a Chain's tail may carry a region, and the eval's round trip installs the
tail's entries before the head runs, so a failure in the head finds its scope. Running the head inline
executed it with the scope absent; `EffectTest`'s "failure in map" caught it immediately. `AndThen` is
immune by type (its spine is region-free), which is why the peel is sound there and only there. The
distinction is now a comment on both classes.

## Verdict

**Adopt L1** (commit f9c3550a39): AndThen applies by running its first step. Wins on trailing maps
(0.73-0.80x), RunOnly (~0.82x), fusionAfterSuspension (0.90x); nothing regresses across 34 rows; the
mechanism is named and its allocation arithmetic is exact.

Remaining reds after L1, with their attribution for whoever goes next: suspensionBaseline 1.48x /
suspensionFuses 1.48x / bodiesFuse 1.36x (composition-cost parity but per-suspension dispatch round trips
and clause-boundary boxing; the systemic fix is the per-call-site clause binding, F1's direction, applied
to HandlerCont), foreignCrossings ~2.6x (adds the fold/walk pair per crossing: dump+find+flatten ~25% of
the row), RunOnly ~3.0x (the one fold per op is now the floor of the folded-continuation design:
1,240 B/op vs the CPS kernel's 0).
