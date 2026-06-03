package kyo

// --- Reporter's repro: discriminator on a nested sealed-trait field ---
sealed trait NestedRO derives CanEqual
object NestedRO:
    final case class `string`(value: String) extends NestedRO derives CanEqual, Schema
    final case class `number`(value: Int)    extends NestedRO derives CanEqual, Schema
end NestedRO

given Schema[NestedRO] = Schema.derived[NestedRO].discriminator("type")
final case class NestedEnvelope(result: NestedRO) derives CanEqual, Schema

// --- Two-deep nesting of the same discriminator ---
final case class NestedTwoDeepMiddle(payload: NestedRO) derives CanEqual, Schema
final case class NestedTwoDeepOuter(middle: NestedTwoDeepMiddle) derives CanEqual, Schema

// --- .drop on nested schema ---
final case class NestedDropInner(visible: String, secret: String) derives CanEqual
given Schema[NestedDropInner] = Schema[NestedDropInner].drop("secret")
final case class NestedDropOuter(inner: NestedDropInner) derives CanEqual, Schema

// --- .rename on nested schema ---
final case class NestedRenameInner(x: Int) derives CanEqual
given Schema[NestedRenameInner] = Schema[NestedRenameInner].rename("x", "y")
final case class NestedRenameOuter(inner: NestedRenameInner) derives CanEqual, Schema

// --- .add on nested schema ---
final case class NestedAddInner(x: Int) derives CanEqual
given Schema[NestedAddInner] = Schema[NestedAddInner].add("derived")(_.x * 2)
final case class NestedAddOuter(inner: NestedAddInner) derives CanEqual, Schema

class NestedTransformTest extends kyo.test.Test[Any]:

    "discriminator survives one level of nesting (reporter's repro)" in {
        val v  = NestedEnvelope(NestedRO.`string`("hi"))
        val js = Json.encode(v)
        assert(js == """{"result":{"type":"string","value":"hi"}}""", js)
        val dec = Json.decode[NestedEnvelope](js)
        assert(dec == Result.succeed(v))
    }

    "discriminator survives two levels deep" in {
        val v  = NestedTwoDeepOuter(NestedTwoDeepMiddle(NestedRO.`number`(42)))
        val js = Json.encode(v)
        assert(js.contains("""{"type":"number","value":42}"""), js)
        assert(Json.decode[NestedTwoDeepOuter](js) == Result.succeed(v))
    }

    "drop on nested schema omits the dropped field at the inner level" in {
        val js = Json.encode(NestedDropOuter(NestedDropInner("v", "s")))
        assert(!js.contains("secret"), js)
        assert(js.contains("\"visible\":\"v\""), js)
    }

    "rename on nested schema renames at the inner level" in {
        val js = Json.encode(NestedRenameOuter(NestedRenameInner(5)))
        assert(js.contains("\"y\":5"), js)
    }

    "add (computed field) on nested schema emits the computed field at the inner level" in {
        val js = Json.encode(NestedAddOuter(NestedAddInner(3)))
        assert(js.contains("\"derived\":6"), js)
    }

    "discriminator + drop combine on a nested schema".pending(
        "chaining .discriminator and .drop on the same sum-type schema is not expressible today: the API rejects .drop on sealed traits"
    ) in {
        ()
        // Design follow-up: chaining `.discriminator(...)` and `.drop(...)` on
        // the same sum-type schema is not expressible today — the API rejects
        // `.drop` on sealed traits at compile time ("Schema.drop is not
        // supported for sealed traits") because transforms operate on case
        // class fields. Supporting this combination would require either a
        // sum-level field-removal primitive or per-variant `.drop` that
        // survives discriminator dispatch at nested positions.
    }

    "discriminator survives Protobuf round-trip" in {
        val v   = NestedEnvelope(NestedRO.`string`("hi"))
        val b   = Protobuf.encode(v)
        val dec = Protobuf.decode[NestedEnvelope](b)
        assert(dec == Result.succeed(v))
    }
end NestedTransformTest
