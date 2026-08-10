# kernel2: measured JIT morphism report

Evidence-based survey of call-site polymorphism in the kernel benchmark suite.
Method: each row ran single-fork with `-XX:+LogCompilation`; the per-row XML was
parsed to extract, for every C2-compiled kernel or bench method, each call site's
recorded receiver-type profile (concrete classes with hit counts) and the inline
decision that followed. Classification is from the profile the JIT itself recorded,
not from reading source or guessing.

Rows inspected (state: commit df6a68fc93, after the head/tail Step unification,
before the Defer concrete class): fusionAllocatesNothing, fusionAfterSuspensionRunOnly,
continuationBodiesFuse, suspensionBaseline, resumeAnswersInPlace,
statefulAnswersPayOneTuple, deepRecursionPaysRescuesOnly, trailingMapsStayLinear.
Logs and parser: session scratchpad `jit/` and `jit-parse.py` (regenerable:
`jit-inspect.sh` reruns the capture).

## Headline

**Zero hot megamorphic call sites in the entire suite.** Every virtual call with a
hot profile (peak count >= 1000) is either monomorphic (one receiver class, inlined)
or bimorphic (two receiver classes, both guard-inlined). Every inline failure in hot
kernel code is size- or depth-based, never dispatch-based.

This is also the explanation for the head/tail unification measuring parity: there
was no megamorphic penalty in the suite to recover. The suite's per-site inline
mapLoops give every user `.map` site its own bytecode, hence its own receiver
profile, so sites stay narrow by construction.

## Measured morphism by site

### Walk sites (chain execution)

| site | caller | profile | decision |
|---|---|---|---|
| `Arrow::step` | AndThen.apply, mapLoops, eval | mono or bi: `Step$$anon$3` dominant + rare bare `Transform` | inlined |
| `Step::head` | AndThen.apply, Step$$anon$3.apply, mapLoops | bi: `Step$$anon$3` (e.g. 28857) + bare `Transform` (e.g. 3) | inlined (accessor) |
| `Step::tail` | same | same bi profile | inlined (accessor) |
| `Transform::apply(v, next)` | per-site mapLoops | mono per site (each mapLoop is its own bytecode) | inlined |
| `Arrow::apply` | handler resume lambda | bi: AndThen + Step$$anon$3 | inlined |

The bare-`Transform` second receiver is the single-map case where a `Transform` is
its own step (`Transform.head = this`, final). After the unification these two are
the only implementations of `head`/`tail` that can exist.

### Handler loops (single effect per row, so measured-narrow)

| site | caller | profile | decision |
|---|---|---|---|
| `Suspend::tag` | handleLoop/resumeLoop/loopLoop | mono: the row's one suspendWith mint | inlined |
| `Suspend::input` | same | mono | inlined |
| `Suspend::cont` | loopLoop, resume lambda | mono: `Suspend$$anon$1` (map wrapper) | inlined |
| `Suspend::cont`/`root` | Suspend.map | bi: `Suspend$$anon$1` + the row's mint | inlined |
| `Function1/Function3::apply` (handler f) | handler loops | mono: the handler lambda class | inlined |

### Defer (measured before the concrete-class change)

`deepRecursionPaysRescuesOnly` compiled `Kyo$Defer.apply` minting `Kyo$Defer$$anon$3`;
accessor sites were mono in-row. The concrete-class unification (commit c227142546)
reduces `value`/`cont` to a single implementation, making their devirtualization
CHA-guaranteed instead of profile-dependent.

`Kyo::map` never appears as a hot profiled virtual site in any row: the rescue arm
(`kyo.map(arrow)` on budget exhaustion) and the bounce arms are cold.

## Inline failures in hot kernel code (all size/depth, none dispatch)

| reason | where | corroborates |
|---|---|---|
| `hot method too big` | continuation-entry mapLoop (377B) called from handle path | E2: FreqInlineSize=600 recovers ~8% on continuationBodiesFuse (31.4 to 28.9 us) |
| `recursive inlining is too deep` | self-recursive mapLoop/loop in deepRecursion | E3: MaxRecursiveInlineLevel=3 recovers ~21% (53.3 to 42.2 us) |
| `already compiled into a big method`, `inlining too deep` | the 50-map stored chain: nested mapLoop$1..$7 inline, deeper ones become separate static calls | static dispatch, no polymorphism; cost is call overhead only |
| `virtual call` with `count=-1` | cold rescue/bounce arms (never hot during profiling) | unprofiled cold paths; C2 leaves a virtual call on a path that does not run hot |

trailingMapsStayLinear's 18 `virtual call` failures (vs 2 elsewhere) are all the
`count=-1` cold-arm kind, in the rescue paths of its mapLoop and in AndThen.apply's
cold branch. No hot-loop cost.

## What the suite cannot show (structural bounds vs measured facts)

The rows are intentionally narrow: one effect, one or few Transform classes per
profiled site. Measured monomorphism at a site is therefore not a guarantee. The
guarantees and exposures, by construction:

1. **`Step::head`/`tail`: at most 2 implementations exist** (the factory mint's vals,
   Transform's finals). But C2 bimorphic inlining keys on receiver classes, not
   implementations: a shared-bytecode walk site (AndThen.apply, eval, handler loops)
   that sees the factory mint plus many distinct bare-Transform classes exceeds the
   profile width. C2 then speculatively inlines the dominant receiver with a virtual
   fallback. The per-site mapLoops shield user code from this; only the shared
   walkers are exposed, and only when bare single-Transform arrows mix with linked
   chains at the same site.

2. **`Suspend` accessors: one implementation per suspendWith mint site.** In a
   program with many suspension sites flowing through one handler, handleLoop's
   `tag`/`input`/`cont` sites see one receiver class per site and will exceed
   profile width. This is the known biggest exposure and is the deferred handler
   work (per-call-site handler loops via inlining handle/resume).

3. **`Transform::apply(v, next)` at shared walkers**: irreducibly polymorphic where
   many transform bodies pass through one bytecode site; this is interpreter-style
   dispatch cost, eliminable only by per-site fused loops (which the inline map
   path already provides).

4. **`Defer`: single implementation after c227142546**; CHA devirtualizes its
   accessors at every site regardless of profile. The A/B is expected parity in
   this suite for the same reason head/tail measured parity.
