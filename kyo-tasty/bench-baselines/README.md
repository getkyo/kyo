# kyo-tasty bench-baselines

This directory holds baseline JSON files used by `BenchmarkRegressionTest` to detect performance regressions.

## Files

### post-campaign.json

Captured at the end of the audit-fixes campaign (Phase 27). Contains post-campaign benchmark numbers or the sentinel value `-1` when JMH was not run.

Fields:
- `cold_load_ms` - median cold-load time in milliseconds, or -1 if not yet measured
- `warm_cache_ms` - median warm-cache (snapshot hit) time in milliseconds, or -1 if not yet measured
- `description` - human-readable explanation of when and how the baseline was captured
- `captured_at_phase` - audit-fixes phase number when the file was written
- `captured_at_commit` - git description of the commit at capture time
- `note` - instructions for regenerating

### cold-load.json (future)

Pre-campaign baseline. Not present because no baseline was captured before the campaign started. This is documented in `BenchmarkRegressionTest` as a known gap: INV-027 is satisfied by the existence of benchmark infrastructure and a recorded post-campaign state.

## Regenerating baselines

To populate `cold_load_ms` and `warm_cache_ms` in `post-campaign.json`, run:

```
sbt 'kyo-tasty-bench/Jmh/run -i 5 -wi 5 -f 1 -bm avgt -tu ms'
```

Then update the JSON fields with the median values from the JMH output. Use the `ColdLoadBench` result for `cold_load_ms` and the warm-cache workload result for `warm_cache_ms`.

## Format

```json
{
  "description": "...",
  "captured_at_phase": 27,
  "captured_at_commit": "...",
  "note": "...",
  "cold_load_ms": 123.4,
  "warm_cache_ms": 45.6
}
```

All fields are required. Use `-1` as a sentinel when the measurement was not taken.
