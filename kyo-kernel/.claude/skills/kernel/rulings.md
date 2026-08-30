# Rulings

What the reviewer has already objected to, in his own words. This is `kernel-rehearsal`'s rubric,
and it is what makes the rehearsal predictive of this reviewer rather than generically competent.

Rulings are recorded **verbatim and dated**. A paraphrase loses the thing that makes a ruling
survive a compaction: "user decided against it" invites a later session to relitigate from a
summary of a summary, while his own sentence does not.

An entry is added whenever he objects during a live review, whether or not the objection was
already covered. A repeat is the strongest signal there is: it means the preparation ignored a
ruling he had already given, and `kernel-rehearsal` reports those first.

## Types and safety

**2026-08-29** on `Any < Nothing` as the evaluator's return type:
> fuck man.... Any < Nothing everywhere!?!?!? STOP AND TAKE LONG STEP BACK

**2026-08-29** on `var res: Any = null` in the eval's guard:
> var res: any = null / why the fuck are you doing this!? you should only update the regions handling?

**2026-08-29**, on the general standard for the change:
> FUCKING SAFE CODE!!! PROPERLY TYPED!!!

Standing consequences: no `Any` or `Null` carrier in the evaluator. A signature that stops
typechecking after a design change is information about the design, not an obstacle to route
around: fix the signature to say what the method now does. Erasing a return type to make a call
compile is the failure this ruling names.

## New types

**2026-08-29** on introducing a `Region` class to carry an entry's types:
> oh fuck why have a Region class? man.....................

> SO WHY ADD REGION!?!?!?!?

Standing consequence: a new type needs an argument that an existing one cannot serve. Re-typing an
array element at the storage boundary is a sanctioned erasure-forced cast (the skill's cast ladder
names `Stack` as the example) and is not a reason to invent a carrier.

## Scope

**2026-08-29** on rewriting `run` while changing region handling:
> you should only update the regions handling?

**2026-08-29**, when the stack type was already what had been asked for:
> don't we just need arrays in Stack? WHATS GOING ON!?

Standing consequence: changes stay inside the derivation's declared surface. An improvement
outside it is still a finding, because nobody agreed to it.

## Naming

**2026-08-29**, setting the vocabulary for the change:
> Avoid new terminology: "drive" is explicitly banned, the correct is "eval", don't use "after" use
> "cont", make sure naming is fully consistent.

## Working method

**2026-08-29**, on the shape of the work:
> make sure the code is as simple and as safe as possible. Avoid new types if possible. Prefer code
> that is correct by construction. If you find yourself handling multiple edge cases you need to
> take a step back and rethink the approach.

**2026-08-29**, opening the live-review model:
> You do NOT touch my code like you did recently after compaction. You can only change it via a
> "live review". It's a different model of execution where your goal is to present a live review
> that will pass my review and you know how picky I am. Your ultmost goal must be satisfying my
> requirements and ensuring the live review will go smoothly.

**2026-08-29**, on how the previous attempt went:
> reflect on how you got a lot of instructions and just went ahead producing garbage code

## Inference and workarounds

**2026-08-28**, on a compile error worked around rather than root-caused:
> You cant workaround real issues, even if they're inference issues

> remember: avoid working around inference issues

Standing consequence: an inference failure is diagnosed to its root in the kernel and fixed there.
An ascription, a helper, or a widened type that makes the site compile is a workaround.

## Applying a live review

**2026-08-30**, on reaching for a bulk replace mid-walk:
> and you were about to do a batch edit!? where's the live review preparation?

Standing consequence: the walk is applied one edit at a time with the Edit tool, and the sequence
must exist as data before the walk starts. A described sequence is not a sequence: `sequence.json`
holds the exact text pairs and `sequence.py --verify` proves they reproduce the tip. If applying
ever needs improvisation, PACKAGE did not finish.

**2026-08-30**, on the tree the walk starts from. A half-applied walk was committed and left
`kyo-kernel` red, which is not a state to restart from and not a tree to build in. Standing
consequence: the walk is atomic in the sense that matters. If it stops part way, the sources go back
to the baseline byte for byte before anything else happens, and the target state stays in the
isolated worktree where it already lives.

## Evidence

**2026-08-30**, discovered rather than ruled, and the worst escape this pipeline has had. Four rounds
of review argued over benchmark tables produced by `ProtoKernelBench`, a class that measures
`kyo.kernel` and contains no reference to `proto`; its name is left over from the rename that made
kernel2 the kernel. No benchmark under `src/jmh` referenced `kyo.proto` at all, so nothing had ever
measured the file under review.

Standing consequence: before a number is evidence, verify that the thing measured is the thing
changed, mechanically. `package-check.sh` resolves every benchmark class a package names and reports
one that does not reference the package under review. A plausible name answers the question by
looking right, which is why judgment kept passing it.
