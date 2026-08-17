# The sweep, re-issued through the harness

`bench-results/exp3/RESULT.md` reported this comparison from numbers computed by hand in python. The
data has now been ingested and the same comparison run through `Bench.compare` and `Report.render`.
This file records where the tool and the hand analysis differ, because that difference is the QA.

## Where they agree

Five winners, one loser, the same magnitudes, and allocation named as the mechanism on exactly the
row whose allocation moved and on no other. The substance of the earlier writeup holds.

## Where they differ, with the tool right each time

**1. The hand analysis missed a winner.** My screen required a delta to exceed the two legs' combined
error: for `emittingClausesPayRegionRebuild` that is `(12.33 + 2.26) / 81.12 = 18%`, so a real -9.8%
did not clear my bar. The tool classifies it Faster. I reported "4 faster, 1 slower"; it is 5.

**2. The tool refuses to leave the wins unexplained.** All five are marked `**none found**` and listed
under "moved with nothing in the evidence behind it, so the cause is not known yet". The hand analysis
printed deltas and said nothing about mechanism, which is exactly how an unexplained win becomes a
claimed one in a summary written an hour later.

**3. The tool states what "flat" is worth.** Nine flat rows carry the warning that these legs were not
replicated, so flat means the harness cannot say how small an effect it would have missed. The hand
analysis silently counted them as "within".

**4. The tool fires the win-and-loss rule.** A change that both wins and loses is two diagnoses rather
than one tradeoff. The hand analysis had no such notion.

## What the ingested run correctly refuses to claim

The header reads "timing only, so no movement here is attributed" and "drift 4.0% assumed, not
measured", and the markers line is empty. All three are true: this data was produced by a raw
`sbt Jmh/run` outside any leg, so it has no markers proving which design produced it, no tree hash,
and no evidence ladder. An ingested run is weaker than a measured leg and the report says so rather
than quietly presenting it as equivalent.

## The lesson, which is the point of the exercise

The hand analysis was not wrong about the numbers. It was less sensitive in one place and silent in
three, and every silence was a place where the tool would have made a claim harder to state. That is
the whole value proposition, demonstrated by having skipped it.
