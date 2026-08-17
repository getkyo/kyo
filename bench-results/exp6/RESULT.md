# Re-measuring the blocked leg, and what it overturned

Three comparisons were blocked because `emittingClausesPayRegionRebuild`'s control leg never settled:
`86.4, 80.8, 81.4, 78.5, 78.6` at `-wi 10`, reporting ±15.2% of its own score. Re-measured at
`-wi 25`, everything else identical.

## The blocker cleared

`BenchCompare` exits 0 with no banner. Twenty-five warmup iterations settle that row where ten did
not, and its reported error falls from **±12.33 to ±2.97**.

## And it overturned the correction

| | claim | basis |
|---|---|---|
| first | `emittingClauses` is a -9.8% win | hand arithmetic, unsettled leg |
| then | it was never a win, it is flat | tool with the error floor, still the unsettled leg |
| now | it is a **-9.5% win** | tool, on a leg that actually settled |

So the original number was approximately right, the intermediate correction was wrong, and the truth
is that the leg was **unmeasurable** rather than flat. A row whose control leg reports ±15.2% cannot
support any verdict, in either direction, and the honest answer was never "flat" but "re-measure".

The tool said that correctly: it did not call the row flat and stop, it **refused the whole
comparison**. I was the one who read the refusal as a verdict.

## The full picture, on settled legs

Four wins beyond the floor, one loss, ten flat:

    suspensionBaseline                -10.4%
    handleLoopAnswersInPlace          -10.4%
    emittingClausesPayRegionRebuild    -9.5%
    continuationBodiesFuse             -7.9%
    trailingMapsStayLinear            +26.6%   and +240,001 B/op

`handleLoopFusesContinuation` at -8.8% is now **flat**, because its own control leg reports ±14.55%:
the settling problem moved to a different row rather than disappearing. One re-measurement is not
enough for a clean sweep, and the tool is saying so rather than letting the number pass.

## What this says about the campaign's numbers

The direction of every result holds and the magnitudes moved by one to three points. The forced-inline
fix wins on four to five rows and costs `trailingMapsStayLinear` roughly 25% and 240,000 B/op. What
changed is that these are now verdicts the tool issued on legs it certified, rather than percentages
computed by hand from whatever the legs happened to report.
