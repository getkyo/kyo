# Experiment 4: the regression the fix causes is dead scalar replacement, measured

Experiment 3 found that forcing `dispatch$1` to inline regresses `trailingMapsStayLinear` by 23.80%
and adds 239,976 B/op. I recorded the escape-analysis explanation as a *reading*, explicitly not a
measurement. This measures it.

## The isolation

Four configurations of one row, `-f 1 -wi 10 -i 5`, heap and collector pinned:

| config | us/op | B/op |
|---|---|---|
| default, EA on | 293.69 ± 23.81 | 2,321,410.0 |
| forced inline, EA on | 363.58 ± 28.42 | 2,561,386.5 |
| default, **EA off** | 333.60 ± 50.65 | 2,561,410.3 |
| forced inline, EA off | 378.75 ± 42.74 | 2,561,410.6 |

    scalar replacement saves, by default:    240,000.3 B/op
    scalar replacement saves, when forced:        24.1 B/op
    the forced-inline allocation increase:   239,976.5 B/op

## The result

**Forcing `dispatch$1` to inline kills scalar replacement on this row.** With escape analysis on, the
default configuration eliminates 240,000 B/op; the forced configuration eliminates 24. Disabling
escape analysis entirely on the default configuration produces 2,561,410 B/op, which differs from the
forced configuration's 2,561,387 by **24 bytes out of 2.5 million**.

Two independent routes to the same number is what makes this a measurement rather than a reading:
enlarging the compilation unit and switching escape analysis off arrive at the same allocation, and
the amount forced-inlining costs equals the amount scalar replacement was saving.

## What it does not settle

The timing decomposition. Forced-with-EA-on (363.58) is slower than default-with-EA-off (333.60),
which would suggest forcing costs something beyond the lost elimination, and forced-with-EA-off
(378.75) is slower again. But the errors here are ±23 to ±51, roughly 8 to 15%, and the differences
are of that order. **The allocation figures are exact and per-operation; the timing figures on this
row are not resolvable at this sample size.** Only the allocation claim is made.

## What it means for the candidate

DIS-1's crude form buys 5 to 10% on five rows by making a 607-byte method inline, and pays for it by
enlarging the compilation unit past the point where escape analysis can scalar-replace on a sixth.
That is a specific, understood, and probably avoidable cost rather than a mystery.

It sharpens the candidate's real content: a tier split that inlines only a small degenerate path
would grow the unit far less than inlining all 607 bytes, and might clear the inlining win without
crossing the escape-analysis cliff. That is now a testable prediction with a named failure mode and a
row to watch.
