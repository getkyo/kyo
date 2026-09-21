---
name: prose-cleanup
description: Clean the comments a branch added, against the repo's comment rules, touching only lines the branch itself wrote. Use at the end of a campaign, or when source prose has drifted into restating code and listing call sites.
---

# prose-cleanup

Clean the prose **this branch wrote**. Pre-existing comments are out of bounds, whatever you think of them.

You are given a batch: a list of file paths. Work through them one at a time.

## 1. Determine what you may touch

For each file:

```sh
bash .claude/skills/prose-cleanup/prose-scope.sh <file> [base-ref]
```

It prints every comment block containing at least one line this branch added, with a `+` marking each added line.

Each block is printed with an `EDITABLE` line list and a `READ-ONLY` line list, and every line is labelled.

**Only `EDITABLE` lines may change.** `READ-ONLY` lines are pre-existing prose shown for context and stay byte-identical, down to the character. A branch that inserted six lines into an existing scaladoc owns those six and nothing else in that doc, so a clause you dislike on a `READ-ONLY` line is not yours to cut. A block the tool does not print does not exist for you.

A block being printed does **not** make the block editable. The block is printed because *some* line in it is yours. Read the `EDITABLE` list before touching anything; that list, not the block boundary, is your scope.

If removing a `+` line would force a change to a non-`+` line, leave both and report it. Do not resolve it yourself.

## 2. Judge each block

You are deciding what is worth saying to a maintainer who opens this file months from now knowing nothing. Prose that earns its place is brief, direct, and informative: it tells them something the code cannot, in the fewest words that carry it. Everything else costs them reading time and goes stale on someone else's commit.

**Most of what you read will not survive, and that is the point.** A branch that added three thousand comment lines did not discover three thousand facts a maintainer cannot get from the code. It narrated itself while working. Your job is to find the few sentences that genuinely carry something and delete the rest.

**Do not ask "does this carry value".** Almost anything does under that question, which is why a pass asking it keeps everything. Ask instead: **would a maintainer be materially worse off without this sentence? Would they make a wrong change, or fail to make a right one?** If the honest answer is no, or only "they would have to read the code more carefully", it goes. Reading the code carefully is their job.

Expect to remove a large fraction of what you read. If you kept most of it, you applied the wrong bar; go back and apply this one.

A block can carry a real fact in one sentence and waste four around it, and those four are as removable as a whole bad comment. Cutting a six-line block to its load-bearing one is a normal outcome, and it is removal, not rewriting, as long as the survivor is byte-identical.

### Work the passes in order, on the whole file

Do not judge each block once and move on. One holistic look per block is what keeps everything: every sentence looks defensible next to the code it sits on, and the duplication only shows up when you compare blocks to each other. Make a separate pass for each removable class, over the whole file, in this order. Record what each pass removed.

**Pass 0 — claims that are not true.** Before judging whether a block is worth keeping, check whether what it says is so. Read the claim against the code it describes and satisfy yourself it holds: the ordering it asserts, the constant it cites, the behaviour it attributes to a call, the thing it says exists.

A false comment is the worst defect in this pass's scope, and redundancy is not close. A redundant comment costs a reader time; a false one makes them reason from something untrue and change the code accordingly, and it is trusted precisely because someone bothered to write it. It is also evidence in itself: prose that no longer matches its code usually means the code moved and nobody updated the comment, so look at what else that edit touched before moving on.

- False, and the fact is worth having: write the correct statement. This is a sanctioned write, not a cut.
- False, and the fact is not worth having: delete it.
- You cannot tell whether it holds: keep it byte-identical and flag it. Never guess, and never repair a claim you have not verified; a confidently wrong correction is worse than the original, because the next reader has no reason to doubt it.

Report every falsehood separately from the verdict counts. It is the finding a maintainer most wants out of this pass, and it is invisible in a diff that only shows deletions. One wave over this repository turned up three: a build file describing a separate project that does not exist, a test listing a scenario that same file disables, and a benchmark row claiming a narrower chain where only the depth differed.

**Pass 1 — whole blocks that restate.** For every block: does grepping the identifier below it recover the content? Does the method, test, or class name already say it? Does the assertion message on the next line say it? If yes, DELETE the block. Do this before anything else; there is no point polishing a block that should not exist.

One exception, because a name states a **subject** and never a **rationale**. Keep the sentences that say why a thing is *built the way it is*, where a reader who does not know would simplify it and quietly destroy what it does:

- **A test whose setup encodes its reproduction.** A capacity of 1, a queue deliberately left unconsumed, an ordering, a magic constant: the sentences explaining why those specific choices stay, even when the test name states the bug. `FooDropTest` tells a reader what is under test; it does not tell them that `channelCapacity = 1` is load-bearing rather than arbitrary, and a maintainer tidying it away deletes the guard while the test still passes.
- **A benchmark row's measurement claim.** What the number means, where the method name does not carry it. A results table read months later is interpreted through these sentences, and a plausible-looking name is exactly what lets a row be read as measuring something it does not.

This licenses the construction rationale and nothing else. The story of how the bug was found, a walkthrough of the body, and a label announcing the block's own topic are all still deleted.

**Pass 2 — duplicates across the file.** Compare every surviving block against every other, and against any block it points at. Where two state the same fact, keep the one at the site that needs it and DELETE the other outright. **Duplication is resolved by deleting a copy, never by merging** — merging is a rewrite, deleting is a cut. A fact stated once where it belongs beats the same fact in three places drifting apart.

Before deleting a block that carries a kyo ticket reference, check the reference survives somewhere in the file. A redundant block can still hold the only route back to the report, and losing it costs a reader the discussion the comment was too small to carry. If it is the last occurrence, keep the sentence carrying it.

**Pass 3 — inventories and pointers.** Inside surviving blocks, cut every clause naming a test, file, issue number, call site, or position ("the leaf below", "as above", "same shape as X"). Keep a pointer only when it routes to a substantially fuller argument elsewhere that this sentence merely summarises.

**Pass 4 — restatement sentences.** Inside each surviving block, cut any sentence that restates the previous sentence in other words, restates the signature, or narrates the line below it. Including the opening sentence that announces what the block is about before the block gets to it.

**Pass 5 — elaboration.** For each surviving block, find the single sentence carrying the fact. Then take every *other* sentence in turn and ask: without this one, would a maintainer make a wrong change? Cut each that fails. A stated invariant usually does not need its failure story: "the release is registered before the take" is the fact, and the paragraph imagining what happens otherwise is usually the author convincing themselves. Keep the failure only where it is genuinely not deducible from the invariant.

**Pass 6 — the file as a whole.** Step back and read the surviving comments together, in order, as a maintainer would. Is this the amount of prose this file needs? If several blocks explain the same class of invariant, keep the clearest and delete the echoes. This pass catches what block-by-block judgment structurally cannot.

The default is no comment. A comment is warranted **only** as an answer to one of these:

1. Why this shape and not the obvious one.
2. What breaks if this changes: the load-bearing invariant, the ordering that must hold, why something stays that a reader would otherwise delete.
3. A platform or external fact the types cannot carry (an inlining budget, a runtime flag, a protocol requirement).
4. A measured result, stated as the number.
5. A concurrency hazard: the interleaving, which carrier owns what.
6. An encoding or bit-packing layout.
7. A phase marker inside a multi-step method body: one line, naming the phase, never restating a call.
8. A navigational signpost inside a method body of 30+ lines with non-linear control flow.
9. A marker the module requires (`// Unsafe:` at a bridging site, an audit comment at a declared exception).

Apply all three kill tests to every block:

- **Grep test.** Is the content recoverable by grepping the identifier below it? Tautology. Delete.
- **Sync test.** Does it name anything nothing keeps in sync? Test classes, call sites, file paths, `file:line` citations, counts, and **positional references** ("the leaf after this one", "the same as the block above", "as above"). Each is false on the next rename or reorder and nothing will catch it. State the constraint, never the inventory.

  **A kyo ticket reference is allowed, and is the only external reference that is.** A ticket number does not rot the way a test name does, and it routes a reader to the report and the discussion, which is where reasoning too large for a comment lives. Keep `(#1928)` and its like. This does not extend to anything else that looks like a citation: a test name, a file, or a `file:line` still goes. Check a pointer before trusting it; plenty have already gone stale.

This is one of the two places a write is sanctioned, the other being a claim that is not true (pass 0). A **required marker whose entire content is a positional pointer** (`// Unsafe: see the leaf above.`) cannot be fixed by cutting: delete the pointer and a bare `// Unsafe:` remains, which is worse. Write the reason in place, whether or not the pointer happens to resolve today, since an accurate positional reference still breaks silently on the next reorder and the marker is the one comment that must carry its justification. Keep it to the reason itself, and say in your report that you wrote rather than cut.
- **Decision test.** Would removing it change what a maintainer does? If no, delete.

Cut these on sight. Each is a tautology or an inventory, and none of them needs a judgment call:

- **`@param` / `@return` that restate the name and type.** The repo's rule is "only when name and type aren't enough" (CONTRIBUTING, Method-Level Scaladoc). `@param v` / "The value to lift" on a parameter named `v` of the type in the signature is the shape to remove. Keep the tag when it carries a constraint, a unit, a range, or an ownership rule.
- **A clause citing a test, a file, or a `file:line`** as evidence for a claim the sentence already makes. Accuracy today is not the point; nothing keeps it accurate. A kyo ticket reference is the exception and stays.
- **A pointer with no content** ("as above", "same as the block below", "see the leaf above"). Either the reason belongs here, or the sentence does not.
- **An opening sentence that announces what the next line does** before the block gets to its actual point.
- **A closing clause of commentary on a naming or style choice**, where the decision it would change is nobody's.

Two banned shapes:

- **Development diary.** "previously", "used to", "the old version", "this was changed because", "after the review", "now uses", "renamed from", "we discovered", phase or campaign codes, any change-relative wording. History belongs in the commit message.
- **Quick-to-stale tautology.** Restates the line, signature, or name directly below it; enumerates the tests, classes or files that use the thing; a "see also" inventory.

Placement: phase and navigational comments live only **inside a method body**. On a top-level declaration, a build setting, a field, or an import block they are always one of the two banned shapes, because there is no structure to navigate.

Style for anything you keep: one fact per sentence, no hedging, no throat-clearing, the measured number over an adjective, no em-dashes or en-dashes.

## 3. Record a verdict, then edit

Write a verdict for **every** block after the six passes: `KEEP`, `SHARPEN`, or `DELETE`, each with a one-line reason. Writing the reason is the point. A block whose justification you cannot state in one line is one you already know is weak.

A KEEP verdict must name which of the nine warranted questions the block answers, and state why a maintainer would make a wrong change without it. "It explains the concurrency hazard" is not a justification; it is a category label, and every comment in a concurrent codebase can claim it. Name the wrong change.

**This is a removal pass, not a rewrite pass.** The three verdicts are narrow:

- **KEEP** means every part of the block carries value, not that you did not look closely. A block you would merely have phrased differently is a KEEP: wording you find clumsy, an order you would have inverted, a sentence you would have split are not defects, and changing them is churn a reviewer has to read for nothing. But a block is not a unit you accept or reject whole. Read it clause by clause and keep the ones that pay.
- **SHARPEN** means **deleting the offending clause or sentence from an otherwise-sound block**. The surviving text stays byte-identical. You are cutting, not rewording. If you find yourself retyping a sentence to say the same thing in your own words, stop: that is a KEEP.
- **DELETE** removes the whole block.

**Never paraphrase established vocabulary.** A term the module already uses is the correct term, and a longer plain-language substitute is a regression: it costs every later reader a translation back. In this repo that includes the kernel's own words for its machinery. If a term is genuinely undefined anywhere, that is worth raising in your report, not silently rewriting.

**Rewriting a comment to "absorb", "compress", "reorder" or "de-hedge" it is out of scope.** So is combining two comments into one. If a block contains both sound and offending material and you cannot remove the offending part without restating the rest, KEEP it and list it under flagged.

Then apply only the `SHARPEN` and `DELETE` verdicts, with the Edit tool, anchored on the block's exact text.

**Do not reformat.** Deleting a comment line can change how scalafmt aligns the code around it (this repo sets `align.preset = more`). Leave that to the coordinator's format step; never adjust code spacing yourself.

## 4. Self-verify before reporting

**Run the check. Do not perform it by eye.**

```sh
bash .claude/skills/prose-cleanup/verify.sh <every file you touched>
```

Reading your own diff and judging it clean is the same judgment that produced the edits, so it confirms whatever you already believed. An agent has reported a batch clean while having cut clauses out of read-only lines: its eyes agreed with its hands. The script does not.

Every path must print `ok`. On a failure:

- `STOLEN` means you changed a line marked READ-ONLY. That is the one rule with no exceptions.
- `CODE` means a code line changed by more than whitespace.

Restore the file with `git checkout -- <file>`, re-read its scope output, and redo it touching only EDITABLE lines. Then confirm the comment delimiters still balance (`/**` and `*/` counts unchanged).

Then check the shape of your own diff:

```sh
git diff --numstat -- <file>     # added, removed
```

**A removal pass is mostly deletions.** Added lines should be a small fraction of removed ones. If added is anywhere near removed you rewrote instead of cutting: go back, restore the file, and take only the clauses that fail a test.

Line counts mislead in one direction, so check characters before trusting them. Cutting a clause off the end of a line rewrites that line in `numstat` terms and shows as 1 added, 1 removed, even though nothing was added. Distinguish the two cases:

```sh
git diff -U0 -- <file> | grep '^-' | grep -v '^---' | wc -c   # characters removed
git diff -U0 -- <file> | grep '^+' | grep -v '^+++' | wc -c   # characters added
```

A genuine cut removes far more characters than it adds, at any line ratio. Parity in characters is a rewrite. Report both numbers so the coordinator sees the shape without reading the diff.

## 5. Report

Per file: the path, counts of KEEP / SHARPEN / DELETE, and one line per non-KEEP block saying what it was and why it went. Then the batch totals. Anything you left because it would have forced a non-`+` change goes in a final "flagged, not changed" list.

Lead the report with what pass 0 found. Every claim that did not hold, quoted, with what the code actually does and whether you corrected or deleted it, and separately every claim you could not verify. These matter more than the counts: a reader skimming for the result of a cleanup will not otherwise learn that the tree contained something untrue, and that is the part worth their attention.

## Running the pass across a branch (coordinator)

Split the work, dispatch, verify centrally, commit once. Do not clean files yourself while agents are running.

```sh
bash .claude/skills/prose-cleanup/batch.sh 8            # -> /tmp/prose-batches/batch-N.txt
```

**Before dispatching, prove the tools hold under concurrency.** A wave runs these scripts from many agents at once, which is not the condition they were written or tested in.

```sh
bash .claude/skills/prose-cleanup/race-probe.sh .claude/skills/prose-cleanup/prose-scope.sh <a few files>
bash .claude/skills/prose-cleanup/race-probe.sh .claude/skills/prose-cleanup/verify.sh <a few edited files>
```

Both must print `PASS`. This step exists because it was once skipped: the scripts shared fixed scratch paths, and at wave scale they answered wrongly on most concurrent runs. Probing before that had varied which files were cleaned but never how many agents ran at once, so the fault had never been exposed. Sampling the work is not sampling the conditions.

Size the wave by comment volume, not file count: around 400 comment lines per agent. Dispatch the batches in one message so they run in parallel; the batches are disjoint, so no two agents ever open the same file.

Give each agent this prompt, with nothing else:

```
Run the prose-cleanup skill over this batch.

Files (your complete and only scope):
<paste the contents of batch-N.txt>

Base ref: origin/main
Working directory: <repo path>

Read .claude/skills/prose-cleanup/SKILL.md and follow it exactly. For each file run
prose-scope.sh to get your editable set. Only lines marked + may change. Write a
verdict for every block before editing. Self-verify each file before moving on.
Do not commit. Report per the skill's Report section.
```

Model: the judgment is "is this sentence load-bearing", which is the same call the author already got wrong, so do not economise on the kernel and core batches. Never dispatch these as `fork`: a fork inherits your context, which is exactly the campaign rationale that makes a weak comment look justified.

When the agents return, verify the whole tree yourself before believing any report:

```sh
bash .claude/skills/prose-cleanup/verify.sh          # every changed line is a + comment line
```

Then compile the affected modules. A file failing verification is restored with `git checkout -- <file>` and re-run alone; do not hand-patch an agent's output, because that reintroduces the drift the pass exists to remove.

Commit once, after verification passes.

## Hard rules

- **Never edit a non-`+` line.** That is pre-existing prose.
- **Never edit code.** Comments only, including no reformatting.
- **Never delete a `// Unsafe:` marker or a module-required audit comment.** Sharpen at most.
- **Never commit.** The coordinator commits after the central verification.
- Do not touch files outside your batch.
