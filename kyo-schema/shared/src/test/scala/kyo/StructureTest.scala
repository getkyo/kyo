package kyo

import kyo.internal.JsonReader
import kyo.internal.JsonWriter
import kyo.internal.ProtobufReader
import kyo.internal.ProtobufWriter
import kyo.internal.StructureValueReader
import kyo.internal.StructureValueWriter

case class RTTree(value: Int, children: List[RTTree]) derives CanEqual
case class RTPersonDTO(name: String, age: Int) derives CanEqual
case class RTPersonDiff(name: String, age: String) derives CanEqual

class StructureTest extends Test:

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
                case Structure.Type.Primitive(name, tag) =>
                    assert(name == "Int")
                    assert(tag =:= Tag[Int])
                case other => fail(s"Expected Primitive, got $other")
            end match

            val strRef = Structure.of[String]
            strRef match
                case Structure.Type.Primitive(name, tag) =>
                    assert(name == "String")
                    assert(tag =:= Tag[String])
                case other => fail(s"Expected Primitive, got $other")
            end match

            val boolRef = Structure.of[Boolean]
            boolRef match
                case Structure.Type.Primitive(name, tag) =>
                    assert(name == "Boolean")
                    assert(tag =:= Tag[Boolean])
                case other => fail(s"Expected Primitive, got $other")
            end match

            val doubleRef = Structure.of[Double]
            doubleRef match
                case Structure.Type.Primitive(name, tag) =>
                    assert(name == "Double")
                    assert(tag =:= Tag[Double])
                case other => fail(s"Expected Primitive, got $other")
            end match
        }

        "case class produces Product with fields in declaration order" in {
            val ref = Structure.of[MTPerson]
            ref match
                case Structure.Type.Product(name, tag, _, fields) =>
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
                case Structure.Type.Product(_, _, _, fields) =>
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
                case Structure.Type.Sum(name, tag, _, variants, _) =>
                    assert(name == "MTShape")
                    assert(tag =:= Tag[MTShape])
                    assert(variants.size == 2)
                    assert(variants(0).name == "MTCircle")
                    assert(variants(1).name == "MTRectangle")

                    // Check variant types are Products
                    variants(0).variantType match
                        case Structure.Type.Product(n, _, _, fields) =>
                            assert(n == "MTCircle")
                            assert(fields.size == 1)
                            assert(fields(0).name == "radius")
                        case other => fail(s"Expected Product for MTCircle, got $other")
                    end match

                    variants(1).variantType match
                        case Structure.Type.Product(n, _, _, fields) =>
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
                case Structure.Type.Product(_, _, _, fields) =>
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
                case Structure.Type.Product(_, _, _, fields) =>
                    val itemsField = fields(1)
                    assert(itemsField.name == "items")
                    itemsField.fieldType match
                        case Structure.Type.Collection(_, _, elem) =>
                            elem match
                                case Structure.Type.Product(n, _, _, _) => assert(n == "MTItem")
                                case other                              => fail(s"Expected Product element, got $other")
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
                case Structure.Type.Product(_, _, _, fields) =>
                    assert(fields.size == 2)

                    val leadField = fields(0)
                    assert(leadField.name == "lead")
                    leadField.fieldType match
                        case Structure.Type.Product(n, _, _, innerFields) =>
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
                case Structure.Type.Product(name, _, _, fields) =>
                    assert(name == "RTTree")
                    assert(fields.size == 2)
                    assert(fields(0).name == "value")

                    val childrenField = fields(1)
                    assert(childrenField.name == "children")
                    childrenField.fieldType match
                        case Structure.Type.Collection(_, _, elem) =>
                            // The recursive reference should be a Product (possibly with empty fields)
                            elem match
                                case Structure.Type.Product(n, _, _, _) =>
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

        "fieldPaths returns all leaf paths" in {
            val ref   = Structure.of[MTSmallTeam]
            val paths = Structure.Type.fieldPaths(ref)
            assert(paths == Chunk(Chunk("lead", "name"), Chunk("lead", "age"), Chunk("size")))
        }

        "Structure.of[A] matches Schema[A].structure" in {
            val fromDirect = Structure.of[MTPerson]
            val fromSchema = Schema[MTPerson].structure

            // Both should produce structurally identical types
            assert(Structure.Type.compatible(fromDirect, fromSchema))

            // Both should be Products with the same field structure
            (fromDirect, fromSchema) match
                case (Structure.Type.Product(n1, _, _, f1), Structure.Type.Product(n2, _, _, f2)) =>
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
                case Structure.Type.Product(_, _, _, fields) =>
                    assert(fields.forall(_.doc.isEmpty))
                case other => fail(s"Expected Product, got $other")
            end match
        }

        "List[Int] produces Collection" in {
            val tpe = Structure.of[List[Int]]
            tpe match
                case Structure.Type.Collection(_, _, elem) =>
                    elem match
                        case Structure.Type.Primitive(n, _) => assert(n == "Int")
                        case other                          => fail(s"Expected Primitive element, got $other")
                case other =>
                    fail(s"Expected Collection, got $other")
            end match
        }

        "Seq[String] produces Collection" in {
            val tpe = Structure.of[Seq[String]]
            tpe match
                case Structure.Type.Collection(_, _, elem) =>
                    elem match
                        case Structure.Type.Primitive(n, _) => assert(n == "String")
                        case other                          => fail(s"Expected Primitive element, got $other")
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
                case Structure.Type.Product(_, _, _, fields) =>
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

            "record equality" in {
                val r1 = Structure.Value.Record(Chunk(("x", Structure.Value.primitive(1))))
                val r2 = Structure.Value.Record(Chunk(("x", Structure.Value.primitive(1))))
                val r3 = Structure.Value.Record(Chunk(("x", Structure.Value.primitive(2))))
                assert(r1 == r2)
                assert(r1 != r3)
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
            // At the top level, mapStart/mapEnd are equivalent to objectStart/objectEnd
            val w = new ProtobufWriter
            w.mapStart(2)
            w.field("key1", 0)
            w.string("value1")
            w.field("key2", 1)
            w.int(42)
            w.mapEnd()
            val bytes = w.resultBytes
            assert(bytes.nonEmpty)
            // Verify we can read it back
            val r = new ProtobufReader(bytes)
            val _ = r.mapStart()
            assert(r.hasNextEntry())
            val _ = r.field()
            assert(r.string() == "value1")
            assert(r.hasNextEntry())
            val _ = r.field()
            assert(r.int() == 42)
            assert(!r.hasNextEntry())
            r.mapEnd()
            succeed
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
            val dv  = w.getResult
            val r   = new StructureValueReader(dv)
            val got = r.bytes()
            assert(got.toArray.toSeq == data.toArray.toSeq)
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
            val r  = new StructureValueReader(dv)
            assert(r.instant() == value)
        }

        "dynamic duration round-trip" in {
            val value = java.time.Duration.ofMinutes(90)
            val w     = new StructureValueWriter
            w.duration(value)
            val dv = w.getResult
            val r  = new StructureValueReader(dv)
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
            succeed
        }

        "json int parse error throws ParseException" in {
            val r  = JsonReader("3.14")
            val ex = intercept[ParseException](r.int())
            assert(ex.isInstanceOf[DecodeException])
            assert(ex.getMessage.contains("Cannot parse"))
        }

        "json long parse error throws ParseException" in {
            val r = JsonReader("\"abc\"")
            // readNumber will fail because "abc" starts with '"', not a digit
            val ex = intercept[ParseException](r.long())
            assert(ex.isInstanceOf[DecodeException])
        }

        "json short overflow throws ParseException" in {
            val r  = JsonReader("99999")
            val ex = intercept[ParseException](r.short())
            assert(ex.isInstanceOf[DecodeException])
            assert(ex.getMessage.contains("Cannot parse"))
        }

        "json byte overflow throws ParseException" in {
            val r  = JsonReader("999")
            val ex = intercept[ParseException](r.byte())
            assert(ex.isInstanceOf[DecodeException])
            assert(ex.getMessage.contains("Cannot parse"))
        }

        "json invalid base64 throws ParseException" in {
            val r  = JsonReader("\"not-valid-base64!!!\"")
            val ex = intercept[ParseException](r.bytes())
            assert(ex.isInstanceOf[DecodeException])
            assert(ex.getMessage.contains("Base64"))
        }

        "json invalid bigInt throws ParseException" in {
            val r  = JsonReader("\"not_a_number\"")
            val ex = intercept[ParseException](r.bigInt())
            assert(ex.isInstanceOf[DecodeException])
            assert(ex.getMessage.contains("BigInt"))
        }

        "json invalid bigDecimal throws ParseException" in {
            val r  = JsonReader("\"not_a_number\"")
            val ex = intercept[ParseException](r.bigDecimal())
            assert(ex.isInstanceOf[DecodeException])
            assert(ex.getMessage.contains("BigDecimal"))
        }

        "json invalid instant throws ParseException" in {
            val r  = JsonReader("\"not-a-date\"")
            val ex = intercept[ParseException](r.instant())
            assert(ex.isInstanceOf[DecodeException])
            assert(ex.getMessage.contains("Instant"))
        }

        "json invalid duration throws ParseException" in {
            val r  = JsonReader("\"not-a-duration\"")
            val ex = intercept[ParseException](r.duration())
            assert(ex.isInstanceOf[DecodeException])
            assert(ex.getMessage.contains("Duration"))
        }

        "json invalid unicode escape throws ParseException" in {
            val r  = JsonReader("\"\\uZZZZ\"")
            val ex = intercept[ParseException](r.string())
            assert(ex.isInstanceOf[DecodeException])
            assert(ex.getMessage.contains("unicode"))
        }

        "protobuf truncated varint throws TruncatedInputException" in {
            // A single byte with continuation bit set but nothing after
            val data = Array[Byte](0x80.toByte)
            val r    = new ProtobufReader(data)
            val ex   = intercept[TruncatedInputException](r.field())
            assert(ex.isInstanceOf[DecodeException])
            assert(ex.getMessage.contains("Truncated"))
        }

        "protobuf truncated string throws TruncatedInputException" in {
            // Field tag (field 1, wire type 2 = length-delimited), then varint length 10, but only 2 bytes of data
            val data = Array[Byte](0x0a, 0x0a, 0x41, 0x42)
            val r    = new ProtobufReader(data)
            val _    = r.field()
            val ex   = intercept[TruncatedInputException](r.string())
            assert(ex.isInstanceOf[DecodeException])
            assert(ex.getMessage.contains("Truncated") || ex.getMessage.contains("exceeds"))
        }

        "protobuf truncated bytes throws TruncatedInputException" in {
            // Field tag (field 1, wire type 2), then varint length 5, but only 1 byte
            val data = Array[Byte](0x0a, 0x05, 0x41)
            val r    = new ProtobufReader(data)
            val _    = r.field()
            val ex   = intercept[TruncatedInputException](r.bytes())
            assert(ex.isInstanceOf[DecodeException])
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
    }

end StructureTest
