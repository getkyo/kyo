package kyo

import kyo.Schema.*
import kyo.internal.JsonReader
import kyo.internal.JsonWriter
import kyo.internal.ProtobufReader
import kyo.internal.ProtobufWriter
import kyo.internal.StructureValueReader
import kyo.internal.StructureValueWriter

case class RTTree(value: Int, children: List[RTTree]) derives CanEqual, Schema
case class RTPersonDTO(name: String, age: Int) derives CanEqual
case class RTPersonDiff(name: String, age: String) derives CanEqual

case class AllPrimitives(
    i: Int,
    l: Long,
    s: Short,
    b: Byte,
    c: Char,
    f: Float,
    d: Double,
    bi: BigInt,
    bd: BigDecimal,
    str: String,
    bool: Boolean
) derives Schema,
      CanEqual

// Variant dispatch for all-no-arg enums must use reference equality, not isInstanceOf. Widening a singleton term-ref to the parent enum type would match every variant.

enum AllNoArgEnumA derives Schema, CanEqual:
    case First
    case Second
    case Third
end AllNoArgEnumA

enum MixedArityEnum derives Schema, CanEqual:
    case Alpha(x: Int)
    case Beta
    case Gamma
end MixedArityEnum

// Generic sealed trait for typeParams regression: derived Sum must populate typeParams.
// Variants are concrete so the sealed-trait macro can emit each variant schema without
// needing to substitute the parent's type argument into a generic child type.
sealed trait GenericSealed[A] derives Schema, CanEqual
case class GenericSealedA(a: Int) extends GenericSealed[Int] derives CanEqual
case class GenericSealedB(b: Int) extends GenericSealed[Int] derives CanEqual

// Scala 2 style sealed trait with mixed case class / case object cases.
sealed trait SealedNoArgVariants derives Schema, CanEqual
object SealedNoArgVariants:
    case class Labeled(name: String) extends SealedNoArgVariants derives CanEqual
    case object Unit1                extends SealedNoArgVariants derives CanEqual
    case object Unit2                extends SealedNoArgVariants derives CanEqual
    case object Unit3                extends SealedNoArgVariants derives CanEqual
end SealedNoArgVariants

// annotations carrier fixtures
final class L1NodeAnnotation extends scala.annotation.StaticAnnotation
case class L1Plain(a: Int, b: String) derives Schema
@L1NodeAnnotation case class L1Node(value: Int, @L1NodeAnnotation next: Maybe[L1Node]) derives Schema

class StructureTest extends kyo.test.Test[Any]:

    given CanEqual[Any, Any]                       = CanEqual.derived
    given CanEqual[Structure.Type, Structure.Type] = CanEqual.derived

    private def toStructure[A](schema: Schema[A], value: A): Structure.Value =
        val w = new StructureValueWriter()
        schema.writeTo(value, w)
        w.getResult
    end toStructure

    private def fromStructure[A](schema: Schema[A], dv: Structure.Value): A =
        val r = new StructureValueReader(dv)
        schema.readFrom(r)
    end fromStructure

    // ==================== Structure.of ====================

    "Structure.of" - {

        "primitive types produce Primitive with correct Tag" in {
            val intRef = Structure.of[Int]
            intRef match
                case Structure.Type.Primitive(kind, tag) =>
                    assert(kind == Structure.PrimitiveKind.Int)
                    assert(tag =:= Tag[Int])
                case other => fail(s"Expected Primitive, got $other")
            end match

            val strRef = Structure.of[String]
            strRef match
                case Structure.Type.Primitive(kind, tag) =>
                    assert(kind == Structure.PrimitiveKind.String)
                    assert(tag =:= Tag[String])
                case other => fail(s"Expected Primitive, got $other")
            end match

            val boolRef = Structure.of[Boolean]
            boolRef match
                case Structure.Type.Primitive(kind, tag) =>
                    assert(kind == Structure.PrimitiveKind.Boolean)
                    assert(tag =:= Tag[Boolean])
                case other => fail(s"Expected Primitive, got $other")
            end match

            val doubleRef = Structure.of[Double]
            doubleRef match
                case Structure.Type.Primitive(kind, tag) =>
                    assert(kind == Structure.PrimitiveKind.Double)
                    assert(tag =:= Tag[Double])
                case other => fail(s"Expected Primitive, got $other")
            end match
        }

        "case class produces Product with fields in declaration order" in {
            val ref = Structure.of[MTPerson]
            ref match
                case Structure.Type.Product(name, tag, _, fields, _) =>
                    assert(name == "MTPerson")
                    assert(tag =:= Tag[MTPerson])
                    assert(fields.size == 2)
                    assert(fields(0).name == "name")
                    assert(fields(1).name == "age")
                case other => fail(s"Expected Product, got $other")
            end match
        }

        "each field has correct name, nested Structure, and default" in {
            val ref = Structure.of[MTConfig]
            ref match
                case Structure.Type.Product(_, _, _, fields, _) =>
                    assert(fields.size == 3)

                    // host: String, no default
                    val host = fields(0)
                    assert(host.name == "host")
                    host.fieldType match
                        case Structure.Type.Primitive(_, tag) => assert(tag =:= Tag[String])
                        case other                            => fail(s"Expected Primitive for host, got $other")
                    assert(host.default.isEmpty)

                    // port: Int = 8080
                    val port = fields(1)
                    assert(port.name == "port")
                    port.fieldType match
                        case Structure.Type.Primitive(_, tag) => assert(tag =:= Tag[Int])
                        case other                            => fail(s"Expected Primitive for port, got $other")
                    assert(port.default.isDefined)
                    assert(port.default.get == Structure.Value.Integer(8080L))

                    // ssl: Boolean = false
                    val ssl = fields(2)
                    assert(ssl.name == "ssl")
                    ssl.fieldType match
                        case Structure.Type.Primitive(_, tag) => assert(tag =:= Tag[Boolean])
                        case other                            => fail(s"Expected Primitive for ssl, got $other")
                    assert(ssl.default.isDefined)
                    assert(ssl.default.get == Structure.Value.Bool(false))
                case other => fail(s"Expected Product, got $other")
            end match
        }

        "sealed trait produces Sum with variants" in {
            val ref = Structure.of[MTShape]
            ref match
                case Structure.Type.Sum(name, tag, _, variants, _, _) =>
                    assert(name == "MTShape")
                    assert(tag =:= Tag[MTShape])
                    assert(variants.size == 2)
                    assert(variants(0).name == "MTCircle")
                    assert(variants(1).name == "MTRectangle")

                    // Check variant types are Products
                    variants(0).variantType match
                        case Structure.Type.Product(n, _, _, fields, _) =>
                            assert(n == "MTCircle")
                            assert(fields.size == 1)
                            assert(fields(0).name == "radius")
                        case other => fail(s"Expected Product for MTCircle, got $other")
                    end match

                    variants(1).variantType match
                        case Structure.Type.Product(n, _, _, fields, _) =>
                            assert(n == "MTRectangle")
                            assert(fields.size == 2)
                            assert(fields(0).name == "width")
                            assert(fields(1).name == "height")
                        case other => fail(s"Expected Product for MTRectangle, got $other")
                    end match
                case other => fail(s"Expected Sum, got $other")
            end match
        }

        "Option[A] produces Optional" in {
            val ref = Structure.of[MTOptional]
            ref match
                case Structure.Type.Product(_, _, _, fields, _) =>
                    assert(fields.size == 2)

                    val nicknameField = fields(1)
                    assert(nicknameField.name == "nickname")
                    assert(nicknameField.optional)
                    nicknameField.fieldType match
                        case Structure.Type.Optional(_, _, inner) =>
                            inner match
                                case Structure.Type.Primitive(_, tag) => assert(tag =:= Tag[String])
                                case other                            => fail(s"Expected Primitive inner, got $other")
                        case other => fail(s"Expected Optional, got $other")
                    end match
                case other => fail(s"Expected Product, got $other")
            end match
        }

        "Seq[A] produces Collection" in {
            val ref = Structure.of[MTOrder]
            ref match
                case Structure.Type.Product(_, _, _, fields, _) =>
                    val itemsField = fields(1)
                    assert(itemsField.name == "items")
                    itemsField.fieldType match
                        case Structure.Type.Collection(_, _, elem) =>
                            elem match
                                case Structure.Type.Product(n, _, _, _, _) => assert(n == "MTItem")
                                case other                                 => fail(s"Expected Product element, got $other")
                        case other => fail(s"Expected Collection, got $other")
                    end match
                case other => fail(s"Expected Product, got $other")
            end match
        }

        "Map[K, V] produces Mapping" in {
            val ref = Structure.of[Map[String, Int]]
            ref match
                case Structure.Type.Mapping(_, _, keyType, valueType) =>
                    keyType match
                        case Structure.Type.Primitive(_, tag) => assert(tag =:= Tag[String])
                        case other                            => fail(s"Expected Primitive key, got $other")
                    valueType match
                        case Structure.Type.Primitive(_, tag) => assert(tag =:= Tag[Int])
                        case other                            => fail(s"Expected Primitive value, got $other")
                case other => fail(s"Expected Mapping, got $other")
            end match
        }

        "nested case classes produce nested Product structures" in {
            val ref = Structure.of[MTSmallTeam]
            ref match
                case Structure.Type.Product(_, _, _, fields, _) =>
                    assert(fields.size == 2)

                    val leadField = fields(0)
                    assert(leadField.name == "lead")
                    leadField.fieldType match
                        case Structure.Type.Product(n, _, _, innerFields, _) =>
                            assert(n == "MTPerson")
                            assert(innerFields.size == 2)
                            assert(innerFields(0).name == "name")
                            assert(innerFields(1).name == "age")
                        case other => fail(s"Expected Product for lead, got $other")
                    end match

                    val sizeField = fields(1)
                    assert(sizeField.name == "size")
                    sizeField.fieldType match
                        case Structure.Type.Primitive(_, tag) => assert(tag =:= Tag[Int])
                        case other                            => fail(s"Expected Primitive for size, got $other")
                case other => fail(s"Expected Product, got $other")
            end match
        }

        "recursive types do not stack overflow" in {
            val ref = Structure.of[RTTree]
            ref match
                case Structure.Type.Product(name, _, _, fields, _) =>
                    assert(name == "RTTree")
                    assert(fields.size == 2)
                    assert(fields(0).name == "value")

                    val childrenField = fields(1)
                    assert(childrenField.name == "children")
                    childrenField.fieldType match
                        case Structure.Type.Collection(_, _, elem) =>
                            // The recursive reference should be a Product (possibly with empty fields)
                            elem match
                                case Structure.Type.Product(n, _, _, _, _) =>
                                    assert(n == "RTTree")
                                case other => fail(s"Expected Product for recursive element, got $other")
                        case other => fail(s"Expected Collection for children, got $other")
                    end match
                case other => fail(s"Expected Product, got $other")
            end match
        }

        "compatible returns true for structurally identical types" in {
            val personRef = Structure.of[MTPerson]
            val dtoRef    = Structure.of[RTPersonDTO]
            assert(Structure.Type.compatible(personRef, dtoRef))
        }

        "compatible returns false when field types differ" in {
            val personRef = Structure.of[MTPerson]
            val diffRef   = Structure.of[RTPersonDiff]
            assert(!Structure.Type.compatible(personRef, diffRef))
        }

        "fold visits all nodes depth-first" in {
            val ref = Structure.of[MTSmallTeam]
            val names = Structure.Type.fold(ref)(List.empty[String]) { (acc, tpe) =>
                acc :+ tpe.name
            }
            // MTSmallTeam (Product) -> lead: MTPerson (Product) -> name: String (Primitive) -> age: Int (Primitive) -> size: Int (Primitive)
            assert(names == List("MTSmallTeam", "MTPerson", "String", "Int", "Int"))
        }

        "derived generic sealed trait populates typeParams" in {
            // Regression guard: buildSumSchema must thread typeParamStructures
            // so that Structure.Type.Sum.typeParams is non-empty for generic sealed traits.
            val s = Schema[GenericSealed[Int]].structure
            s match
                case sum: Structure.Type.Sum =>
                    assert(sum.typeParams.size == 1, s"Expected 1 typeParam for GenericSealed[Int], got ${sum.typeParams.size}")
                case other => fail(s"Expected Sum, got $other")
            end match
        }

        "Structure.of[Unit] produces Primitive(PrimitiveKind.Unit, _)" in {
            val t = Structure.of[Unit]
            t match
                case Structure.Type.Primitive(kind, _) =>
                    assert(kind == Structure.PrimitiveKind.Unit)
                case _ =>
                    fail(s"expected Primitive(Unit), got $t")
            end match
        }

        "fieldPaths returns all leaf paths" in {
            val ref   = Structure.of[MTSmallTeam]
            val paths = Structure.Type.fieldPaths(ref)
            assert(paths == Chunk(Chunk("lead", "name"), Chunk("lead", "age"), Chunk("size")))
        }

        "Structure.of[A] delegates to Schema[A].structure" in {
            // Structure.of[MTPerson] calls summon[Schema[MTPerson]].structure.
            // MTPerson uses auto-derivation (inline given), so two summons yield
            // different Schema instances; use compatible (structural equality) not eq.
            val fromDirect = Structure.of[MTPerson]
            val fromSchema = Schema[MTPerson].structure

            assert(Structure.Type.compatible(fromDirect, fromSchema))

            (fromDirect, fromSchema) match
                case (Structure.Type.Product(n1, _, _, f1, _), Structure.Type.Product(n2, _, _, f2, _)) =>
                    assert(n1 == n2)
                    assert(f1.size == f2.size)
                    assert(f1.zip(f2).forall { (field1, field2) =>
                        field1.name == field2.name
                    })
                case other => fail(s"Expected Product pair, got $other")
            end match
        }

        "Structure.Field.doc is Maybe.empty by default" in {
            val ref = Structure.of[MTPerson]
            ref match
                case Structure.Type.Product(_, _, _, fields, _) =>
                    assert(fields.forall(_.doc.isEmpty))
                case other => fail(s"Expected Product, got $other")
            end match
        }

        "Structure.Field by-name construction does not force the structure thunk" in {
            val sentinel = new java.util.concurrent.atomic.AtomicInteger(0)
            // The by-name expression must appear directly at the call site (not through
            // a pre-assigned val) so the thunk captures the unevaluated block.
            val f = Structure.Field(
                "x",
                { sentinel.incrementAndGet(); Structure.Type.Primitive(Structure.PrimitiveKind.Int, Tag[Int].asInstanceOf[Tag[Any]]) },
                Maybe.empty,
                Maybe.empty,
                false
            )
            assert(sentinel.get() == 0, s"thunk was forced at construction time; sentinel=${sentinel.get()}")
            val ft = f.fieldType
            assert(sentinel.get() == 1, s"expected sentinel==1 after one fieldType read; got ${sentinel.get()}")
            ft match
                case Structure.Type.Primitive(kind, _) => assert(kind == Structure.PrimitiveKind.Int)
                case other                             => fail(s"Expected Primitive(Int), got $other")
            end match
        }

        "Structure.Field equality compares the forced fieldType" in {
            val ft = Structure.Type.Primitive(Structure.PrimitiveKind.Int, Tag[Int].asInstanceOf[Tag[Any]])
            val a  = Structure.Field("x", ft, Maybe("doc"), Maybe.empty, optional = false)
            val b  = Structure.Field("x", ft, Maybe("doc"), Maybe.empty, optional = false)
            // Two Fields built from identical data compare equal despite the by-name `apply`
            // wrapping each call's argument in a fresh `() => fieldType` lambda.
            assert(a == b, s"Field equality regressed: a=$a b=$b")
            assert(a.hashCode == b.hashCode, "hashCode must agree with equals")
            val ftOther = Structure.Type.Primitive(Structure.PrimitiveKind.String, Tag[String].asInstanceOf[Tag[Any]])
            val c       = Structure.Field("x", ftOther, Maybe("doc"), Maybe.empty, optional = false)
            assert(a != c, "Fields with different fieldType must not compare equal")
            val d = Structure.Field("y", ft, Maybe("doc"), Maybe.empty, optional = false)
            assert(a != d, "Fields with different name must not compare equal")
            val e = Structure.Field("x", ft, Maybe.empty, Maybe.empty, optional = false)
            assert(a != e, "Fields with different doc must not compare equal")
            val f = Structure.Field("x", ft, Maybe("doc"), Maybe.empty, optional = true)
            assert(a != f, "Fields with different optional must not compare equal")
        }

        "Structure.Field roundtrips via Json with public field names" in {
            val ft      = Structure.Type.Primitive(Structure.PrimitiveKind.String, Tag[String].asInstanceOf[Tag[Any]])
            val f       = Structure.Field("x", ft, Maybe("hi"), Maybe.empty, optional = true)
            val encoded = Json.encode(f)
            assert(encoded.contains("\"name\":\"x\""), s"encoded did not contain name:x; got $encoded")
            assert(encoded.contains("\"fieldType\":"), s"encoded did not contain fieldType; got $encoded")
            assert(encoded.contains("\"doc\":\"hi\""), s"encoded did not contain doc:hi; got $encoded")
            assert(encoded.contains("\"optional\":true"), s"encoded did not contain optional:true; got $encoded")
            assert(!encoded.contains("\"_fieldType\""), s"encoded contained private _fieldType; got $encoded")
            val decoded = Json.decode[Structure.Field](encoded)
            decoded match
                case Result.Success(f2) =>
                    assert(f2.name == "x")
                    assert(Structure.Type.compatible(f2.fieldType, ft))
                    assert(f2.doc == Maybe("hi"))
                    assert(f2.default == Maybe.empty)
                    assert(f2.optional == true)
                case other => fail(s"Expected Success, got $other")
            end match
        }

        "List[Int] produces Collection" in {
            val tpe = Structure.of[List[Int]]
            tpe match
                case Structure.Type.Collection(_, _, elem) =>
                    elem match
                        case Structure.Type.Primitive(kind, _) => assert(kind == Structure.PrimitiveKind.Int)
                        case other                             => fail(s"Expected Primitive element, got $other")
                case other =>
                    fail(s"Expected Collection, got $other")
            end match
        }

        "Seq[String] produces Collection" in {
            val tpe = Structure.of[Seq[String]]
            tpe match
                case Structure.Type.Collection(_, _, elem) =>
                    elem match
                        case Structure.Type.Primitive(kind, _) => assert(kind == Structure.PrimitiveKind.String)
                        case other                             => fail(s"Expected Primitive element, got $other")
                case other =>
                    fail(s"Expected Collection, got $other")
            end match
        }

        "compatible returns false for different structures" in {
            val a = Structure.of[MTPerson]
            val c = Structure.of[MTAddress]
            // MTPerson (2 fields) vs MTAddress (3 fields)
            assert(!Structure.Type.compatible(a, c))
        }

        "field defaults are captured" in {
            val tpe = Structure.of[MTAllDefaults]
            tpe match
                case Structure.Type.Product(_, _, _, fields, _) =>
                    assert(fields.size == 3)

                    // a: Int = 1
                    val aField = fields(0)
                    assert(aField.name == "a")
                    assert(aField.default.nonEmpty)
                    assert(aField.default.get == Structure.Value.Integer(1L))

                    // b: String = "hello"
                    val bField = fields(1)
                    assert(bField.name == "b")
                    assert(bField.default.nonEmpty)
                    assert(bField.default.get == Structure.Value.Str("hello"))

                    // c: Boolean = false
                    val cField = fields(2)
                    assert(cField.name == "c")
                    assert(cField.default.nonEmpty)
                    assert(cField.default.get == Structure.Value.Bool(false))

                case other =>
                    fail(s"Expected Product, got $other")
            end match
        }
    }

    // ==================== Structure.Value ====================

    "Structure.Value" - {

        "Structure.Value round-trip" - {

            "person toStructure" in {
                val schema = summon[Schema[MTPerson]]
                val person = MTPerson("Alice", 30)
                val dv     = toStructure(schema, person)
                dv match
                    case Structure.Value.Record(fields) =>
                        assert(fields.size == 2)
                        val fieldMap = fields.toMap
                        assert(fieldMap("name") == Structure.Value.primitive("Alice"))
                        assert(fieldMap("age") == Structure.Value.primitive(30))
                    case other => fail(s"Expected Record, got $other")
                end match
            }

            "person fromStructure" in {
                val schema = summon[Schema[MTPerson]]
                val person = MTPerson("Alice", 30)
                val dv     = toStructure(schema, person)
                val result = fromStructure(schema, dv)
                assert(result == person)
            }

            "team toStructure" in {
                val schema = summon[Schema[MTSmallTeam]]
                val team   = MTSmallTeam(MTPerson("Alice", 30), 5)
                val dv     = toStructure(schema, team)
                dv match
                    case Structure.Value.Record(fields) =>
                        assert(fields.size == 2)
                        val fieldMap = fields.toMap
                        // lead should be a nested Record
                        fieldMap("lead") match
                            case Structure.Value.Record(leadFields) =>
                                val lm = leadFields.toMap
                                assert(lm("name") == Structure.Value.primitive("Alice"))
                                assert(lm("age") == Structure.Value.primitive(30))
                            case other => fail(s"Expected Record for lead, got $other")
                        end match
                        assert(fieldMap("size") == Structure.Value.primitive(5))
                    case other => fail(s"Expected Record, got $other")
                end match
            }

            "team fromStructure" in {
                val schema = summon[Schema[MTSmallTeam]]
                val team   = MTSmallTeam(MTPerson("Alice", 30), 5)
                val dv     = toStructure(schema, team)
                val result = fromStructure(schema, dv)
                assert(result == team)
            }

            "list toStructure" in {
                val schema = summon[Schema[List[MTPerson]]]
                val list   = List(MTPerson("Alice", 30), MTPerson("Bob", 25))
                val dv     = toStructure(schema, list)
                dv match
                    case Structure.Value.Sequence(elements) =>
                        assert(elements.size == 2)
                    case other => fail(s"Expected Sequence, got $other")
                end match
            }

            "list fromStructure" in {
                val schema = summon[Schema[List[MTPerson]]]
                val list   = List(MTPerson("Alice", 30), MTPerson("Bob", 25))
                val dv     = toStructure(schema, list)
                val result = fromStructure(schema, dv)
                assert(result == list)
            }

            "option some toStructure" in {
                // Option uses nil-based encoding: Some(x) → x
                val schema              = summon[Schema[Option[String]]]
                val opt: Option[String] = Some("hello")
                val dv                  = toStructure(schema, opt)
                assert(dv == Structure.Value.primitive("hello"))
            }

            "option none toStructure" in {
                // Option uses nil-based encoding: None → Null
                val schema              = summon[Schema[Option[String]]]
                val opt: Option[String] = None
                val dv                  = toStructure(schema, opt)
                assert(dv == Structure.Value.Null)
            }

            "option fromStructure round-trip" in {
                val schema               = summon[Schema[Option[String]]]
                val some: Option[String] = Some("hello")
                val none: Option[String] = None
                assert(fromStructure(schema, toStructure(schema, some)) == some)
                assert(fromStructure(schema, toStructure(schema, none)) == none)
            }

            "sealed trait toStructure" in {
                val schema: Schema[MTShape] = summon[Schema[MTShape]]
                val circle: MTShape         = MTCircle(5.0)
                val dv                      = toStructure(schema, circle)
                dv match
                    case Structure.Value.Record(fields) =>
                        assert(fields.size == 1)
                        val (variantName, variantValue) = fields.head
                        assert(variantName == "MTCircle")
                        variantValue match
                            case Structure.Value.Record(innerFields) =>
                                val fm = innerFields.toMap
                                assert(fm("radius") == Structure.Value.primitive(5.0))
                            case other => fail(s"Expected Record for variant, got $other")
                        end match
                    case other => fail(s"Expected Record wrapper, got $other")
                end match
            }

            "sealed trait fromStructure round-trip" in {
                val schema: Schema[MTShape] = summon[Schema[MTShape]]
                val circle: MTShape         = MTCircle(5.0)
                val rect: MTShape           = MTRectangle(3.0, 4.0)
                assert(fromStructure(schema, toStructure(schema, circle)) == circle)
                assert(fromStructure(schema, toStructure(schema, rect)) == rect)
            }

            "recursive toStructure" in {
                val schema = summon[Schema[CodecTree]]
                val tree   = CodecTree(1, List(CodecTree(2, scala.Nil), CodecTree(3, scala.Nil)))
                val dv     = toStructure(schema, tree)
                dv match
                    case Structure.Value.Record(fields) =>
                        val fm = fields.toMap
                        assert(fm("value") == Structure.Value.primitive(1))
                        fm("children") match
                            case Structure.Value.Sequence(elements) =>
                                assert(elements.size == 2)
                            case other => fail(s"Expected Sequence for children, got $other")
                        end match
                    case other => fail(s"Expected Record, got $other")
                end match
            }

            "recursive fromStructure round-trip" in {
                val schema = summon[Schema[CodecTree]]
                val tree   = CodecTree(1, List(CodecTree(2, scala.Nil), CodecTree(3, scala.Nil)))
                assert(fromStructure(schema, toStructure(schema, tree)) == tree)
            }
        }

        "Structure.Value JSON round-trip" - {

            "dynamic to json round-trip" in {
                val schema = summon[Schema[MTPerson]]
                val person = MTPerson("Alice", 30)

                // person -> Structure.Value -> JSON string -> person -> Structure.Value -> compare
                val dv1     = toStructure(schema, person)
                val json    = Json.encode(person)
                val decoded = Json.decode[MTPerson](json)
                assert(decoded == Result.succeed(person))
                val dv2 = toStructure(schema, decoded.getOrThrow)
                assert(dv1 == dv2)
            }

            "json parse to dynamic" in {
                val schema  = summon[Schema[MTPerson]]
                val json    = """{"name":"Bob","age":25}"""
                val decoded = Json.decode[MTPerson](json)
                assert(decoded == Result.succeed(MTPerson("Bob", 25)))
                val dv = toStructure(schema, decoded.getOrThrow)
                dv match
                    case Structure.Value.Record(fields) =>
                        val fm = fields.toMap
                        assert(fm("name") == Structure.Value.primitive("Bob"))
                        assert(fm("age") == Structure.Value.primitive(25))
                    case other => fail(s"Expected Record, got $other")
                end match
            }
        }

        "Structure.Value.primitive" - {

            "string primitive" in {
                val v = Structure.Value.primitive("hello")
                v match
                    case Structure.Value.Str(value) =>
                        assert(value == "hello")
                    case other => fail(s"Expected Str, got $other")
                end match
            }

            "int primitive" in {
                val v = Structure.Value.primitive(42)
                v match
                    case Structure.Value.Integer(value) =>
                        assert(value == 42L)
                    case other => fail(s"Expected Integer, got $other")
                end match
            }

            "double primitive" in {
                val v = Structure.Value.primitive(3.14)
                v match
                    case Structure.Value.Decimal(value) =>
                        assert(value == 3.14)
                    case other => fail(s"Expected Decimal, got $other")
                end match
            }

            "boolean primitive" in {
                val v = Structure.Value.primitive(true)
                v match
                    case Structure.Value.Bool(value) =>
                        assert(value == true)
                    case other => fail(s"Expected Bool, got $other")
                end match
            }

            "bytes primitive" in {
                val bytes = Span.from(Array[Byte](1, 2, 3))
                val v     = Structure.Value.primitive(bytes)
                v match
                    case Structure.Value.Bytes(value) =>
                        assert(value.toArray.toSeq == bytes.toArray.toSeq)
                    case other => fail(s"Expected Bytes, got $other")
                end match
            }

            "instant primitive" in {
                val instant = java.time.Instant.parse("2026-07-09T12:34:56Z")
                val v       = Structure.Value.primitive(instant)
                v match
                    case Structure.Value.Instant(value) =>
                        assert(value == instant)
                    case other => fail(s"Expected Instant, got $other")
                end match
            }

            "duration primitive" in {
                val duration = java.time.Duration.ofSeconds(5, 123)
                val v        = Structure.Value.primitive(duration)
                v match
                    case Structure.Value.Duration(value) =>
                        assert(value == duration)
                    case other => fail(s"Expected Duration, got $other")
                end match
            }
        }

        "Schema.toStructureValue" - {

            "person toStructureValue" in {
                val schema = Schema[MTPerson]
                val person = MTPerson("Alice", 30)
                val dv     = schema.toStructureValue(person)
                dv match
                    case Structure.Value.Record(fields) =>
                        assert(fields.size == 2)
                        val fieldMap = fields.toMap
                        assert(fieldMap("name") == Structure.Value.primitive("Alice"))
                        assert(fieldMap("age") == Structure.Value.primitive(30))
                    case other => fail(s"Expected Record, got $other")
                end match
            }

            "nested case class toStructureValue" in {
                val schema = Schema[MTSmallTeam]
                val team   = MTSmallTeam(MTPerson("Alice", 30), 5)
                val dv     = schema.toStructureValue(team)
                dv match
                    case Structure.Value.Record(fields) =>
                        assert(fields.size == 2)
                        val fieldMap = fields.toMap
                        fieldMap("lead") match
                            case Structure.Value.Record(leadFields) =>
                                val lm = leadFields.toMap
                                assert(lm("name") == Structure.Value.primitive("Alice"))
                                assert(lm("age") == Structure.Value.primitive(30))
                            case other => fail(s"Expected Record for lead, got $other")
                        end match
                        assert(fieldMap("size") == Structure.Value.primitive(5))
                    case other => fail(s"Expected Record, got $other")
                end match
            }

            "toStructureValue consistency" in {
                val schema1 = Schema[MTPerson]
                val schema2 = summon[Schema[MTPerson]]
                val person  = MTPerson("Bob", 25)
                val from1   = schema1.toStructureValue(person)
                val from2   = schema2.toStructureValue(person)
                assert(from1 == from2)
            }

            "bytes toStructureValue" in {
                val bytes = Span.from(Array[Byte](4, 5, 6))
                val dv    = summon[Schema[Span[Byte]]].toStructureValue(bytes)
                dv match
                    case Structure.Value.Bytes(value) =>
                        assert(value.toArray.toSeq == bytes.toArray.toSeq)
                    case other => fail(s"Expected Bytes, got $other")
                end match
            }

            "instant toStructureValue" in {
                val instant = java.time.Instant.parse("2026-07-09T12:34:56Z")
                val dv      = summon[Schema[java.time.Instant]].toStructureValue(instant)
                assert(dv == Structure.Value.Instant(instant))
            }

            "duration toStructureValue" in {
                val duration = java.time.Duration.ofSeconds(5, 123)
                val dv       = summon[Schema[java.time.Duration]].toStructureValue(duration)
                assert(dv == Structure.Value.Duration(duration))
            }
        }

        "Structure.Value equality" - {

            "Null equals Null" in {
                assert(Structure.Value.Null == Structure.Value.Null)
            }

            "primitive equality" in {
                assert(Structure.Value.primitive(42) == Structure.Value.primitive(42))
                assert(Structure.Value.primitive(42) != Structure.Value.primitive(43))
                assert(Structure.Value.primitive("a") != Structure.Value.primitive(1))
            }

            "decimal equality preserves Double semantics" in {
                val zero     = Structure.Value.Decimal(0.0)
                val negative = Structure.Value.Decimal(-0.0)

                assert(zero == negative)
                assert(zero.hashCode == negative.hashCode)
                assert(Structure.Value.Decimal(Double.NaN) != Structure.Value.Decimal(Double.NaN))
            }

            "record equality" in {
                val r1 = Structure.Value.Record(Chunk(("x", Structure.Value.primitive(1))))
                val r2 = Structure.Value.Record(Chunk(("x", Structure.Value.primitive(1))))
                val r3 = Structure.Value.Record(Chunk(("x", Structure.Value.primitive(2))))
                assert(r1 == r2)
                assert(r1 != r3)
            }

            "equals/hashCode are reflexive and mutually consistent across every case" in {
                // Structure.Value overrides equals/hashCode with a hand-rolled ordinal dispatch rather
                // than a shape match because recursive extractor matching broke Scala.js equality for
                // Bytes(Span[Byte]). An ordinal dispatch has no compiler-enforced exhaustiveness, so this
                // sweep is the guard instead: `expectedCases` is derived from the compiler-synthesized
                // Mirror for the enum, so it grows automatically when a case is added, and the assertion
                // below fails the moment `samples` falls behind, catching an unguarded new case before it ships.
                import scala.compiletime.constValue
                import scala.deriving.Mirror

                val mirror        = summon[Mirror.SumOf[Structure.Value]]
                val expectedCases = constValue[Tuple.Size[mirror.MirroredElemTypes]]

                val samples: List[Structure.Value] = List(
                    Structure.Value.Record(Chunk(("x", Structure.Value.Null))),
                    Structure.Value.VariantCase("V", Structure.Value.Null),
                    Structure.Value.Sequence(Chunk(Structure.Value.Null)),
                    Structure.Value.MapEntries(Chunk((Structure.Value.Null, Structure.Value.Null))),
                    Structure.Value.Str("s"),
                    Structure.Value.Bool(true),
                    Structure.Value.Integer(1L),
                    Structure.Value.Decimal(1.5),
                    Structure.Value.BigNum(BigDecimal("1.5")),
                    Structure.Value.Bytes(Span.from(Array[Byte](1, 2, 3))),
                    Structure.Value.Instant(java.time.Instant.parse("2024-01-01T00:00:00Z")),
                    Structure.Value.Duration(java.time.Duration.ofSeconds(5)),
                    Structure.Value.Null
                )

                assert(
                    samples.size == expectedCases,
                    s"Structure.Value has $expectedCases cases but this sweep covers only ${samples.size}; " +
                        "add a sample for the new case so its equals/hashCode contract stays guarded"
                )

                samples.foreach { value =>
                    assert(value == value, s"reflexivity broken for $value")
                    assert(value.hashCode == value.hashCode, s"hashCode not stable across calls for $value")
                }

                samples.foreach { value =>
                    val others = samples.filterNot(_ == value)
                    assert(others.forall(_ != value), s"$value compared equal to a value of a different case")
                }
            }
        }
    }

    // ==================== Structure.Path ====================

    "Structure.Path" - {

        val personDv = Structure.Value.Record(Chunk(
            ("name", Structure.Value.primitive("Alice")),
            ("age", Structure.Value.primitive(30))
        ))

        val teamDv = Structure.Value.Record(Chunk(
            ("lead", personDv),
            ("size", Structure.Value.primitive(5))
        ))

        val listDv = Structure.Value.Sequence(Chunk(
            Structure.Value.primitive(10),
            Structure.Value.primitive(20),
            Structure.Value.primitive(30)
        ))

        "path field get" in {
            val path   = Structure.Path.field("name")
            val result = path.get(personDv)
            assert(result == Result.succeed(Chunk(Structure.Value.primitive("Alice"))))
        }

        "path nested get" in {
            val path   = Structure.Path.field("lead") / Structure.PathSegment.Field("name")
            val result = path.get(teamDv)
            assert(result == Result.succeed(Chunk(Structure.Value.primitive("Alice"))))
        }

        "path index get" in {
            val path   = Structure.Path.root / Structure.PathSegment.Index(1)
            val result = path.get(listDv)
            assert(result == Result.succeed(Chunk(Structure.Value.primitive(20))))
        }

        "path each get" in {
            val path   = Structure.Path.each
            val result = path.get(listDv)
            result match
                case Result.Success(values) =>
                    assert(values.size == 3)
                    assert(values(0) == Structure.Value.primitive(10))
                    assert(values(1) == Structure.Value.primitive(20))
                    assert(values(2) == Structure.Value.primitive(30))
                case other => fail(s"Expected Success, got $other")
            end match
        }

        "path field set" in {
            val path     = Structure.Path.field("name")
            val newValue = Structure.Value.primitive("Bob")
            val result   = path.set(personDv, newValue)
            result match
                case Result.Success(updated) =>
                    updated match
                        case Structure.Value.Record(fields) =>
                            val fm = fields.toMap
                            assert(fm("name") == newValue)
                            assert(fm("age") == Structure.Value.primitive(30))
                        case other => fail(s"Expected Record, got $other")
                case other => fail(s"Expected Success, got $other")
            end match
        }

        "path nested set" in {
            val path     = Structure.Path.field("lead") / Structure.PathSegment.Field("name")
            val newValue = Structure.Value.primitive("Bob")
            val result   = path.set(teamDv, newValue)
            result match
                case Result.Success(updated) =>
                    updated match
                        case Structure.Value.Record(fields) =>
                            val fm = fields.toMap
                            fm("lead") match
                                case Structure.Value.Record(leadFields) =>
                                    val lm = leadFields.toMap
                                    assert(lm("name") == newValue)
                                    assert(lm("age") == Structure.Value.primitive(30))
                                case other => fail(s"Expected Record for lead, got $other")
                            end match
                        case other => fail(s"Expected Record, got $other")
                case other => fail(s"Expected Success, got $other")
            end match
        }

        "path missing field" in {
            val path   = Structure.Path.field("nonexistent")
            val result = path.get(personDv)
            assert(result.isPanic)
        }

        "path toString" in {
            val path = Structure.Path.field("lead") / Structure.PathSegment.Field("name")
            assert(path.toString == ".lead.name")

            val pathWithIndex = Structure.Path.field("items") / Structure.PathSegment.Index(2)
            assert(pathWithIndex.toString == ".items[2]")

            val pathWithEach = Structure.Path.field("items") / Structure.PathSegment.Each
            assert(pathWithEach.toString == ".items[*]")

            val pathWithVariant = Structure.Path.root / Structure.PathSegment.Variant("Circle")
            assert(pathWithVariant.toString == "<Circle>")
        }

        "Structure.Path variant" - {

            "variant get" in {
                val variantDv = Structure.Value.VariantCase(
                    "MTCircle",
                    Structure.Value.Record(Chunk(
                        ("radius", Structure.Value.primitive(5.0))
                    ))
                )
                val path   = Structure.Path.variant("MTCircle")
                val result = path.get(variantDv)
                result match
                    case Result.Success(values) =>
                        assert(values.size == 1)
                        values.head match
                            case Structure.Value.Record(fields) =>
                                val fm = fields.toMap
                                assert(fm("radius") == Structure.Value.primitive(5.0))
                            case other => fail(s"Expected Record, got $other")
                        end match
                    case other => fail(s"Expected Success, got $other")
                end match
            }

            "variant non-matching get" in {
                val variantDv = Structure.Value.VariantCase(
                    "MTCircle",
                    Structure.Value.Record(Chunk(
                        ("radius", Structure.Value.primitive(5.0))
                    ))
                )
                val path   = Structure.Path.variant("MTRectangle")
                val result = path.get(variantDv)
                assert(result.isPanic)
            }

            "variant set" in {
                val variantDv = Structure.Value.VariantCase(
                    "MTCircle",
                    Structure.Value.Record(Chunk(
                        ("radius", Structure.Value.primitive(5.0))
                    ))
                )
                val path     = Structure.Path.variant("MTCircle") / Structure.PathSegment.Field("radius")
                val newValue = Structure.Value.primitive(10.0)
                val result   = path.set(variantDv, newValue)
                result match
                    case Result.Success(updated) =>
                        updated match
                            case Structure.Value.VariantCase(name, inner) =>
                                assert(name == "MTCircle")
                                inner match
                                    case Structure.Value.Record(fields) =>
                                        val fm = fields.toMap
                                        assert(fm("radius") == newValue)
                                    case other => fail(s"Expected Record, got $other")
                                end match
                            case other => fail(s"Expected VariantCase, got $other")
                    case other => fail(s"Expected Success, got $other")
                end match
            }
        }

        "Structure.Path.each set" - {

            "set all elements" in {
                val listDv = Structure.Value.Sequence(Chunk(
                    Structure.Value.Record(Chunk(("x", Structure.Value.primitive(1)))),
                    Structure.Value.Record(Chunk(("x", Structure.Value.primitive(2)))),
                    Structure.Value.Record(Chunk(("x", Structure.Value.primitive(3))))
                ))
                val path     = Structure.Path.each / Structure.PathSegment.Field("x")
                val newValue = Structure.Value.primitive(99)
                val result   = path.set(listDv, newValue)
                result match
                    case Result.Success(updated) =>
                        updated match
                            case Structure.Value.Sequence(elements) =>
                                assert(elements.size == 3)
                                assert(elements.forall {
                                    case Structure.Value.Record(fields) =>
                                        fields.toMap.get("x").contains(newValue)
                                    case _ => false
                                })
                            case other => fail(s"Expected Sequence, got $other")
                    case other => fail(s"Expected Success, got $other")
                end match
            }
        }

        "Structure.Path index set" - {

            "set specific index" in {
                val listDv = Structure.Value.Sequence(Chunk(
                    Structure.Value.primitive(10),
                    Structure.Value.primitive(20),
                    Structure.Value.primitive(30)
                ))
                val path     = Structure.Path.index(1)
                val newValue = Structure.Value.primitive(99)
                val result   = path.set(listDv, newValue)
                result match
                    case Result.Success(updated) =>
                        updated match
                            case Structure.Value.Sequence(elements) =>
                                assert(elements.size == 3)
                                assert(elements(0) == Structure.Value.primitive(10))
                                assert(elements(1) == Structure.Value.primitive(99))
                                assert(elements(2) == Structure.Value.primitive(30))
                            case other => fail(s"Expected Sequence, got $other")
                    case other => fail(s"Expected Success, got $other")
                end match
            }

            "index out of bounds" in {
                val listDv = Structure.Value.Sequence(Chunk(
                    Structure.Value.primitive(10)
                ))
                val path   = Structure.Path.index(5)
                val result = path.set(listDv, Structure.Value.primitive(99))
                assert(result.isPanic)
            }
        }
    }

    // ==================== error handling ====================

    "error handling" - {

        "json bytes round-trip" in {
            val data = Span.from(Array[Byte](1, 2, 3, 4, 5))
            val w    = JsonWriter()
            w.bytes(data)
            val json = w.resultString
            val r    = JsonReader(json)
            val got  = r.bytes()
            assert(got.toArray.toSeq == data.toArray.toSeq)
        }

        "json bigInt round-trip" in {
            val value = BigInt("123456789012345678901234567890")
            val w     = JsonWriter()
            w.bigInt(value)
            val json = w.resultString
            val r    = JsonReader(json)
            assert(r.bigInt() == value)
        }

        "json bigDecimal round-trip" in {
            val value = BigDecimal("123456789.012345678901234567890")
            val w     = JsonWriter()
            w.bigDecimal(value)
            val json = w.resultString
            val r    = JsonReader(json)
            assert(r.bigDecimal() == value)
        }

        "json instant round-trip" in {
            val value = java.time.Instant.parse("2024-06-15T10:30:00Z")
            val w     = JsonWriter()
            w.instant(value)
            val json = w.resultString
            val r    = JsonReader(json)
            assert(r.instant() == value)
        }

        "json duration round-trip" in {
            val value = java.time.Duration.ofHours(2).plusMinutes(30)
            val w     = JsonWriter()
            w.duration(value)
            val json = w.resultString
            val r    = JsonReader(json)
            assert(r.duration() == value)
        }

        "json mapStart/mapEnd" in {
            val w = JsonWriter()
            w.mapStart(2)
            w.field("key1", 0)
            w.string("value1")
            w.field("key2", 1)
            w.int(42)
            w.mapEnd()
            val json = w.resultString
            assert(json == """{"key1":"value1","key2":42}""")
        }

        "json hasNextEntry" in {
            val json = """{"a":"x","b":"y"}"""
            val r    = JsonReader(json)
            val _    = r.mapStart()
            var keys = List.empty[String]
            while r.hasNextEntry() do
                keys = keys :+ r.field()
                val _ = r.string()
            r.mapEnd()
            assert(keys == List("a", "b"))
        }

        "protobuf bytes round-trip" in {
            val data = Span.from(Array[Byte](10, 20, 30, 40, 50))
            val w    = new ProtobufWriter
            w.field("data", 0)
            w.bytes(data)
            val r   = new ProtobufReader(w.resultBytes)
            val _   = r.field()
            val got = r.bytes()
            assert(got.toArray.toSeq == data.toArray.toSeq)
        }

        "protobuf bigInt round-trip" in {
            val value = BigInt("999999999999999999999999999")
            val w     = new ProtobufWriter
            w.field("value", 0)
            w.bigInt(value)
            val r = new ProtobufReader(w.resultBytes)
            val _ = r.field()
            assert(r.bigInt() == value)
        }

        "protobuf bigDecimal round-trip" in {
            val value = BigDecimal("3.14159265358979323846")
            val w     = new ProtobufWriter
            w.field("value", 0)
            w.bigDecimal(value)
            val r = new ProtobufReader(w.resultBytes)
            val _ = r.field()
            assert(r.bigDecimal() == value)
        }

        "protobuf instant round-trip" in {
            val value = java.time.Instant.parse("2024-01-01T00:00:00Z")
            val w     = new ProtobufWriter
            w.field("value", 0)
            w.instant(value)
            val r = new ProtobufReader(w.resultBytes)
            val _ = r.field()
            assert(r.instant() == value)
        }

        "protobuf duration round-trip" in {
            val value = java.time.Duration.ofSeconds(3661)
            val w     = new ProtobufWriter
            w.field("value", 0)
            w.duration(value)
            val r = new ProtobufReader(w.resultBytes)
            val _ = r.field()
            assert(r.duration() == value)
        }

        "protobuf mapStart/mapEnd" in {
            // A map is a repeated MapEntry message under its field number (proto3 map<K, V>), so the
            // map must sit under a field. Each entry holds the key at field 1 and the value at field 2.
            val w = new ProtobufWriter
            w.field("m", 5)
            w.mapStart(2)
            w.field("key1", 0)
            w.string("value1")
            w.field("key2", 1)
            w.int(42)
            w.mapEnd()
            val bytes = w.resultBytes
            assert(bytes.nonEmpty)
            // Read it back: the enclosing field() consumes the first entry's tag, then the map drives entries.
            val r = new ProtobufReader(bytes)
            val _ = r.field()
            val _ = r.mapStart()
            assert(r.hasNextEntry())
            assert(r.field() == "key1")
            assert(r.string() == "value1")
            assert(r.hasNextEntry())
            assert(r.field() == "key2")
            assert(r.int() == 42)
            assert(!r.hasNextEntry())
            r.mapEnd()
            ()
        }

        "protobuf hasNextEntry" in {
            val w = new ProtobufWriter
            w.field("a", 0)
            w.string("x")
            w.field("b", 1)
            w.string("y")
            val r     = new ProtobufReader(w.resultBytes)
            var count = 0
            while r.hasNextEntry() do
                val _ = r.field()
                val _ = r.string()
                count += 1
            end while
            assert(count == 2)
        }

        "dynamic bytes round-trip" in {
            val data = Span.from(Array[Byte](7, 8, 9))
            val w    = new StructureValueWriter
            w.bytes(data)
            val dv = w.getResult
            dv match
                case Structure.Value.Bytes(value) => assert(value.toArray.toSeq == data.toArray.toSeq)
                case other                        => fail(s"Expected Bytes, got $other")
            val r   = new StructureValueReader(dv)
            val got = r.bytes()
            assert(got.toArray.toSeq == data.toArray.toSeq)
        }

        "legacy string bytes decode" in {
            val data    = Span.from(Array[Byte](7, 8, 9))
            val encoded = java.util.Base64.getEncoder.encodeToString(data.toArray)
            val r       = new StructureValueReader(Structure.Value.Str(encoded))
            val got     = r.bytes()
            assert(got.toArray.toSeq == data.toArray.toSeq)
        }

        "base64-shaped string remains string" in {
            val encoded = "AQID"
            val got     = fromStructure(summon[Schema[String]], Structure.Value.Str(encoded))
            assert(got == encoded)
        }

        "dynamic bigInt round-trip" in {
            val value = BigInt("42")
            val w     = new StructureValueWriter
            w.bigInt(value)
            val dv = w.getResult
            val r  = new StructureValueReader(dv)
            assert(r.bigInt() == value)
        }

        "dynamic bigDecimal round-trip" in {
            val value = BigDecimal("1.618033988749895")
            val w     = new StructureValueWriter
            w.bigDecimal(value)
            val dv = w.getResult
            val r  = new StructureValueReader(dv)
            assert(r.bigDecimal() == value)
        }

        "dynamic instant round-trip" in {
            val value = java.time.Instant.parse("2025-12-31T23:59:59Z")
            val w     = new StructureValueWriter
            w.instant(value)
            val dv = w.getResult
            assert(dv == Structure.Value.Instant(value))
            val r = new StructureValueReader(dv)
            assert(r.instant() == value)
        }

        "legacy string instant decode" in {
            val value = java.time.Instant.parse("2025-12-31T23:59:59Z")
            val r     = new StructureValueReader(Structure.Value.Str(value.toString))
            assert(r.instant() == value)
        }

        "dynamic duration round-trip" in {
            val value = java.time.Duration.ofMinutes(90)
            val w     = new StructureValueWriter
            w.duration(value)
            val dv = w.getResult
            assert(dv == Structure.Value.Duration(value))
            val r = new StructureValueReader(dv)
            assert(r.duration() == value)
        }

        "legacy string duration decode" in {
            val value = java.time.Duration.ofMinutes(90)
            val r     = new StructureValueReader(Structure.Value.Str(value.toString))
            assert(r.duration() == value)
        }

        "dynamic map round-trip" in {
            val w = new StructureValueWriter
            w.mapStart(2)
            w.field("alpha", 0)
            w.string("hello")
            w.field("beta", 1)
            w.int(99)
            w.mapEnd()
            val dv = w.getResult
            val r  = new StructureValueReader(dv)
            val _  = r.mapStart()
            assert(r.hasNextEntry())
            assert(r.field() == "alpha")
            assert(r.string() == "hello")
            assert(r.hasNextEntry())
            assert(r.field() == "beta")
            assert(r.int() == 99)
            assert(!r.hasNextEntry())
            r.mapEnd()
            ()
        }

        "StructureValueReader.char rejects multi-character strings" in {
            // Regression guard: char() must not silently truncate a multi-character string to its first char.
            // The strict-on-text-input symmetry argument (matching `string()` rejecting Integer-as-String) applies.
            val dv = Structure.Value.Str("ab")
            val r  = new StructureValueReader(dv)
            intercept[TypeMismatchException](r.char())
            ()
        }

        "StructureValueReader.char rejects empty strings" in {
            val dv = Structure.Value.Str("")
            val r  = new StructureValueReader(dv)
            intercept[TypeMismatchException](r.char())
            ()
        }

        "StructureValueReader.char accepts a single-character string" in {
            val dv = Structure.Value.Str("x")
            val r  = new StructureValueReader(dv)
            assert(r.char() == 'x')
        }

        "json int parse error throws ParseException" in {
            val r  = JsonReader("3.14")
            val ex = intercept[ParseException](r.int())
            discard(summon[ParseException <:< DecodeException])
            assert(ex.getMessage.contains("Cannot parse"))
        }

        "json long parse error throws ParseException" in {
            val r = JsonReader("\"abc\"")
            // readNumber will fail because "abc" starts with '"', not a digit
            val ex = intercept[ParseException](r.long())
            discard(summon[ParseException <:< DecodeException])
            assert(ex.getMessage.contains("Cannot parse"))
        }

        "json short overflow throws ParseException" in {
            val r  = JsonReader("99999")
            val ex = intercept[ParseException](r.short())
            discard(summon[ParseException <:< DecodeException])
            assert(ex.getMessage.contains("Cannot parse"))
        }

        "json byte overflow throws ParseException" in {
            val r  = JsonReader("999")
            val ex = intercept[ParseException](r.byte())
            discard(summon[ParseException <:< DecodeException])
            assert(ex.getMessage.contains("Cannot parse"))
        }

        "json invalid base64 throws ParseException" in {
            val r  = JsonReader("\"not-valid-base64!!!\"")
            val ex = intercept[ParseException](r.bytes())
            discard(summon[ParseException <:< DecodeException])
            assert(ex.getMessage.contains("Base64"))
        }

        "json invalid bigInt throws ParseException" in {
            val r  = JsonReader("\"not_a_number\"")
            val ex = intercept[ParseException](r.bigInt())
            discard(summon[ParseException <:< DecodeException])
            assert(ex.getMessage.contains("BigInt"))
        }

        "json invalid bigDecimal throws ParseException" in {
            val r  = JsonReader("\"not_a_number\"")
            val ex = intercept[ParseException](r.bigDecimal())
            discard(summon[ParseException <:< DecodeException])
            assert(ex.getMessage.contains("BigDecimal"))
        }

        "json invalid instant throws ParseException" in {
            val r  = JsonReader("\"not-a-date\"")
            val ex = intercept[ParseException](r.instant())
            discard(summon[ParseException <:< DecodeException])
            assert(ex.getMessage.contains("Instant"))
        }

        "json invalid duration throws ParseException" in {
            val r  = JsonReader("\"not-a-duration\"")
            val ex = intercept[ParseException](r.duration())
            discard(summon[ParseException <:< DecodeException])
            assert(ex.getMessage.contains("Duration"))
        }

        "json invalid unicode escape throws ParseException" in {
            val r  = JsonReader("\"\\uZZZZ\"")
            val ex = intercept[ParseException](r.string())
            discard(summon[ParseException <:< DecodeException])
            assert(ex.getMessage.contains("unicode"))
        }

        "protobuf truncated varint throws TruncatedInputException" in {
            // A single byte with continuation bit set but nothing after
            val data = Array[Byte](0x80.toByte)
            val r    = new ProtobufReader(data)
            val ex   = intercept[TruncatedInputException](r.field())
            discard(summon[TruncatedInputException <:< DecodeException])
            assert(ex.getMessage.contains("Truncated"))
        }

        "protobuf truncated string throws TruncatedInputException" in {
            // Field tag (field 1, wire type 2 = length-delimited), then varint length 10, but only 2 bytes of data
            val data = Array[Byte](0x0a, 0x0a, 0x41, 0x42)
            val r    = new ProtobufReader(data)
            val _    = r.field()
            val ex   = intercept[TruncatedInputException](r.string())
            discard(summon[TruncatedInputException <:< DecodeException])
            assert(ex.getMessage.contains("Truncated") || ex.getMessage.contains("exceeds"))
        }

        "protobuf truncated bytes throws TruncatedInputException" in {
            // Field tag (field 1, wire type 2), then varint length 5, but only 1 byte
            val data = Array[Byte](0x0a, 0x05, 0x41)
            val r    = new ProtobufReader(data)
            val _    = r.field()
            val ex   = intercept[TruncatedInputException](r.bytes())
            discard(summon[TruncatedInputException <:< DecodeException])
            assert(ex.getMessage.contains("Truncated") || ex.getMessage.contains("exceeds"))
        }

        "protobuf empty data gives no field on read" in {
            val data = Array.emptyByteArray
            val r    = new ProtobufReader(data)
            // hasNextField returns false for empty data
            assert(!r.hasNextField())
        }

        "codec decode malformed json returns Failure" in {
            val result = Json.decode[MTPerson]("{bad json}")
            assert(result.isFailure)
        }

        "codec decode valid json returns Success" in {
            val result = Json.decode[MTPerson]("""{"name":"Alice","age":30}""")
            assert(result == Result.succeed(MTPerson("Alice", 30)))
        }

        "AllPrimitives round-trips through JSON" in {
            val v = AllPrimitives(
                i = 42,
                l = 9_999_999_999L,
                s = 7.toShort,
                b = 3.toByte,
                c = 'Z',
                f = 1.5f,
                d = 2.71828,
                bi = BigInt("123456789012345678901234567890"),
                bd = BigDecimal("3.14159265358979323846"),
                str = "hello",
                bool = true
            )
            val json    = Json.encode(v)
            val decoded = Json.decode[AllPrimitives](json)
            assert(decoded == Result.succeed(v))
        }

        "AllPrimitives round-trips through Protobuf" in {
            val v = AllPrimitives(
                i = -5,
                l = -1L,
                s = (-2).toShort,
                b = 0.toByte,
                c = 'a',
                f = -0.5f,
                d = 1.0,
                bi = BigInt("-42"),
                bd = BigDecimal("0.5"),
                str = "proto",
                bool = false
            )
            val bytes   = Protobuf.encode(v)
            val decoded = Protobuf.decode[AllPrimitives](bytes)
            assert(decoded == Result.succeed(v))
        }

        "all-no-arg enum encodes each case distinctly through JSON" in {
            val first: AllNoArgEnumA  = AllNoArgEnumA.First
            val second: AllNoArgEnumA = AllNoArgEnumA.Second
            val third: AllNoArgEnumA  = AllNoArgEnumA.Third

            val j1 = Json.encode[AllNoArgEnumA](first)
            val j2 = Json.encode[AllNoArgEnumA](second)
            val j3 = Json.encode[AllNoArgEnumA](third)

            assert(j1 != j2, s"First vs Second should differ, got j1=$j1 j2=$j2")
            assert(j2 != j3, s"Second vs Third should differ, got j2=$j2 j3=$j3")
            assert(j1 != j3, s"First vs Third should differ, got j1=$j1 j3=$j3")

            assert(Json.decode[AllNoArgEnumA](j1).getOrThrow == first)
            assert(Json.decode[AllNoArgEnumA](j2).getOrThrow == second)
            assert(Json.decode[AllNoArgEnumA](j3).getOrThrow == third)
        }

        "all-no-arg enum round-trips each case distinctly through Protobuf" in {
            // Byte distinctness exercises sealed-trait variant dispatch on the write path.
            // The decode round-trip exercises top-level sealed-trait read dispatch through matchField.
            val first: AllNoArgEnumA  = AllNoArgEnumA.First
            val second: AllNoArgEnumA = AllNoArgEnumA.Second
            val third: AllNoArgEnumA  = AllNoArgEnumA.Third

            val b1 = Protobuf.encode[AllNoArgEnumA](first)
            val b2 = Protobuf.encode[AllNoArgEnumA](second)
            val b3 = Protobuf.encode[AllNoArgEnumA](third)

            assert(b1.toArray.toSeq != b2.toArray.toSeq, "First vs Second bytes should differ")
            assert(b2.toArray.toSeq != b3.toArray.toSeq, "Second vs Third bytes should differ")
            assert(b1.toArray.toSeq != b3.toArray.toSeq, "First vs Third bytes should differ")

            assert(Protobuf.decode[AllNoArgEnumA](b1).getOrThrow == first)
            assert(Protobuf.decode[AllNoArgEnumA](b2).getOrThrow == second)
            assert(Protobuf.decode[AllNoArgEnumA](b3).getOrThrow == third)
        }

        "mixed parameterized and no-arg enum cases round-trip distinctly through JSON" in {
            val alpha: MixedArityEnum = MixedArityEnum.Alpha(7)
            val beta: MixedArityEnum  = MixedArityEnum.Beta
            val gamma: MixedArityEnum = MixedArityEnum.Gamma

            val ja = Json.encode[MixedArityEnum](alpha)
            val jb = Json.encode[MixedArityEnum](beta)
            val jg = Json.encode[MixedArityEnum](gamma)

            assert(ja != jb, s"Alpha vs Beta should differ, got ja=$ja jb=$jb")
            assert(jb != jg, s"Beta vs Gamma should differ, got jb=$jb jg=$jg")
            assert(ja != jg, s"Alpha vs Gamma should differ, got ja=$ja jg=$jg")

            assert(Json.decode[MixedArityEnum](ja).getOrThrow == alpha)
            assert(Json.decode[MixedArityEnum](jb).getOrThrow == beta)
            assert(Json.decode[MixedArityEnum](jg).getOrThrow == gamma)
        }

        "mixed parameterized and no-arg enum cases round-trip distinctly through Protobuf" in {
            // Byte distinctness exercises sealed-trait variant dispatch on the write path.
            // The round-trip exercises top-level sealed-trait decoding through matchField
            // (covering both the case-class variant Alpha(7) and the two no-arg case-object
            // variants Beta / Gamma).
            val alpha: MixedArityEnum = MixedArityEnum.Alpha(7)
            val beta: MixedArityEnum  = MixedArityEnum.Beta
            val gamma: MixedArityEnum = MixedArityEnum.Gamma

            val ba = Protobuf.encode[MixedArityEnum](alpha)
            val bb = Protobuf.encode[MixedArityEnum](beta)
            val bg = Protobuf.encode[MixedArityEnum](gamma)

            assert(ba.toArray.toSeq != bb.toArray.toSeq, "Alpha vs Beta bytes should differ")
            assert(bb.toArray.toSeq != bg.toArray.toSeq, "Beta vs Gamma bytes should differ")
            assert(ba.toArray.toSeq != bg.toArray.toSeq, "Alpha vs Gamma bytes should differ")

            assert(Protobuf.decode[MixedArityEnum](ba).getOrThrow == alpha)
            assert(Protobuf.decode[MixedArityEnum](bb).getOrThrow == beta)
            assert(Protobuf.decode[MixedArityEnum](bg).getOrThrow == gamma)
        }

        "sealed trait with mixed case class and case objects round-trips distinctly through JSON" in {
            val labeled: SealedNoArgVariants = SealedNoArgVariants.Labeled("hi")
            val unit1: SealedNoArgVariants   = SealedNoArgVariants.Unit1
            val unit2: SealedNoArgVariants   = SealedNoArgVariants.Unit2
            val unit3: SealedNoArgVariants   = SealedNoArgVariants.Unit3

            val jl = Json.encode[SealedNoArgVariants](labeled)
            val j1 = Json.encode[SealedNoArgVariants](unit1)
            val j2 = Json.encode[SealedNoArgVariants](unit2)
            val j3 = Json.encode[SealedNoArgVariants](unit3)

            assert(jl != j1, s"Labeled vs Unit1 should differ, got jl=$jl j1=$j1")
            assert(j1 != j2, s"Unit1 vs Unit2 should differ, got j1=$j1 j2=$j2")
            assert(j2 != j3, s"Unit2 vs Unit3 should differ, got j2=$j2 j3=$j3")
            assert(j1 != j3, s"Unit1 vs Unit3 should differ, got j1=$j1 j3=$j3")

            assert(Json.decode[SealedNoArgVariants](jl).getOrThrow == labeled)
            assert(Json.decode[SealedNoArgVariants](j1).getOrThrow == unit1)
            assert(Json.decode[SealedNoArgVariants](j2).getOrThrow == unit2)
            assert(Json.decode[SealedNoArgVariants](j3).getOrThrow == unit3)
        }
    }

    // ==================== Structure.Type.Open variant ====================

    "Open variant" - {

        "Open.name returns 'Open'" in {
            val open = Structure.Type.Open(Tag[String].asInstanceOf[Tag[Any]])
            assert(open.name == "Open")
        }

        "compatible returns true for two Open with same tag" in {
            val a = Structure.Type.Open(Tag[String].asInstanceOf[Tag[Any]])
            val b = Structure.Type.Open(Tag[String].asInstanceOf[Tag[Any]])
            assert(Structure.Type.compatible(a, b))
        }

        "compatible returns false for two Open with different tags" in {
            val a = Structure.Type.Open(Tag[String].asInstanceOf[Tag[Any]])
            val b = Structure.Type.Open(Tag[Int].asInstanceOf[Tag[Any]])
            assert(!Structure.Type.compatible(a, b))
        }

        "fold visits Open node once with no children" in {
            val open  = Structure.Type.Open(Tag[String].asInstanceOf[Tag[Any]])
            val count = Structure.Type.fold(open)(0) { (acc, _) => acc + 1 }
            assert(count == 1)
        }

        "JsonSchema.fromStructure on Open renders empty Obj" in {
            val open   = Structure.Type.Open(Tag[String].asInstanceOf[Tag[Any]])
            val schema = Json.JsonSchema.fromStructure(open)
            assert(schema == Json.JsonSchema.Obj(List.empty, List.empty))
        }

    }

    // ==================== Type.Schema round-trip via anonymous given ====================

    "Type.Schema anonymous given" - {

        "derives Schema absent on Type class (anonymous given resolves)" in {
            val given1 = summon[Schema[Structure.Type]]
            val given2 = summon[Schema[Structure.Type]]
            assert(given1 eq given2)
        }

        "Open round-trips through Schema[Structure.Type] via JSON" in {
            val open: Structure.Type = Structure.Type.Open(Tag[Int].asInstanceOf[Tag[Any]])
            val encoded              = Json.encode[Structure.Type](open)
            val decoded              = Json.decode[Structure.Type](encoded).getOrThrow
            assert(Structure.Type.compatible(open, decoded))
        }

        "Primitive round-trips through Schema[Structure.Type] via JSON" in {
            val prim: Structure.Type = Structure.Type.Primitive(Structure.PrimitiveKind.String, Tag[String].asInstanceOf[Tag[Any]])
            val encoded              = Json.encode[Structure.Type](prim)
            val decoded              = Json.decode[Structure.Type](encoded).getOrThrow
            assert(Structure.Type.compatible(prim, decoded))
        }

        "Collection round-trips through Schema[Structure.Type] via JSON" in {
            val elem: Structure.Type = Structure.Type.Primitive(Structure.PrimitiveKind.Int, Tag[Int].asInstanceOf[Tag[Any]])
            val coll: Structure.Type = Structure.Type.Collection("List", Tag[List[Int]].asInstanceOf[Tag[Any]], elem)
            val encoded              = Json.encode[Structure.Type](coll)
            val decoded              = Json.decode[Structure.Type](encoded).getOrThrow
            assert(Structure.Type.compatible(coll, decoded))
        }

    }

    "Structure.of reads Schema.structure (T2)" - {

        "Int: Structure.of[Int] is same instance as Schema[Int].structure" in {
            // intSchema is a singleton given so structure is the same lazy val instance
            assert(Structure.of[Int] eq summon[Schema[Int]].structure)
        }

        "List[String]: Structure.of[List[String]] returns compatible structure to Schema[List[String]].structure" in {
            // listSchema is a polymorphic given so two summons may yield different instances;
            // use compatible (structural equality) rather than reference equality
            assert(Structure.Type.compatible(Structure.of[List[String]], summon[Schema[List[String]]].structure))
        }

    }

    // ==================== annotations carrier ====================

    "annotations carrier" - {

        "unannotated derivation is byte-identical to the pre-feature golden" in {
            val plainGolden = """{"a":1,"b":"x"}"""
            val encoded     = Json.encode(L1Plain(1, "x"))
            assert(encoded == plainGolden, s"wire changed from pre-feature golden: got=$encoded expected=$plainGolden")
            assert(!encoded.contains("\"annotations\""), s"annotations leaked into wire: $encoded")
            val decoded = Json.decode[L1Plain](encoded).getOrThrow
            assert(decoded == L1Plain(1, "x"), s"round-trip failed: $decoded")
        }

        "Field wire shape carries exactly the five pre-feature keys" in {
            val ft      = Structure.Type.Primitive(Structure.PrimitiveKind.Int, Tag[Int].asInstanceOf[Tag[Any]])
            val f       = Structure.Field("x", ft, Maybe.empty, Maybe.empty, false, Chunk[Any]("marker"))
            val encoded = Json.encode[Structure.Field](f)
            assert(encoded.contains("\"name\""), s"missing name key: $encoded")
            assert(encoded.contains("\"fieldType\""), s"missing fieldType key: $encoded")
            assert(encoded.contains("\"doc\""), s"missing doc key: $encoded")
            assert(encoded.contains("\"default\""), s"missing default key: $encoded")
            assert(encoded.contains("\"optional\""), s"missing optional key: $encoded")
            assert(!encoded.contains("\"annotations\""), s"annotations appeared in Field wire: $encoded")
        }

        "compatible ignores annotations on Product and Sum" in {
            val tag = Tag[String].asInstanceOf[Tag[Any]]
            val p1  = Structure.Type.Product("P", tag, Chunk.empty, Chunk.empty, Chunk[Any]("x"))
            val p2  = Structure.Type.Product("P", tag, Chunk.empty, Chunk.empty)
            assert(Structure.Type.compatible(p1, p2), "Product compatible must ignore annotations")
            assert(Structure.Type.compatible(p2, p1), "Product compatible must be symmetric under annotations")

            val s1 = Structure.Type.Sum("S", tag, Chunk.empty, Chunk.empty, Chunk.empty, Chunk[Any]("y"))
            val s2 = Structure.Type.Sum("S", tag, Chunk.empty, Chunk.empty, Chunk.empty)
            assert(Structure.Type.compatible(s1, s2), "Sum compatible must ignore annotations")
            assert(Structure.Type.compatible(s2, s1), "Sum compatible must be symmetric under annotations")
        }

        "Field equals and hashCode ignore annotations" in {
            val ft = Structure.Type.Primitive(Structure.PrimitiveKind.Int, Tag[Int].asInstanceOf[Tag[Any]])
            val a  = Structure.Field("x", ft, Maybe.empty, Maybe.empty, false, Chunk[Any]("x"))
            val b  = Structure.Field("x", ft, Maybe.empty, Maybe.empty, false)
            assert(a == b, s"Field.equals must ignore annotations: a=$a b=$b")
            assert(a.hashCode == b.hashCode, s"Field.hashCode must ignore annotations: a.hc=${a.hashCode} b.hc=${b.hashCode}")
        }

        "self-recursive annotated case class derives without StackOverflowError" in {
            val schema = summon[Schema[L1Node]]
            schema.structure match
                case Structure.Type.Product(name, _, _, fields, _) =>
                    assert(name == "L1Node", s"expected name L1Node, got $name")
                    assert(fields.size == 2, s"expected 2 fields, got ${fields.size}")
                    assert(fields(0).name == "value")
                    assert(fields(1).name == "next")
                    assert(
                        fields(1).annotations.exists(_.isInstanceOf[L1NodeAnnotation]),
                        s"recursive field @L1NodeAnnotation must be captured; got ${fields(1).annotations}"
                    )
                case other => fail(s"Expected Product for L1Node, got $other")
            end match
        }

        "unannotated node annotations default to Chunk.empty" in {
            summon[Schema[L1Plain]].structure match
                case p: Structure.Type.Product =>
                    assert(p.annotations.isEmpty, s"Product annotations must default to Chunk.empty, got ${p.annotations}")
                    p.fields.foreach { f =>
                        assert(f.annotations.isEmpty, s"Field ${f.name} annotations must default to Chunk.empty, got ${f.annotations}")
                    }
                case other => fail(s"Expected Product for L1Plain, got $other")
            end match
        }

        "Structure.Type meta-schema wire is byte-identical (Product and Sum, annotated and unannotated)" in {
            val prodGolden =
                """{"Product":{"name":"P","tag":"*8:C:java.io.Serializable:0:::4\n4:A\n5:C:java.lang.constant.Constable:0:::2\n0:C:java.lang.String:0:::1:5:6:7:8\n2:C:java.lang.Object:0:::3\n7:C:java.lang.Comparable:1:0:0:2\n3:C:scala.Matchable:0:::4\n6:C:java.lang.CharSequence:0:::2\n1:C:java.lang.constant.ConstantDesc:0:::2","typeParams":[],"fields":[]}}"""
            val sumGolden =
                """{"Sum":{"name":"S","tag":"*8:C:java.io.Serializable:0:::4\n4:A\n5:C:java.lang.constant.Constable:0:::2\n0:C:java.lang.String:0:::1:5:6:7:8\n2:C:java.lang.Object:0:::3\n7:C:java.lang.Comparable:1:0:0:2\n3:C:scala.Matchable:0:::4\n6:C:java.lang.CharSequence:0:::2\n1:C:java.lang.constant.ConstantDesc:0:::2","typeParams":[],"variants":[],"enumValues":[]}}"""

            val tag              = Tag[String].asInstanceOf[Tag[Any]]
            val prodPlain        = Structure.Type.Product("P", tag, Chunk.empty, Chunk.empty)
            val prodAnnotated    = Structure.Type.Product("P", tag, Chunk.empty, Chunk.empty, Chunk[Any]("marker"))
            val encProdPlain     = Json.encode[Structure.Type](prodPlain)
            val encProdAnnotated = Json.encode[Structure.Type](prodAnnotated)
            assert(
                encProdPlain == prodGolden,
                s"Product wire differs from pre-feature freeze golden: got=$encProdPlain expected=$prodGolden"
            )
            assert(
                encProdPlain == encProdAnnotated,
                s"Product wire must be identical regardless of annotations: plain=$encProdPlain annotated=$encProdAnnotated"
            )
            assert(!encProdAnnotated.contains("\"annotations\""), s"annotations key must not appear in Product wire: $encProdAnnotated")

            val sumPlain        = Structure.Type.Sum("S", tag, Chunk.empty, Chunk.empty, Chunk.empty)
            val sumAnnotated    = Structure.Type.Sum("S", tag, Chunk.empty, Chunk.empty, Chunk.empty, Chunk[Any]("marker"))
            val encSumPlain     = Json.encode[Structure.Type](sumPlain)
            val encSumAnnotated = Json.encode[Structure.Type](sumAnnotated)
            assert(
                encSumPlain == sumGolden,
                s"Sum wire differs from pre-feature freeze golden: got=$encSumPlain expected=$sumGolden"
            )
            assert(
                encSumPlain == encSumAnnotated,
                s"Sum wire must be identical regardless of annotations: plain=$encSumPlain annotated=$encSumAnnotated"
            )
            assert(!encSumAnnotated.contains("\"annotations\""), s"annotations key must not appear in Sum wire: $encSumAnnotated")

            val decodedProd = Json.decode[Structure.Type](encProdAnnotated).getOrThrow
            assert(
                Structure.Type.compatible(prodPlain, decodedProd),
                s"Product round-trip must be structurally compatible: original=$prodPlain decoded=$decodedProd"
            )
            val decodedSum = Json.decode[Structure.Type](encSumAnnotated).getOrThrow
            assert(
                Structure.Type.compatible(sumPlain, decodedSum),
                s"Sum round-trip must be structurally compatible: original=$sumPlain decoded=$decodedSum"
            )
        }

        "Product equals and hashCode ignore annotations" in {
            val tag = Tag[String].asInstanceOf[Tag[Any]]
            val a1  = new L1NodeAnnotation
            val a2  = new L1NodeAnnotation
            val p1  = Structure.Type.Product("P", tag, Chunk.empty, Chunk.empty, Chunk[Any](a1))
            val p2  = Structure.Type.Product("P", tag, Chunk.empty, Chunk.empty, Chunk[Any](a2))
            val p3  = Structure.Type.Product("P", tag, Chunk.empty, Chunk.empty)
            assert(p1 == p2, s"Product.equals must ignore annotations: p1=$p1 p2=$p2")
            assert(p1.hashCode == p2.hashCode, s"Product.hashCode must ignore annotations: p1.hc=${p1.hashCode} p2.hc=${p2.hashCode}")
            assert(p1 == p3, s"Annotated Product must equal annotation-stripped twin: p1=$p1 p3=$p3")
            assert(p1.hashCode == p3.hashCode, "Annotated Product hashCode must equal annotation-stripped twin hashCode")
            val p4 = Structure.Type.Product("Q", tag, Chunk.empty, Chunk.empty)
            assert(p1 != p4, "Products with different names must not be equal")
        }

        "Sum equals and hashCode ignore annotations" in {
            val tag = Tag[String].asInstanceOf[Tag[Any]]
            val a1  = new L1NodeAnnotation
            val a2  = new L1NodeAnnotation
            val s1  = Structure.Type.Sum("S", tag, Chunk.empty, Chunk.empty, Chunk.empty, Chunk[Any](a1))
            val s2  = Structure.Type.Sum("S", tag, Chunk.empty, Chunk.empty, Chunk.empty, Chunk[Any](a2))
            val s3  = Structure.Type.Sum("S", tag, Chunk.empty, Chunk.empty, Chunk.empty)
            assert(s1 == s2, s"Sum.equals must ignore annotations: s1=$s1 s2=$s2")
            assert(s1.hashCode == s2.hashCode, s"Sum.hashCode must ignore annotations: s1.hc=${s1.hashCode} s2.hc=${s2.hashCode}")
            assert(s1 == s3, s"Annotated Sum must equal annotation-stripped twin: s1=$s1 s3=$s3")
            assert(s1.hashCode == s3.hashCode, "Annotated Sum hashCode must equal annotation-stripped twin hashCode")
            val s4 = Structure.Type.Sum("T", tag, Chunk.empty, Chunk.empty, Chunk.empty)
            assert(s1 != s4, "Sums with different names must not be equal")
        }

        "Variant equals and hashCode ignore annotations" in {
            val vt = Structure.Type.Primitive(Structure.PrimitiveKind.Int, Tag[Int].asInstanceOf[Tag[Any]])
            val a1 = new L1NodeAnnotation
            val a2 = new L1NodeAnnotation
            val v1 = Structure.Variant("V", vt, Chunk[Any](a1))
            val v2 = Structure.Variant("V", vt, Chunk[Any](a2))
            val v3 = Structure.Variant("V", vt)
            assert(v1 == v2, s"Variant.equals must ignore annotations: v1=$v1 v2=$v2")
            assert(v1.hashCode == v2.hashCode, s"Variant.hashCode must ignore annotations: v1.hc=${v1.hashCode} v2.hc=${v2.hashCode}")
            assert(v1 == v3, s"Annotated Variant must equal annotation-stripped twin: v1=$v1 v3=$v3")
            assert(v1.hashCode == v3.hashCode, "Annotated Variant hashCode must equal annotation-stripped twin hashCode")
            val v4 = Structure.Variant("W", vt)
            assert(v1 != v4, "Variants with different names must not be equal")
        }

    }

end StructureTest
