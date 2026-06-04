---
name: contributing-verify
description: The grounding gate for a module CONTRIBUTING.md. Re-opens every cited file:line and adjudicates each claim CONFIRMED / WRONG-CITATION / INVENTED / MISLABELED-AS-REAL, checks every KEEP item from the model is present, and runs the companion script. Reports PASS or BLOCK with a precise action list.
argument-hint: <doc-path> <model-path> [<module-dir>]
---

# contributing-verify

The load-bearing gate, and the reason this skill exists. A module
`CONTRIBUTING.md` is dangerous when wrong: a contributor acts on a stated
convention or mechanism that the code does not actually have, and ships a bug or
loses trust. This gate exists to make every factual claim in the guide
re-checkable against the source and to reject anything invented, mis-cited, or
dressed as fact when it is only a target.

This skill does not modify the guide; it verifies and reports. Four gates run in
order. Gate 2 (per-claim grounding) is the heart; the others support it.

## Gate 1: companion script

Run:

```
bash .claude/skills/contributing/contributing-check.sh <doc-path> <module-dir> --citations
```

`<module-dir>` is the third argument when the supervisor passes one (a non-default
doc location); otherwise it is the directory containing the doc (`dirname` of
`<doc-path>`), since a module guide lives in its module by default. The script
needs it to resolve citations, so never run Gate 1 without it.

It checks mechanically: every `file:line` citation in the guide resolves to a real
file and a real line in the module, the required structure headers are present, the
defer-to-root pointer exists, and there are no em-dashes, en-dashes, TODO/FIXME, or
placeholder markers. Report every STRONG finding with its line. A STRONG finding
blocks; resolve it before trusting the later gates (a citation that does not even
resolve cannot be a CONFIRMED claim).

## Gate 2: per-claim grounding (the heart)

Walk the guide claim by claim. A claim is any factual sentence: a mechanism, an
invariant, a config value, a return-type rule, a recipe step, a behavior. For each
claim that carries a citation, open the cited `file:line` with `Read` and
adjudicate:

- **CONFIRMED**: the cited code or comment says what the claim says. Reproduce the
  one phrase that confirms it.
- **WRONG-CITATION**: the claim is plausibly true but the citation points at the
  wrong place, or the line has shifted. Report the claim and what the cited line
  actually contains.
- **INVENTED**: the claim has no support at the citation and you cannot find
  support nearby. This is the worst finding. Report it loudly; the supervisor will
  trace it back to the stage that introduced it, not just delete the sentence.
- **MISLABELED-AS-REAL**: the claim is stated as how the code behaves today, but
  the code does not behave that way; it is a target convention presented as fact
  (or the inverse, a real behavior hedged as aspirational). Report the mismatch.

For a claim with NO citation that is nonetheless a factual assertion about the
code, treat it as INVENTED unless it is general framing. The guide's contract is
that every code-level fact is anchored.

Do not sample. Walk every factual claim. The whole point of the gate is that no
confidently-wrong sentence survives, and a sampled gate ships exactly the sentence
it skipped. Use `Read` with explicit offsets to open each citation; do not trust
the quote in the model, re-read the live source, because the model could itself be
stale or the drafter could have shifted a line.

## Gate 3: completeness against the model

Read the deduped model (second argument). For every KEEP item, confirm the guide
covers it. Report any `[INVARIANT]`, headline `[MECHANISM]`, or `[TRAP]` from the
model that is missing from the guide as `[MISSING-ITEM] <item> [<citation>]`. A
guide can be fully grounded and still incomplete by dropping a load-bearing
invariant; this gate catches that. (Minor `[CONVENTION]` or `[CONFIG]` omissions
are WEAK, not blocking, unless they are part of the headline section.)

## Gate 4: exists-vs-convention sweep

Independently of Gate 2's per-claim pass, scan for the specific honesty failure:
any sentence that describes a behavior as implemented when the model marked it
`[CONVENTION: target]`, or that hedges a real behavior as aspirational. Report each
as `[EXISTS-CONFUSION] <sentence> -- model says <fact|target>`. This is called out
separately because it is the highest-trust-cost error and is easy to miss in a
fast read.

## Output

```
## Gate 1: script check
<STRONG findings with lines, or PASS>

## Gate 2: per-claim grounding
- CONFIRMED: <count> claims
- WRONG-CITATION: <list, each with claim + what the line actually says>
- INVENTED: <list, each with claim + where it appears>
- MISLABELED-AS-REAL: <list>

## Gate 3: completeness against model
<MISSING-ITEM entries, or PASS>

## Gate 4: exists-vs-convention
<EXISTS-CONFUSION entries, or PASS>

## Summary
<PASS | BLOCK | FULL-REWRITE>
Claims: <CONFIRMED>/<total cited>.
<If BLOCK: the action list, one entry per finding, in contributing-edit terms
(FIX-CITATION / REMOVE-INVENTED / RELABEL / ADD-MISSING). If the INVENTED count is
high or the spine of the guide is unsupported, recommend FULL-REWRITE and name the
upstream stage to re-run (a fabricating miner facet, or the drafter).>
```

Verdict rules:
- **PASS**: Gate 1 clean, zero INVENTED, zero MISLABELED, zero MISSING-ITEM, at
  most a few WRONG-CITATION that an edit fixes.
- **BLOCK**: any WRONG-CITATION, MISLABELED, or MISSING-ITEM, or a small number of
  INVENTED traceable to a draft slip. Emit the action list.
- **FULL-REWRITE**: many INVENTED claims, or the headline section is largely
  unsupported. The guide is more invention than grounding; name the upstream stage
  to re-run.

## Execution

This sub-skill runs as a single Opus agent dispatched by the supervisor. You do
NOT dispatch further sub-agents. Run the script (Gate 1), then walk every cited
claim by opening its source (Gate 2), then check completeness against the model
(Gate 3), then the exists-vs-convention sweep (Gate 4), then emit the report.
Never report PASS with an unresolved INVENTED or MISLABELED finding. This gate is
the difference between a contributor guide that is trusted and one that quietly
teaches the wrong thing; hold the line.
