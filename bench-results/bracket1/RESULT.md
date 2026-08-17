# The first replicated bracket on the central question

Base against `Eval$::dispatch$1` forced inline, C V C V C, two rows, `-wi 25`. Every earlier answer to
this question was a single leg reporting a floor; this is the first with replication and a real
threshold. It became expressible only after `bracket` learned to compare configurations rather than
only shas, since forced-inline is a JVM configuration and not a design.

## Result

    A/A null: clean, no control row classified against another control leg.

    🟢 continuationBodiesFuse    28.68 ± 0.25  ->  26.72 ± 0.25   -6.8%
    ⚪ trailingMapsStayLinear    303.17 ± 18.00 -> 331.83 ± 18.00  +9.5%, +240,000 B/op

    flat to within +-22.64% (alpha 0.025 after correcting for 2 rows, df 3)

**The headline survives replication.** `continuationBodiesFuse` improves 6.8% when the 607-byte
method is forced to inline, now backed by a threshold estimated from replicate legs rather than a
floor, with the A/A null clean over the three control legs.

## The correction

**The timing regression on `trailingMapsStayLinear` is not established.** Earlier single-leg runs
reported +23.8% and +26.6% and I stated both. At this configuration the row resolves to ±22.64%, so
+9.5% is flat: the row is too noisy for a verdict of that size, and the two earlier figures were
outside their legs' resolution rather than measured.

**Its allocation regression is established**, and is unaffected by that: +240,000 B/op, exact and
per-operation, matching the 239,976 measured earlier and the 240,000 that separate scalar replacement
on from off. Allocation does not need the timing to resolve.

So the honest statement is narrower than before and better founded: forcing the inline **wins 6.8% on
`continuationBodiesFuse` and costs 240,000 B/op on `trailingMapsStayLinear`**, with that row's timing
cost real in direction and unmeasured in size at this configuration.

## The defect this exposed

The report showed `-` in the mechanism column for the flat row, because mechanisms are suppressed
there, so a 240,000 B/op change was visible only as a number in a column. Allocation is exact and
per-operation and does not depend on the timing resolving; it is now reported separately, with a test
built from these numbers.
