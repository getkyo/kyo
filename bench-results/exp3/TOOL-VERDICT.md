# The sweep, re-issued through the harness

`bench-results/exp3/RESULT.md` reported this comparison from numbers computed by hand in python. The
data has now been ingested and the same comparison run through `Bench.compare` and `Report.render`.
This file records where the tool and the hand analysis differ, because that difference is the QA.

## Where they agree

Five winners, one loser, the same magnitudes, and allocation named as the mechanism on exactly the
row whose allocation moved and on no other. The substance of the earlier writeup holds.

## Correction to this file's first version

Its first version said "the tool is right each time". That was written after comparing two things,
the tool and my python, and declaring the tool the winner. Checking both against the raw data, which
is the only arbiter, shows the tool was wrong on the row we disagreed about.

`emittingClausesPayRegionRebuild`: the control leg reports **±15.2%** of its own score, and the tool
classified a **-9.8%** delta as a win. `Bench.compare` had no floor at the legs' own error; that floor
existed only in `compareReplicated`. My python was right to withhold the verdict, for a cruder reason
than it deserved.

Worse, the raw iterations `[86.4, 80.8, 81.4, 78.5, 78.6]` show that leg never settled, and dropping
the first iteration moves its mean by 1.64%. Neither the tool nor I saw that, because nothing was
looking at the per-iteration series at all.

Both are fixed: single-pair comparison now floors at the legs' own error, and an unsettled leg is a
blocker that fails the run. Re-run, the tool calls that row flat and refuses the comparison entirely.

## Where they differ, with the tool right in three of four places

**1. Neither of us was right, and I was less wrong.** My screen required a delta to exceed the legs'
combined error, 18% on this row, so -9.8% did not clear it. The tool classified it Faster. The raw
data says the control leg reports ±15.2% and never reached steady state, so no verdict is available
at all. The corrected tool calls it flat and blocks the comparison. My crude screen happened to reach
the right conclusion by a wrong route; the tool reached the wrong one by a route that looked rigorous.

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
