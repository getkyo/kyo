# Resource safety under multi-shot continuations: prior art

Context: kyo-kernel2's `Effect.bracket` releases a resource when the **first** branch of a multi-shot
continuation finishes, leaving later branches reading a freed resource (5 red tests in
`kyo/kernel/EffectTest.scala`). This document records what the literature and other implementations do
about that, so the design decision is made against the state of the art rather than from first principles.

## The problem is named, and it is ours verbatim

Voigt, Schuster and Brachthäuser, *Dynamic Wind for Effect Handlers*, OOPSLA 2025 (PACMPL 9, Article 377),
state it in their introduction:

> Mechanisms for finalization in presence of exceptions do not account for these. Abstractly, parts of the
> external context would have to be saved and later restored. Concretely, if resources have already been
> freed beforehand, resuming the program leads to undefined behavior, when already released external
> resources, such as file handles, are accessed.

Their definition of the property kyo is currently violating:

> Resource safety means that a program may only interact with external resources after it has acquired
> them, but before it has released them.

Their motivating example is a backtracking parser built from a `fork` effect whose handler resumes the
continuation twice. That is `Choice.run` and `Batch.run`.

## The design space, and who occupies each point

| when the release runs | who | consequence |
|---|---|---|
| at the **first exit** of the extent | **kyo today** | later branches read a freed resource. Unsound. |
| symmetric **per entry and per exit** | Scheme `dynamic-wind` | correct for reversible state, wrong for irreversible release |
| at **continuation capture**, re-acquire on resume or fail | Effekt (Voigt et al. 2025) | sound. Cost: even one-shot resumptions cannot cross the handler |
| at the **eval boundary** | the held-out review's proposal | sound. Cost: release is late, not at the end of the use |
| **forbid multi-shot dynamically** | OCaml 5 | sound. Cost: no `Choice`/`Batch` in their current form |
| **forbid multi-shot statically** | Links (Tang et al. 2024) | sound. Cost: a linearity discipline in the type system |

### Scheme `dynamic-wind`: the oldest form of the result

`dynamic-wind` runs its `before` thunk on every entry into the extent and its `after` thunk on every exit,
including entries and exits caused by a captured continuation. That is exactly right for reversible state
and exactly wrong for a file handle: once `after` has closed it, re-entry cannot reopen it. The Scheme
community's conclusion, from the R4RS era, was that `call-with-input-file` and `call-with-output-file` are
*continuation unsafe*, and that being prepared for re-entry requires that unwind actions be undoable,
which irreversible cleanup is not.

Read as a theorem about our situation: **you cannot have both multi-shot re-entry and irreversible
release.** One of the two has to give. Everything below is a choice of which.

### OCaml 5: forbid it, dynamically, and say resources are the reason

OCaml 5's effect handlers are one-shot, enforced at runtime by nulling the continuation object on resume
and raising `Continuation_already_resumed` on a second use. The manual gives resource safety as the
motivation, not performance:

> OCaml programs may manipulate linear resources such as sockets and file descriptors, and the linearity
> discipline is easily broken if continuations are allowed to resume more than once. It would be quite hard
> to debug such linearity violations on resources due to the lack of static checks for linearity and the
> non-local nature of control flow, so OCaml does not support multi-shot continuations.

Note the second half of their position, which applies to kyo too: the at-most-once check is dynamic, and
there is **no** check that a continuation is resumed at least once, so discarding one still leaks unless
something else owns the release. kyo does own that case (the drain), and that part is already sound.

### Links and Tang et al.: forbid it, statically

*Soundly Handling Linearity* (Tang, Hillerström, Lindley, Morris, POPL 2024) introduces **control-flow
linearity**: track how often a continuation may be invoked, and require that this agree with the linearity
of the resources it captures. They used it to fix a long-standing soundness bug in Links. This is the
principled version of the OCaml rule, and it is a type-system change, not a runtime one.

### Effekt: allow it, and make the extent re-entrant or loud

Voigt et al. add two clauses to a handler beside the usual `return`: **`on suspend`**, run while the
continuation is being captured, and **`on resume`**, run when it is rewound back onto the stack. Their key
sequencing decision:

> Since we cannot be sure whether the continuation will be discarded or resumed, we need to ensure that all
> resources are released *during* the continuation capturing (stack unwinding).

So the release happens at **capture**, not at any exit. Re-entry then runs `on resume`, which may re-acquire.
For a resource that cannot be re-acquired, their own worked example is precisely the "make it loud" option:

```
val file = open("example.txt", WriteOnly())
try { ... }
on suspend { close(file) }
on resume { _ => do throw("re-entering scope") }
on return { x => close(file); return x }
```

and their assessment of that fallback:

> We believe that in the absence of static checks for control-flow linearity, this dynamic check is the best
> we can achieve. Unfortunately, this also means that even effect operations resuming exactly once cannot
> cross the handler protecting the file.

That last sentence is the reason the Effekt position is not directly importable into kyo: kyo's whole model
is effect operations running inside a bracket's `use`. `dump(pos)` is kyo's capture point, so a capture-time
release would fire on any handled suspension inside a bracket, including single-shot ones. `Sync.acquireReleaseWith(f)(close) { f => Var.get.map(f.read) }` would close at the `Var.get`.

They prove a resource safety theorem for the result: acquiring and releasing stay well-bracketed even with
unrestricted continuation use, including from inside finalizers.

## Where the Scala ecosystem sits

ZIO and Cats Effect do not have this problem because they do not have multi-shot continuations at all. Their
`acquireRelease`/`Resource`/`Scope` machinery only ever has to deal with one linear continuation plus
cancellation. **kyo is not in that category**: `Choice.run`, `Choice.runStream`, `Batch.run`, `Aspect` (via
user-supplied cuts) and `Poll.runFirst` (which hands the continuation out as a value) all put kyo in the
OCaml/Effekt/Links group, and it inherits the obligation those languages had to discharge explicitly.

## Separately: prior art for `Scope` as a registry rather than a bracket

Haskell's `resourcet` exists for the reason `Scope` exists. `bracket` imposes a linear LIFO nesting, and
some allocation patterns cannot be expressed that way at all: breaking a stream into chunks written to
successive files, or walking a directory tree, needs interleaved acquisition where the interleaving is not
known statically. `ResourceT` answers with a **registry of release actions** that can be freed in an order
that is not the nesting order.

That is an independent argument, from outside kyo, for keeping `Scope`'s finalizer queue rather than pushing
`Effect.bracket` down into `Scope.acquireRelease`: they solve different shapes, and the registry is the one
that composes with interleaving.

## What this implies for the decision

1. The "release at the last branch" behavior my failing tests assert is **not** what any surveyed system
   does, because no local rule can know which entry is last. The tests are right that today's behavior is
   unsound; they are not evidence for that particular fix.
2. Two of the four sound options are available to kyo without a type-system change: release at the eval
   boundary (the review's proposal, which I did not find in the literature), or throw on re-entry into a
   spent extent (Effekt's fallback, and the closest thing to an endorsed answer).
3. Both OCaml and Effekt independently concluded that, without static linearity, a **dynamic check** is the
   honest floor. Silently handing out a freed resource is the one option nobody defends.
4. If the eventual answer is a static one, `Choice`, `Batch`, `Aspect` and `Poll` are the surfaces that would
   need to carry the linearity annotation.

## Sources

- [Dynamic Wind for Effect Handlers (Voigt, Schuster, Brachthäuser, OOPSLA 2025)](https://doi.org/10.1145/3763155) and the [PDF](https://pl.cs.uni-tuebingen.de/publications/voigt2025dynamic.pdf)
- [Soundly Handling Linearity (Tang, Hillerström, Lindley, Morris, POPL 2024)](https://dl.acm.org/doi/10.1145/3632896), [arXiv](https://arxiv.org/abs/2307.09383)
- [OCaml manual, Effect handlers](https://ocaml.org/manual/5.4/effects.html)
- [Retrofitting Effect Handlers onto OCaml](https://arxiv.org/pdf/2104.00250)
- [Dynamic Wind (Guile Reference Manual)](https://www.gnu.org/software/guile/manual/html_node/Dynamic-Wind.html)
- [Chez Scheme, Control Operations](https://www.scheme.com/tspl3/control.html)
- [resourcet on Hackage](https://hackage.haskell.org/package/resourcet)
- [ResourceT Overview (School of Haskell)](https://smunix.github.io/www.schoolofhaskell.com/user/snoyberg/library-documentation/resourcet.html)
