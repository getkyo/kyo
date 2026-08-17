# Review findings and their disposition

Three reviews of the plan and one of the implementation. The implementation review ran mutation
testing: it reintroduced each historical bug and checked whether the tests caught it. That is the
method that found the worst defects, because the tests all passed either way.

## Implementation review, 12 findings

| # | Finding | State |
|---|---|---|
| F1 | `ParseCoverage` counted `seen` inside the parse loop, so it could not detect a partial parse. Restoring the 4456-element drop reported 637/637, 100% coverage, all checks green | **fixed** |
| F2 | `Bytecode` dropped declarations not ending `);`: static initializers (19 of 60 classes) and `throws` clauses, merging their instructions into the previous method. 11B reported against an actual 5B | **fixed** |
| F3 | `tCritical` returned Infinity for df 7 and 9; a 100% regression classified Flat under a green all-clear | **fixed**, table replaced by computation |
| F4 | Untabulated-alpha fallback was looser than the truth and non-monotone: 16 rows loosened the test against 15 | **fixed** by the same change |
| F5 | Every check on the receiver verdict passes with the comparison inverted, because `morphism` pre-filters to profiled sites and no `kyo.` callee is polymorphic in this capture | open |
| F6 | A comparison where every row was unresolvable printed the green all-clear | **fixed** |
| F7 | The A/A null required 4 control legs where the session has 3, so it never ran; and its contiguous split put warm-up drift in the numerator | **fixed**, splits by alternation |
| F8 | `recompiled` counts ordinary tier escalation: 74 of 84 are level-3-then-4 promotion, which tiered compilation does to every hot method | open |
| F9 | Remaining order- and suffix-anchored patterns: `Klass`, `Inlined`/`NotInlined` fix attribute order; `AttrCount` matches `receiver_count` by suffix | open |
| F10 | Four checks cannot fail: two assert arithmetic on local constants, one is implied by its neighbour, one holds by definition | open |
| F11 | Untested surface (`parseAlloc`, `parseCpu`, `Store` round trip, `verifyAgainst`), unused oracles, and absolute paths that make the suite unrunnable off this machine | open |
| F12 | Comment claims the artifacts contradict, including my own "1920 C1 refusals" against an actual 1929 | open |

## What the pattern says

I built three guards tonight against silent failure. The review showed two of them could not
themselves fail: coverage was self-referential, and the bytecode size check had no fixture carrying
either shape that breaks it. That is the same defect as the shape assertions this whole rework
replaced, one level up, and it is worth stating plainly rather than filing as six separate items.

The general rule, now applied to the guards as well as to the parsers: **a check that has never
failed is a claim, not a check.** Mutation is how you find out which one you have.

## Plan reviews, for the record

Round 1 found three of four signals broken. Round 2 found the allocation phase unsound (the pretty
tree covers 14.27% of bytes and its truncation point moves between runs), the A/A estimator wrong,
and the automation promise undecidable from a two-sha diff. Round 3, held out and blind, found the
replacement statistic classified 45.5% of rows under pure noise, so its acceptance criterion would
have failed 99.99% of the time on a correct implementation.

Every one of those was verified locally before acting, and two of the reviews' own numbers were wrong
in the process: OSR tasks are 5 rather than 19 (the larger number counts mentions across element
kinds), and allocation coverage is 14.27% rather than 14.46%.
