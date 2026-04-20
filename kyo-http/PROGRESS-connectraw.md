# Progress: `connectRaw` for kyo-http + kyo-pod

## Status: PLANNING

## Phase 1: Add `HttpRawConnection` + `connectRaw` to kyo-http
- [ ] Step 1.1: Create `HttpRawConnection.scala` (public API)
- [ ] Step 1.2: Add `connectRaw` to `HttpClientBackend` (internal impl)
- [ ] Step 1.3: Add `connectRaw` extension on `HttpClient` (public API)
- [ ] Step 1.4: Tests for `connectRaw`

## Phase 2: Update kyo-pod with `connectRaw`
- [ ] Step 2.1: Implement `execInteractive` via `connectRaw`
- [ ] Step 2.2: Implement `attach` via `connectRaw`
- [ ] Step 2.3: Streaming demux helper for Docker multiplexed streams
- [ ] Step 2.4: Tests for kyo-pod changes

## Phase 3: Final validation
- [ ] Run full kyo-http test suite
- [ ] Run full kyo-pod test suite
