# Bug D fix — handoff to kyo-browser

## Status

Bug D (schema-level transforms silently bypassed when a `Schema` is consumed as a sub-schema by a parent's derived codec) is fixed on branch `worktree-squishy-wibbling-parrot`. Three macro emission sites in `SerializationMacro` now route through `kyo.internal.SchemaSerializer.writeTo` / `readFrom`, the only path that consults `Schema.hasTransforms`. The dispatch short-circuits to the raw codec call when `hasTransforms` is false, so the no-transform case has zero added cost. JSON round-tripping of nested discriminated/dropped/renamed/added schemas is now correct. Consume by pulling the fix commit (or waiting for the equivalent on `main`).

## Branch and commits

Branch: `worktree-squishy-wibbling-parrot`

```
db1bcc601 [schema] route nested field codecs through transform-aware dispatch
a91161eb4 [schema] add failing NestedTransformTest reproducing nested-transform bug
```

`a91161eb4` adds the failing tests; `db1bcc601` is the fix that makes them pass.

## What the bug was

Schema-level transforms (`.discriminator`, `.drop`, `.rename`, `.add`, `.computed`) were silently bypassed when the customised `Schema` was consumed as a sub-schema by a parent case class's derived codec. Reporter's repro:

```scala
case class Envelope(result: RO) derives Schema
// RO is a sealed trait with: Schema.derived[RO].discriminator("type")
```

Encoding an `Envelope` produced the wrapper form `{"result":{"string":{"value":"hi"}}}` instead of the expected flat-discriminator form `{"result":{"type":"string","value":"hi"}}`. Decoding the flat form into `Envelope` threw `UnknownVariantException("type")`.

Root cause: `SerializationMacro.caseClassWriteBody` (and the read-side counterparts in `caseClassReadBodyResolved`) emitted raw `serializeWrite` / `serializeRead` calls for `Maybe`, `Option`, and generic-reference fields, bypassing `SchemaSerializer.writeTo` / `readFrom` — the only dispatch that checks `Schema.hasTransforms` and applies the transform pipeline.

## What's now correct

Verified by `kyo-schema/shared/src/test/scala/kyo/NestedTransformTest.scala`:

1. `discriminator survives one level of nesting (reporter's repro)` — encode produces flat form; decode is the inverse.
2. `discriminator survives two levels deep` — `Outer(middle: Middle)` where `Middle(payload: RO)`.
3. `drop on nested schema omits the dropped field at the inner level`.
4. `rename on nested schema renames at the inner level`.
5. `add (computed field) on nested schema emits the computed field at the inner level`.
6. `discriminator + drop combine on a nested schema`.

## How to integrate on kyo-browser side

1. Pull from branch `worktree-squishy-wibbling-parrot` at commit `db1bcc601` (or use the equivalent once it lands on `main`).
2. Delete the hand-rolled `Schema.init` workaround that encodes/decodes `RemoteObject` and friends.
3. Replace with the natural form: `given Schema[RemoteObject] = Schema.derived[RemoteObject].discriminator("type")`, plus `derives Schema` on the envelopes (`EvalResult`, `ExceptionDetails`, `CdpReply[EvalResult]`).
4. Re-enable any tests previously marked `pending` / skipped due to this bug.
5. CDP wire format is JSON, so the fix fully covers your use case.

## Known follow-up: Bug E (Protobuf discriminator)

When the same schemas go through `Protobuf.encode` / `Protobuf.decode` instead of JSON, sealed-trait `.discriminator(field)` decode throws `MissingFieldException(field)`. Independent of Bug D: `DiscriminatorReader.objectStart` in `SchemaSerializer.scala` calls `inner.field()` and string-matches against the discriminator name; `ProtobufReader.field()` returns `fieldNames.getOrElse(fieldNumber, fieldNumber.toString)` and `fieldNames: Map[Int, String]` is never populated anywhere in kyo-schema (grep `withFieldNames` finds only the setter). Tracked as Bug E in `kyo-schema/improvement-plan/analysis.md`, scheduled as Phase 5 in the execution plan. Does not affect kyo-browser (CDP uses JSON).

## Macro-quirk caveats

- `Schema.derived[Inner].drop(_.field)` (lambda overload) fails to compile at the `derives Schema` site because the apply syntax strips the `Focused` refinement from the lambda. Use the string-name overload: `.drop("field")` / `.rename("from", "to")`.
- When defining `given Schema[Inner] = Schema[Inner].drop(...)` (note: `Schema[Inner]`, not `Schema.derived[Inner]` — the `transparent inline apply` preserves the refinement), the inner case class should NOT itself `derives Schema`, or you get given ambiguity. Define the inner type with no `derives`, then provide the customised given at package level so the outer's derivation captures it. `NestedTransformTest.scala` uses this pattern.

## Files touched on this branch

- `kyo-schema/shared/src/main/scala/kyo/internal/SerializationMacro.scala` (modified)
- `kyo-schema/shared/src/test/scala/kyo/NestedTransformTest.scala` (new)
