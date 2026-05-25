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

// --- discriminator + drop combined on nested schema ---
// Drop applies to the case-class variants (.drop on a sealed trait is not
// supported by design); discriminator applies to the trait. Together, when
// a value is encoded through the trait inside an envelope, the wire should
// carry the discriminator AND omit the dropped variant field.
sealed trait NestedDiscDropRO derives CanEqual
object NestedDiscDropRO:
    final case class `string`(value: String, metadata: String) extends NestedDiscDropRO derives CanEqual
    final case class `number`(value: Int, metadata: String)    extends NestedDiscDropRO derives CanEqual
end NestedDiscDropRO

given Schema[NestedDiscDropRO.`string`] = Schema[NestedDiscDropRO.`string`].drop("metadata")
given Schema[NestedDiscDropRO.`number`] = Schema[NestedDiscDropRO.`number`].drop("metadata")
given Schema[NestedDiscDropRO]          = Schema.derived[NestedDiscDropRO].discriminator("type")

final case class NestedDiscDropEnvelope(result: NestedDiscDropRO) derives CanEqual, Schema

class NestedTransformTest extends Test:

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

    "discriminator + drop combine on a nested schema" in {
        val v  = NestedDiscDropEnvelope(NestedDiscDropRO.`string`("hi", "meta-payload"))
        val js = Json.encode(v)
        // Discriminator applied: nested object uses flat "type":"string" form, not wrapper.
        assert(js.contains("\"type\":\"string\""), js)
        // Drop applied: nested object omits the "metadata" field.
        assert(!js.contains("metadata"), js)
        assert(!js.contains("meta-payload"), js)
        // The remaining value field is preserved.
        assert(js.contains("\"value\":\"hi\""), js)
        // Round-trip: decoded value has the dropped field defaulted to empty.
        // (drop omits on write; on read, the field is missing from wire and the
        // generated decoder leaves the case-class default — empty string here since
        // no explicit default was set.)
        val dec = Json.decode[NestedDiscDropEnvelope](js)
        assert(dec.isSuccess, dec)
        val Result.Success(decoded) = dec: @unchecked
        assert(decoded.result.isInstanceOf[NestedDiscDropRO.`string`], decoded.result.getClass.getName)
        val s = decoded.result.asInstanceOf[NestedDiscDropRO.`string`]
        assert(s.value == "hi", s.value)
        // The dropped `metadata` field has no explicit default on the case class. The macro-emitted decoder
        // falls through to `zeroInitTerm`, which returns `""` for `String` fields. Lock that contract here so
        // future changes to the default-init policy are caught by this test rather than going silent.
        assert(s.metadata == "", s.metadata)
    }

    "discriminator survives Protobuf round-trip" in {
        val v   = NestedEnvelope(NestedRO.`string`("hi"))
        val b   = Protobuf.encode(v)
        val dec = Protobuf.decode[NestedEnvelope](b)
        assert(dec == Result.succeed(v))
    }
end NestedTransformTest
