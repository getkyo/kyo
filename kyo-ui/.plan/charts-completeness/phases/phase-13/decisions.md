# Phase P13 decisions: README refresh + doctest gate

## D-1: yScaleRight example location and form

Decision: added `.yScaleRight(_.linear(40000.0, 70000.0))` to the existing "Two axes" `twoAxis`
block (README line ~1188), immediately after `.yAxisRight(_.label("Upper bound"))`. The
`.linear(40000.0, 70000.0)` domain pads the `hi` column's observed range (46000..64000 from the
running `sales` dataset) by 6000 on each side, giving a fixed domain that is deterministic and
non-degenerate.

Alternative considered: a separate `dualScale` block. Rejected because the existing `twoAxis`
block is already the canonical dual-axis teaching example; adding `yScaleRight` there shows the
feature in its natural context without introducing a redundant block.

## D-2: Prose for yScaleRight

Added one sentence immediately before the code block:

  "`.yScaleRight` configures the right-axis scale independently of the left; pass any of the
  same `ScaleOverride` builders you would pass to `.yScale`:"

No em-dashes. No marketing language. Sentence explains the API contract (independence from
`.yScale`) and the builder vocabulary (same `ScaleOverride` interface). The existing prose
"Each axis gets its own independent scale" was retained (it is accurate after P11).

## D-3: P12 setter-removal edits already in place

Confirmed by reading the README before editing: all 6 AxisConfig-setter chain-start edits
(lines 1088, 1168, 1169, 1186, 1187, 1291 per prep section 2A) were applied by P12. No
re-application needed. The `rg` scan for `.left/.right/.top/.bottom` in the README returns only:

- Line 1089: `.legend(_.top)` -- LegendConfig, not AxisConfig. Untouched.
- Line 1170: `.legend(_.top)` -- same. Untouched.
- Line 1285: prose mentioning `.margins(_.left(80))` -- Margins builder, not AxisConfig. Untouched.

## D-4: No feature-list corrections needed

The README Charts section has no explicit feature/capability enumeration list. The campaign
changes (colorScale uniform on grouped-bar/area/text/errorBar, Y-axis tick rotation + theme font
on Y axis, highlight extended to line/area/text/errorBar) are internal lowering fixes for
existing public knobs. No README claim was found that enumerated which marks support these
features, so no existing claim was inaccurate. No correction made.

## D-5: Doctest gate result

Command: `sbt 'kyo-ui/doctest'`

Output:
```
[info] doctest-format: kyo-ui/jvm/../README.md (0 reformatted, 91 unchanged, 0 skipped)
[info] doctest: validating kyo-ui/README.md (43 classpath entries)
[info] doctest: total=90 compiled=82 cacheHits=0 warnings=0 failures=0
[success] Total time: 20 s
```

All blocks: failures=0, warnings=0. The `cacheHits=0` confirms no stale-artifact false green;
the plugin recompiled all blocks from source. 90 blocks total on JVM (7 `doctest:platform=js`
blocks excluded from the JVM run; 1 `doctest:expect=skipped` block compiled but not evaluated).
Log saved to `phases/phase-13/runs/doctest-001.log`.

## D-6: Files changed

- `kyo-ui/README.md`: prose sentence added and `.yScaleRight(_.linear(40000.0, 70000.0))` added
  to the `twoAxis` block. No other edits. Tree left dirty per instructions.
- `kyo-ui/.plan/charts-completeness/phases/phase-13/decisions.md`: this file.
