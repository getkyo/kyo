package kyo

import Record.*
import Schema.*

class SchemaTest extends kyo.test.Test[Any]:

    given CanEqual[Any, Any] = CanEqual.derived

    // =========================================================================
    // apply
    // =========================================================================

    "apply" - {

        "apply simple case class" in {
            val m                                                                    = Schema[MTPerson]
            val _: Schema[MTPerson] { type Focused = "name" ~ String & "age" ~ Int } = m
            succeed("type-resolution compile check: the type ascription above is the verification; no concrete runtime value to assert")
        }

        "apply nested case class" in {
            val m = Schema[MTTeam]
            val _: Schema[MTTeam] { type Focused = "name" ~ String & "lead" ~ MTPersonAddr & "members" ~ List[MTPersonAddr] } = m
            succeed("type-resolution compile check: the type ascription above is the verification; no concrete runtime value to assert")
        }

        "apply sealed trait" in {
            val m                                                                                         = Schema[MTShape]
            val _: Schema[MTShape] { type Focused = "MTCircle" ~ MTCircle | "MTRectangle" ~ MTRectangle } = m
            succeed("type-resolution compile check: the type ascription above is the verification; no concrete runtime value to assert")
        }

        "apply case class with defaults" in {
            val m                                                                                       = Schema[MTConfig]
            val _: Schema[MTConfig] { type Focused = "host" ~ String & "port" ~ Int & "ssl" ~ Boolean } = m
            succeed("type-resolution compile check: the type ascription above is the verification; no concrete runtime value to assert")
        }

        "apply generic case class" in {
            val m                                                                                   = Schema[MTPair[Int, String]]
            val _: Schema[MTPair[Int, String]] { type Focused = "first" ~ Int & "second" ~ String } = m
            succeed("type-resolution compile check: the type ascription above is the verification; no concrete runtime value to assert")
        }

        "apply single field" in {
            val m                                                        = Schema[MTWrapper]
            val _: Schema[MTWrapper] { type Focused = "value" ~ String } = m
            succeed("type-resolution compile check: the type ascription above is the verification; no concrete runtime value to assert")
        }

        "apply with container fields" in {
            val m                                                                         = Schema[MTOrder]
            val _: Schema[MTOrder] { type Focused = "id" ~ Int & "items" ~ List[MTItem] } = m
            succeed("type-resolution compile check: the type ascription above is the verification; no concrete runtime value to assert")
        }

        "apply primitive type" in {
            val m                                     = Schema[Int]
            val _: Schema[Int] { type Focused = Int } = m
            succeed("type-resolution compile check: the type ascription above is the verification; no concrete runtime value to assert")
        }

        "apply already structural" in {
            val m = Schema["name" ~ String & "age" ~ Int]
            val _: Schema["name" ~ String & "age" ~ Int] { type Focused = "name" ~ String & "age" ~ Int } = m
            succeed("type-resolution compile check: the type ascription above is the verification; no concrete runtime value to assert")
        }

        "apply recursive type" in {
            case class MTTree(value: Int, children: List[MTTree]) derives CanEqual, Schema
            val m                                                                              = Schema[MTTree]
            val _: Schema[MTTree] { type Focused = "value" ~ Int & "children" ~ List[MTTree] } = m
            succeed("type-resolution compile check: the type ascription above is the verification; no concrete runtime value to assert")
        }
    }

    // =========================================================================
    // fields
    // =========================================================================

    "fields" - {

        "schema fields for product" in {
            val fs = Schema[MTPerson].fieldDescriptors
            assert(fs.size == 2)
            assert(fs.map(_.name).toSet == Set("name", "age"))
        }

        "schema names for product" in {
            assert(Schema[MTPerson].fieldNames == Set("name", "age"))
        }

        "schema tag preserves type" in {
            val s                                                                    = Schema[MTPerson]
            val _: Schema[MTPerson] { type Focused = "name" ~ String & "age" ~ Int } = s
            assert(s.fieldNames == Set("name", "age"))
        }

        "schema navigate to field" in {
            val s = Schema[MTPerson]
            assert(s.focus(_.name).tag =:= Tag[String])
        }

        "schema navigate to nested" in {
            val s = Schema[MTPersonAddr]
            assert(s.focus(_.address).tag =:= Tag[MTAddress])
            val addrFields = Schema[MTAddress].fieldDescriptors
            assert(addrFields.map(_.name).toSet == Set("street", "city", "zip"))
        }

        "schema navigate nested then field" in {
            val s = Schema[MTPersonAddr]
            assert(s.focus(_.address).focus(_.city).tag =:= Tag[String])
        }

        "schema for sealed trait" in {
            val names = Schema[MTShape].fieldNames
            assert(names == Set("MTCircle", "MTRectangle"))
        }

        "schema navigate to variant" in {
            val s = Schema[MTShape]
            assert(s.focus(_.MTCircle).tag =:= Tag[MTCircle])
            val circleFields = Schema[MTCircle].fieldDescriptors
            assert(circleFields.map(_.name).toSet == Set("radius"))
        }

        "schema navigate nonexistent compile error" in {
            typeCheckFailure("Schema[kyo.MTPerson].focus(_.nonexistent)")("not found")
        }

        "schema fields count" in {
            assert(Schema[MTPerson].fieldDescriptors.size == 2)
        }

        "schema after navigation" in {
            val fs = Schema[MTAddress].fieldDescriptors
            assert(fs.map(_.name).toSet == Set("street", "city", "zip"))
        }

        "schema field tag access" in {
            val fs = Schema[MTPerson].fieldDescriptors
            assert(fs.map(_.name).toSet == Set("name", "age"))
        }

        "schema for container type" in {
            val names = Schema[MTOrder].fieldNames
            assert(names.contains("items"))
        }

        "schema for single field" in {
            assert(Schema[MTWrapper].fieldDescriptors.size == 1)
        }

        "schema field name access" in {
            val names = Schema[MTPerson].fieldDescriptors.map(_.name)
            assert(names.contains("name"))
            assert(names.contains("age"))
        }

        "schema tag for nested" in {
            val s = Schema[MTPersonAddr].focus(_.address)
            assert(s.tag =:= Tag[MTAddress])
        }

        "schema on generic type" in {
            val s = Schema[MTPair[Int, String]]
            assert(s.fieldNames == Set("first", "second"))
            assert(s.focus(_.first).tag =:= Tag[Int])
            assert(s.focus(_.second).tag =:= Tag[String])
        }

        "schema on sealed trait" in {
            val s = Schema[MTShape]
            assert(s.fieldNames.contains("MTCircle"))
            assert(s.fieldNames.contains("MTRectangle"))
            assert(s.focus(_.MTCircle).tag =:= Tag[MTCircle])
            assert(Schema[MTCircle].fieldDescriptors.map(_.name).toSet == Set("radius"))
            assert(s.focus(_.MTRectangle).tag =:= Tag[MTRectangle])
            assert(Schema[MTRectangle].fieldDescriptors.map(_.name).toSet == Set("width", "height"))
        }
    }

    // =========================================================================
    // defaults
    // =========================================================================

    "defaults" - {

        "defaults returns default values" in {
            val d = Schema[MTConfig].defaults
            assert(d.port == 8080)
            assert(d.ssl == false)
        }

        "defaults navigable port" in {
            val d         = Schema[MTConfig].defaults
            val port: Int = d.port
            assert(port == 8080)
        }

        "defaults navigable ssl" in {
            val d            = Schema[MTConfig].defaults
            val ssl: Boolean = d.ssl
            assert(ssl == false)
        }

        "defaults no default compile error" in {
            typeCheckFailure("Schema[kyo.MTConfig].defaults.host")("host")
        }

        "defaults all fields have defaults" in {
            val d = Schema[MTAllDefaults].defaults
            assert(d.a == 1)
            assert(d.b == "hello")
            assert(d.c == false)
        }

        "defaults with int type" in {
            val d      = Schema[MTAllDefaults].defaults
            val a: Int = d.a
            assert(a == 1)
        }

        "defaults with boolean type" in {
            val d          = Schema[MTAllDefaults].defaults
            val c: Boolean = d.c
            assert(c == false)
        }

        "defaults with string type" in {
            val d         = Schema[MTAllDefaults].defaults
            val b: String = d.b
            assert(b == "hello")
        }

        "defaults count" in {
            val d = Schema[MTConfig].defaults
            val f = d.fields
            assert(f.size == 2)
        }

        "defaults preserves type" in {
            val d            = Schema[MTConfig].defaults
            val port: Int    = d.port
            val ssl: Boolean = d.ssl
            assert(port == 8080)
            assert(ssl == false)
        }

        "defaults on class without defaults" in {
            val d = Schema[MTPerson].defaults
            val f = d.fields
            assert(f.isEmpty)
        }

        "defaults via Schema access" in {
            val d = Schema[MTConfig].defaults
            assert(d.port == 8080)
        }

        "defaults nested type with default" in {
            val d = Schema[MTNestedDefault].defaults
            assert(d.address == MTAddress("", "", ""))
        }
    }

    // =========================================================================
    // withStructure
    // =========================================================================

    "withStructure reports the overridden structure and keeps the generic codec" in {
        // Overriding the open Schema[Structure.Value] with a runtime-built Product yields a
        // shape-dynamic schema: the wire shape is the override, encode and decode stay the generic
        // Structure.Value codec, with no hand-written reader or writer.
        val payload =
            Structure.Type.Product(
                "Payload",
                Tag[Any],
                Chunk.empty,
                Chunk(
                    Structure.Field("active", summon[Schema[Boolean]].structure),
                    Structure.Field("tags", summon[Schema[List[String]]].structure)
                )
            )
        val shape =
            Structure.Type.Product(
                "Dynamic",
                Tag[Any],
                Chunk.empty,
                Chunk(
                    Structure.Field("note", summon[Schema[String]].structure),
                    Structure.Field("payload", payload)
                )
            )
        val schema = summon[Schema[Structure.Value]].withStructure(shape)
        schema.structure match
            case p: Structure.Type.Product =>
                assert(p.name == "Dynamic", s"expected the overridden Product name; got ${p.name}")
                assert(p.fields.map(_.name) == Chunk("note", "payload"), s"expected the overridden fields; got ${p.fields.map(_.name)}")
            case other => fail(s"expected the overridden Product structure; got $other")
        end match

        val value = Structure.Value.Record(Chunk[(String, Structure.Value)](
            "note"    -> Structure.Value.Str("n"),
            "payload" -> Structure.Value.Record(Chunk[(String, Structure.Value)](
                "active" -> Structure.Value.Bool(true),
                "tags"   -> Structure.Value.Sequence(Chunk(Structure.Value.Str("a")))
            ))
        ))
        val enc = schema.encodeString[Json](value)
        assert(enc == """{"note":"n","payload":{"active":true,"tags":["a"]}}""", s"conforming record must encode plainly; got $enc")
        assert(schema.decodeString[Json](enc) == Result.succeed(value), "conforming record must round-trip")
    }

    "withStructure leaves the base schema untouched" in {
        val base  = summon[Schema[Structure.Value]]
        val shape =
            Structure.Type.Product(
                "Solo",
                Tag[Any],
                Chunk.empty,
                Chunk(Structure.Field("text", summon[Schema[String]].structure))
            )
        val overridden = base.withStructure(shape)
        overridden.structure match
            case p: Structure.Type.Product => assert(p.name == "Solo", s"override must apply; got ${p.name}")
            case other                     => fail(s"expected a Product structure; got $other")
        assert(base.structure != overridden.structure, "the base Schema[Structure.Value] must keep its own structure")
    }

    "withStructure does not validate: a non-conforming record still round-trips" in {
        // The override is reporting-only; the open codec is a passthrough. Enforcement is a consumer
        // concern (Structure.conform), so this pins the actual codec behavior explicitly.
        val shape =
            Structure.Type.Product(
                "Strict",
                Tag[Any],
                Chunk.empty,
                Chunk(Structure.Field("required", summon[Schema[String]].structure))
            )
        val schema        = summon[Schema[Structure.Value]].withStructure(shape)
        val nonConforming = Structure.Value.Record(Chunk[(String, Structure.Value)]("other" -> Structure.Value.Str("x")))
        val enc           = schema.encodeString[Json](nonConforming)
        assert(enc == """{"other":"x"}""", s"the codec must not reject or reshape a non-conforming record; got $enc")
        assert(schema.decodeString[Json](enc) == Result.succeed(nonConforming), "decode is equally passthrough")
        assert(Structure.conform(nonConforming, shape) == Present("missing required field 'required'"))
    }

    "withStructure composes with transform to expose a domain type" in {
        case class Note(text: String)
        val shape =
            Structure.Type.Product(
                "Note",
                Tag[Any],
                Chunk.empty,
                Chunk(Structure.Field("text", summon[Schema[String]].structure))
            )
        def toNote(v: Structure.Value): Note =
            v match
                case Structure.Value.Record(fields) =>
                    Note(fields.collectFirst { case ("text", Structure.Value.Str(s)) => s }.getOrElse(""))
                case _ => Note("")
        def fromNote(n: Note): Structure.Value =
            Structure.Value.Record(Chunk[(String, Structure.Value)]("text" -> Structure.Value.Str(n.text)))
        val schema = summon[Schema[Structure.Value]].withStructure(shape).transform(toNote)(fromNote)
        val enc    = schema.encodeString[Json](Note("hi"))
        assert(enc == """{"text":"hi"}""", s"domain value must encode through the swapped structure; got $enc")
        assert(schema.decodeString[Json](enc) == Result.succeed(Note("hi")), "domain value must round-trip")
    }

    "a schema builder takes no Frame" - {
        import SBFGivens.given

        "every builder builds a given where no Frame can be derived, and the schema round-trips" in {
            val account = SBFAccount("Ada", "Lovelace")
            assert(Json.encode(account) == """{"first_name":"Ada","last_name":"Lovelace"}""")
            assert(Json.decode[SBFAccount]("""{"given_name":"Ada","last_name":"Lovelace"}""") == Result.succeed(account))
            val order = SBFOrder(3, "ab1", Chunk("x"))
            assert(Json.decode[SBFOrder](Json.encode(order)) == Result.succeed(order))
            assert(Json.decode[SBFOrder]("""{"count":3,"sku":"ab1","tags":["x"]}""") == Result.succeed(order))
            assert(Protobuf.decode[SBFOrder](Protobuf.encode(order)) == Result.succeed(order))
            assert(Json.decode[SBFShape]("""{"type":"round","radius":2}""") == Result.succeed(SBFCircle(2)))
            Seq[SBFShape](SBFCircle(2), SBFSquare(1)).foreach(s => assert(Json.decode[SBFShape](Json.encode(s)) == Result.succeed(s)))
            Seq[SBFPoint](SBFFlat(1), SBFSpace(1, 2)).foreach(p => assert(Json.decode[SBFPoint](Json.encode(p)) == Result.succeed(p)))
            Seq[SBFCoin](SBFHeads(), SBFTails()).foreach(c => assert(Json.decode[SBFCoin](Json.encode(c)) == Result.succeed(c)))
            assert(Json.decode[SBFPage]("""{"label":"a"}""") == Result.succeed(SBFPage(10, "a")))
        }

        "a misconfigured builder fails at the first decode that reaches it, with that call's Frame and the builder that caused it" in {
            val decodeSite = summon[Frame]
            Json.decode[SBFHolder]("""{"clash":{"type":"side","n":1}}""")(using summon[Json], summon[Schema[SBFHolder]], decodeSite) match
                case Result.Panic(e: VariantNameCollisionException) =>
                    assert(e.wireName == "side")
                    assert(e.variants == Chunk("SBFLeft", "SBFRight"))
                    assert(e.frame == decodeSite, s"raised at ${e.frame}, decoded at $decodeSite")
                    assert(e.getMessage.contains("variantNames"), e.getMessage)
                case other => fail(s"expected a VariantNameCollisionException, got $other")
            end match
        }

        "a misconfigured builder fails at the first encode that reaches it, with that call's Frame" in {
            val encodeSite = summon[Frame]
            Result.catching[VariantNameCollisionException](
                Json.encode(SBFHolder(SBFLeft(1)))(using summon[Schema[SBFHolder]], encodeSite, summon[Json])
            ) match
                case Result.Failure(e) =>
                    assert(e.wireName == "side")
                    assert(e.variants == Chunk("SBFLeft", "SBFRight"))
                    assert(e.frame == encodeSite, s"raised at ${e.frame}, encoded at $encodeSite")
                    assert(e.getMessage.contains("variantNames"), e.getMessage)
                case other => fail(s"expected a VariantNameCollisionException, got $other")
            end match
        }

        "a field transformer that refuses its input fails the decode with the decode call's Frame and the field's path, never a throw" in {
            assert(Json.decode[SBFLine]("""{"sku":"ab1","quantity":2}""").map(_.sku.value) == Result.succeed("ab1"))
            val decodeSite = summon[Frame]
            Json.decode[SBFLine]("""{"sku":"a b","quantity":2}""")(using summon[Json], summon[Schema[SBFLine]], decodeSite) match
                case Result.Failure(e: ConstructorRejectedException) =>
                    assert(e.path == Seq("sku"))
                    assert(e.frame == decodeSite, s"decoded at $decodeSite, reported at ${e.frame}")
                    e.rejection match
                        case leaf: SBFInvalidSku =>
                            assert(leaf.text == "a b")
                            assert(leaf.frame == decodeSite, s"decoded at $decodeSite, rejected at ${leaf.frame}")
                        case other => fail(s"expected the constructor's own failure, got $other")
                    end match
                case other => fail(s"expected a ConstructorRejectedException, got $other")
            end match
        }

        "a failed check carries the validate call's Frame" in {
            def validated(order: SBFOrder)(using site: Frame) = (site, summon[Schema[SBFOrder]].validate(order))
            val (validateSite, failures)                      = validated(SBFOrder(200, "zz", Chunk.empty))
            assert(failures.map(_.message).toSet == Set("count is at most 100", "code is not zz"))
            failures.foreach(f => assert(f.frame == validateSite, s"failed at ${f.frame}, validated at $validateSite"))
        }
    }

end SchemaTest

object OSHolder:
    opaque type Code = String
    final case class Labelled(@kyo.schema.rename("the_label") label: String, count: Int) derives CanEqual
end OSHolder

object OSHolderSchemas:
    given Schema[OSHolder.Labelled] = Schema.derived[OSHolder.Labelled]

sealed abstract case class DVPort private (value: Int) derives CanEqual
object DVPort:
    def make(value: Int): Result[String, DVPort] =
        if value > 0 && value < 65536 then Result.succeed(new DVPort(value) {})
        else Result.fail(s"port out of range: $value")

    given Schema[DVPort] = Schema.derivedVia(make)
end DVPort

case class DVPortTwin(value: Int) derives CanEqual

sealed abstract case class DVSpan private (lo: Int, hi: Int) derives CanEqual
object DVSpan:
    def init(lo: Int, hi: Int)(using Frame): Result[DVInvalidSpan, DVSpan] =
        if lo <= hi then Result.succeed(new DVSpan(lo, hi) {}) else Result.fail(DVInvalidSpan())

    given Schema[DVSpan] = Schema.derivedVia((lo: Int, hi: Int) => init(lo, hi))
end DVSpan

final class DVInvalidSpan(using Frame) extends KyoException("a span's low end is not above its high end")

case class DVListener(name: String, port: DVPort) derives CanEqual, Schema

case class DVNonEmpty[A](items: Chunk[A]) derives CanEqual
object DVNonEmpty:
    def make[A](items: Chunk[A]): Result[String, DVNonEmpty[A]] =
        if items.nonEmpty then Result.succeed(DVNonEmpty(items))
        else Result.fail("must not be empty")

    given nonEmptySchema[A](using Schema[A]): Schema[DVNonEmpty[A]] = Schema.derivedVia(make[A])
end DVNonEmpty

sealed abstract case class DVRange private (low: Int, high: Int) derives CanEqual
object DVRange:
    def make(low: Int, high: Int): Result[String, DVRange] =
        if low <= high then Result.succeed(new DVRange(low, high) {})
        else Result.fail(s"low $low exceeds high $high")

    given Schema[DVRange] = Schema.derivedVia(make)
end DVRange

case class DVUpper(name: String) derives CanEqual
object DVUpper:
    def make(name: String): DVUpper = DVUpper(name.toUpperCase)

    given Schema[DVUpper] = Schema.derivedVia(make)
end DVUpper

case class DVEven(n: Int) derives CanEqual
object DVEven:
    def make(n: Int): Option[DVEven] = if n % 2 == 0 then Some(DVEven(n)) else None

    given Schema[DVEven] = Schema.derivedVia(make)
end DVEven

case class DVPositive(v: Long) derives CanEqual
object DVPositive:
    def make(v: Long): Maybe[DVPositive] = if v > 0 then Present(DVPositive(v)) else Absent

    given Schema[DVPositive] = Schema.derivedVia(make)
end DVPositive

case class DVCode(code: String) derives CanEqual
object DVCode:
    def make(code: String): Either[String, DVCode] =
        if code.nonEmpty then Right(DVCode(code)) else Left("empty code")

    given Schema[DVCode] = Schema.derivedVia(make)
end DVCode

case class DVShort(raw: String) derives CanEqual
object DVShort:
    def make(raw: String): scala.util.Try[DVShort] =
        scala.util.Try:
            require(raw.length <= 3, s"too long: $raw")
            DVShort(raw)

    given Schema[DVShort] = Schema.derivedVia(make)
end DVShort

case class DVTagged(id: Int, label: Option[String]) derives CanEqual
object DVTagged:
    def make(id: Int, label: Option[String]): Result[String, DVTagged] =
        if id > 0 then Result.succeed(DVTagged(id, label)) else Result.fail("id must be positive")

    given Schema[DVTagged] = Schema.derivedVia(make)
end DVTagged

sealed case class SCCSingle(x: Int) derives CanEqual
sealed case class SCCMulti(name: String, age: Int) derives CanEqual
case class SCCPlainTwin(x: Int) derives CanEqual
case class SCCHolder(id: Int, inner: SCCSingle) derives CanEqual
given Schema[SCCSingle] = Schema.derived

sealed trait SCCShape derives CanEqual
object SCCShape:
    case class Circle(r: Int) extends SCCShape
    case class Square(s: Int) extends SCCShape
end SCCShape

sealed trait SCCNode derives CanEqual
object SCCNode:
    sealed case class Leaf(v: Int) extends SCCNode
    case class Label(name: String) extends SCCNode
end SCCNode

sealed trait SCCTop derives CanEqual
object SCCTop:
    sealed abstract class Mid extends SCCTop
    object Mid:
        case class Concrete(n: Int) extends Mid
        case class Other(s: String) extends Mid
    end Mid
    case class Direct(flag: Boolean) extends SCCTop
end SCCTop

case class UnionCaseA(label: String, value: Int) derives CanEqual, Schema
case class UnionCaseB(flag: Boolean) derives CanEqual, Schema

object SfA:
    case class Dup(x: Int) derives CanEqual, Schema

object SfB:
    case class Dup(y: Int) derives CanEqual, Schema

case class Cart(items: Chunk[String], note: Maybe[String]) derives Schema, CanEqual

case class StrictPerson(id: Int, name: String) derives CanEqual, Schema
case class StrictRename(firstName: String, lastName: String) derives CanEqual, Schema
case class MTRenamedCityHolder(city: String, home: MTAddress) derives CanEqual, Schema
case class StrictFieldCase(firstName: String, lastName: String) derives CanEqual, Schema
case class StrictInner(value: Int) derives CanEqual, Schema
case class StrictOuter(name: String, inner: StrictInner) derives CanEqual, Schema
case class StrictFlattenChild(code: String, quantity: Int) derives CanEqual, Schema
case class StrictFlattenParent(id: Int, child: StrictFlattenChild) derives CanEqual, Schema

case class DefaultPrimitive(id: Int, name: String) derives CanEqual, Schema
case class DefaultNestedChild(code: String, quantity: Int) derives CanEqual, Schema
case class DefaultNestedParent(id: Int, child: DefaultNestedChild) derives CanEqual, Schema
case class DefaultCollection(items: Chunk[String], name: String) derives CanEqual, Schema
case class DefaultSumParent(id: Int, shape: MTShape) derives CanEqual, Schema
case class DefaultWithScala(id: Int, name: String = "scala") derives CanEqual, Schema
case class DefaultRename(firstName: String, lastName: String) derives CanEqual, Schema
case class DefaultDrop(id: Int, removed: Maybe[String]) derives CanEqual, Schema

case class OmitAllPolicyCase(
    items: Chunk[String],
    tags: Map[String, Int],
    note: Option[String],
    name: String
) derives CanEqual, Schema

case class OmitShadowCase(a: List[Int], b: List[Int]) derives CanEqual, Schema

// Fixtures: product fields must not be omitted under omitEmptyCollections
case class EmptyMapProduct(theMap: Map[String, Int]) derives CanEqual, Schema
case class MapAndProductSibling(tags: Map[String, Int], nested: EmptyMapProduct) derives CanEqual, Schema

case class InnerWithEmptyCollection(items: Chunk[String]) derives CanEqual, Schema
case class OuterWithNestedProduct(inner: InnerWithEmptyCollection, label: String) derives CanEqual, Schema

case class CartWithMap(tags: Map[String, Int], name: String) derives CanEqual, Schema
case class CartWithList(items: List[String], name: String) derives CanEqual, Schema

case class PredicateOmitCase(label: String, count: Int = 0) derives CanEqual, Schema

case class WhenDefaultInner(x: Int, y: Int) derives CanEqual, Schema
case class WhenDefaultOuter(label: String, inner: WhenDefaultInner = WhenDefaultInner(1, 2)) derives CanEqual, Schema

case class CrossFeaturePerson(firstName: String, score: Int = 0) derives CanEqual, Schema

// --- Enum derivation fixtures ---

enum EDSimple derives Schema, CanEqual:
    case Red, Green, Blue
end EDSimple

enum EDParam derives Schema, CanEqual:
    case Point(x: Int, y: Int)
    case Line(a: Int, b: Int, c: Int)
end EDParam

enum EDMixed derives Schema, CanEqual:
    case Named(label: String)
    case Anonymous
end EDMixed

enum EDDefaults derives Schema, CanEqual:
    case Full(a: Int, b: String = "def-b", c: Boolean = true)
    case Empty
end EDDefaults

enum EDWithBody derives Schema, CanEqual:
    case Alpha(x: Int)
    case Beta

    def describe: String = this match
        case Alpha(x) => s"alpha-$x"
        case Beta     => "beta"
end EDWithBody

trait EDLabelled:
    def kind: String = "labelled"

enum EDExtendsTrait extends EDLabelled derives Schema, CanEqual:
    case One(x: Int)
    case Two
end EDExtendsTrait

object EDHolder:
    enum Nested derives Schema, CanEqual:
        case X
        case Y(v: Int)
    end Nested
end EDHolder

enum EDInner derives Schema, CanEqual:
    case A
    case B(v: Int)
end EDInner

enum EDOuter derives Schema, CanEqual:
    case Wrap(inner: EDInner)
    case Plain
end EDOuter

enum EDCustomApply derives Schema, CanEqual:
    case Item(code: String)
    case Blank
end EDCustomApply

object EDCustomApply:
    def make(code: String): EDCustomApply = Item(code.toUpperCase)
end EDCustomApply

enum EDSingle derives Schema, CanEqual:
    case Only(x: Int)
end EDSingle

enum EDMany derives Schema, CanEqual:
    case M0, M1, M2, M3, M4, M5, M6, M7, M8, M9, M10, M11, M12, M13, M14, M15, M16, M17, M18, M19, M20, M21, M22, M23, M24
end EDMany

// A field-id pin on a parameterized enum case's own field. `@proto.fieldNumber` is the supported
// per-variant field pin: it is captured directly off the case's field at derivation time and folded
// into the enum's own derived Schema. A standalone `given Schema[EDPinCase.Pinned]` built with the
// programmatic `.fieldId` builder is NOT an alternative for this: enum/sealed-trait derivation always
// emits each variant's Schema fresh (for monomorphic dispatch and for safe self-recursion via the
// parent-schema tie-knot), so a case's own pre-existing given is never consulted; only field-level
// annotations (`@proto.fieldNumber`, `@rename`, `@alias`, `@doc`, `@omit`, `@transform`) are captured
// from a variant's fields, per Schema.fieldId's scaladoc.
enum EDPinCase derives Schema, CanEqual:
    case Pinned(@kyo.schema.proto.fieldNumber(99) x: Int, y: Int)
    case Other
end EDPinCase

case class EDHolderProduct(id: Int, kind: EDMixed) derives CanEqual, Schema
case class EDOptionHolder(maybeKind: Option[EDMixed]) derives CanEqual, Schema
case class EDListHolder(kinds: List[EDMixed]) derives CanEqual, Schema
case class EDMapValueHolder(byName: Map[String, EDMixed]) derives CanEqual, Schema
case class EDMapKeyHolder(byKind: Map[EDMixed, Int]) derives CanEqual, Schema
case class EDSetHolder(kinds: Set[EDSimple]) derives CanEqual, Schema

case class EDTransformHolder(kind: EDMixed, secret: String) derives CanEqual
given edTransformHolderSchema: Schema[EDTransformHolder] = Schema[EDTransformHolder].rename("kind", "type_of").drop("secret")
