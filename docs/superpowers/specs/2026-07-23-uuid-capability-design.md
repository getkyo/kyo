# UUID capability design

## Status

- Approved by user on 2026-07-23
- Scope: `kyo-data`, `kyo-schema`, `kyo-core`
- Out of scope: `kyo-eventlog`

## Goal

Introduce the UUID capability intent into this repository with module placement and public API aligned to the approved intent:

1. `kyo-data`: pure UUID value and algorithms.
2. `kyo-schema`: schema and codec integration.
3. `kyo-core`: effectful generation capability and companion-style extension API.

## Architecture

### Module ownership

- `kyo-data` owns `kyo.UUID` as an opaque value with pure constructors and inspectors.
- `kyo-schema` owns `Schema[UUID]` and UUID codec behavior.
- `kyo-core` owns `UUIDGenerator` and exported `UUID` companion extensions:
  - `UUID.v4`
  - `UUID.v7`
  - `UUID.let`

This preserves dependency direction: data is foundational, schema depends on data, core depends on data and can expose effectful generation.

### Public surface

`kyo-data`:

- `opaque type UUID = UUID.Repr`
- Constants: `UUID.nil`, `UUID.max`
- Parsing:
  - `UUID.parse(String): Result[UUID.InvalidUUID, UUID]`
  - `UUID.parseUrn(String): Result[UUID.InvalidUUID, UUID]`
  - `UUID.fromBytes(Span[Byte]): Result[UUID.InvalidUUID, UUID]`
- Pure constructors:
  - `UUID.v5(namespace: UUID, name: Span[Byte]): UUID`
  - `UUID.v8Sha256(namespace: UUID, name: Span[Byte]): UUID`
- Extensions:
  - `show: String`
  - `bytes: Span[Byte]`
  - `version: Int`
  - `variant: UUID.Variant`
  - `unixTimestampMillis: Maybe[Long]`
  - `compare(that: UUID): Int`

`kyo-core`:

- `abstract class UUIDGenerator`
  - `def v4(using Frame): UUID < Sync`
  - `def v7(using Frame): UUID < Sync`
- `object UUIDGenerator`
  - `val live: UUIDGenerator`
  - `def let[A, S](generator: UUIDGenerator)(value: A < S)(using Frame): A < (S & Sync)`
- `object UUIDCoreExtensions` exports methods on `UUID.type`:
  - `UUID.v4`
  - `UUID.v7`
  - `UUID.let`

`kyo-schema`:

- Add `given uuidSchema: Schema[UUID]`.
- Keep explicit compatibility support for `Schema[java.util.UUID]` through interop conversion path.

## Behavioral contract

### Parsing and formatting

- Canonical text is lowercase `8-4-4-4-12`.
- `parse` accepts canonical form case-insensitively.
- `parse` rejects braces, URN text, missing separators, and non-hex characters.
- `parseUrn` accepts `urn:uuid:` form and validates canonical payload.
- `fromBytes` requires exactly 16 bytes.

### Ordering and inspection

- `compare` uses unsigned bytewise ordering.
- `unixTimestampMillis` returns `Present(millis)` only for v7 values, else `Absent`.

### Deterministic construction

- `v5` implements RFC name-based SHA-1 UUID behavior.
- `v8Sha256` implements the named Kyo profile with versioned domain marker and fixed encoding layout.

### Generation capability

- `UUIDGenerator.live` uses secure entropy sources only.
- No fallback to `Random`, timestamp-only synthesis, or process counters.
- v7 monotonicity is per generator instance with atomic state `(effectiveMillis, payload)`.
- If observed time repeats or moves backward, generator keeps effective millis and increments payload.
- On payload exhaustion, generator advances logical millisecond and reseeds payload from secure entropy.

## Error model

- Input errors (parse, bytes length, format) return typed `Result` failures via `UUID.InvalidUUID`.
- Generation failures remain effect failures in `Sync` and are not silently recovered.

## Testing strategy

All implementation follows test-first flow per module.

### `kyo-data` tests

- New `UUIDTest.scala` for:
  - parse success and failure cases
  - URN parsing behavior
  - bytes round-trip and invalid lengths
  - version and variant extraction
  - v7 timestamp inspection
  - ordering invariants
  - deterministic `v5` and `v8Sha256` vectors

### `kyo-schema` tests

- Update existing schema primitive structure tests for `Schema[UUID]`.
- Add encode/decode round-trip tests for UUID values in JSON and at least one binary codec.
- Keep explicit interop coverage for `java.util.UUID` compatibility path.

### `kyo-core` tests

- New `UUIDGeneratorTest.scala` for:
  - `UUID.v4` and `UUID.v7` extension wiring
  - `UUID.let` scoping behavior
  - monotonic v7 ordering under repeated timestamp
  - monotonic behavior under backward timestamp
  - deterministic generator test-double path

## Delivery plan (single branch, layered phases)

1. Phase 1: `kyo-data` UUID value + tests.
2. Phase 2: `kyo-schema` `Schema[UUID]` + tests + docs adjustments.
3. Phase 3: `kyo-core` generator capability + extension exports + parser support + tests.

Each phase must compile and keep existing behavior intact outside the UUID surface.

## Non-goals

- Eventlog identity policy integration in this change.
- Legacy UUID generation versions (v1, v3, v6) as generator APIs.
- Database-specific mapping helpers in core capability.
