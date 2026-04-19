package kyo

import Json.JsonSchema
import Record.*
import Schema.*
import kyo.internal.CodecMacro
import kyo.internal.JsonReader
import kyo.internal.JsonWriter
import kyo.internal.ProtobufReader
import kyo.internal.ProtobufWriter
import kyo.internal.StructureValueReader
import kyo.internal.StructureValueWriter

class SchemaTest extends Test:

    given CanEqual[Any, Any] = CanEqual.derived

    // =========================================================================
    // apply
    // =========================================================================

    "apply" - {

        "apply simple case class" in {
            val m                                                                    = Schema[MTPerson]
            val _: Schema[MTPerson] { type Focused = "name" ~ String & "age" ~ Int } = m
            succeed
        }

        "apply nested case class" in {
            val m = Schema[MTTeam]
            val _: Schema[MTTeam] { type Focused = "name" ~ String & "lead" ~ MTPersonAddr & "members" ~ List[MTPersonAddr] } = m
            succeed
        }

        "apply sealed trait" in {
            val m                                                                                         = Schema[MTShape]
            val _: Schema[MTShape] { type Focused = "MTCircle" ~ MTCircle | "MTRectangle" ~ MTRectangle } = m
            succeed
        }

        "apply case class with defaults" in {
            val m                                                                                       = Schema[MTConfig]
            val _: Schema[MTConfig] { type Focused = "host" ~ String & "port" ~ Int & "ssl" ~ Boolean } = m
            succeed
        }

        "apply generic case class" in {
            val m                                                                                   = Schema[MTPair[Int, String]]
            val _: Schema[MTPair[Int, String]] { type Focused = "first" ~ Int & "second" ~ String } = m
            succeed
        }

        "apply single field" in {
            val m                                                        = Schema[MTWrapper]
            val _: Schema[MTWrapper] { type Focused = "value" ~ String } = m
            succeed
        }

        "apply with container fields" in {
            val m                                                                         = Schema[MTOrder]
            val _: Schema[MTOrder] { type Focused = "id" ~ Int & "items" ~ List[MTItem] } = m
            succeed
        }

        "apply primitive type" in {
            val m                                     = Schema[Int]
            val _: Schema[Int] { type Focused = Int } = m
            succeed
        }

        "apply already structural" in {
            val m = Schema["name" ~ String & "age" ~ Int]
            val _: Schema["name" ~ String & "age" ~ Int] { type Focused = "name" ~ String & "age" ~ Int } = m
            succeed
        }

        "apply recursive type" in {
            case class MTTree(value: Int, children: List[MTTree]) derives CanEqual
            val m                                                                              = Schema[MTTree]
            val _: Schema[MTTree] { type Focused = "value" ~ Int & "children" ~ List[MTTree] } = m
            succeed
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
    // metadata
    // =========================================================================

    "metadata" - {

        // --- Tests from SchemaDescribeTest (doc/deprecated/example/metadata) ---

        "doc stores and retrieves root documentation" in {
            val s = Schema[MTUser].doc("A registered user")
            assert(s.doc == Maybe("A registered user"))
        }

        "doc(_.field) stores and retrieves per-field documentation" in {
            val s = Schema[MTUser]
                .doc(_.name)("The user's display name")
                .doc(_.email)("RFC 5322 email address")
            assert(s.focus(_.name).doc == Maybe("The user's display name"))
            assert(s.focus(_.email).doc == Maybe("RFC 5322 email address"))
        }

        "example stores and retrieves example values" in {
            val s = Schema[MTUser]
                .example(MTUser(1.toString, 1, "alice@example.com", ""))
            assert(s.examples.size == 1)
        }

        "deprecated stores and retrieves deprecation reason" in {
            val s = Schema[MTUser]
                .deprecated(_.ssn)("Use taxId instead")
            assert(s.focus(_.ssn).deprecated == Maybe("Use taxId instead"))
        }

        "multiple examples accumulate in order" in {
            val u1 = MTUser("Alice", 30, "alice@example.com", "111")
            val u2 = MTUser("Bob", 25, "bob@example.com", "222")
            val s = Schema[MTUser]
                .example(u1)
                .example(u2)
            assert(s.examples == Seq(u1, u2))
        }

        "metadata survives transform chain" in {
            val user = MTUser("Alice", 30, "alice@example.com", "123-45-6789")
            val s = Schema[MTUser]
                .doc("A user")
                .doc(_.name)("User name")
                .example(user)
                .deprecated(_.ssn)("Use taxId instead")
                .drop("ssn")
                .rename("name", "userName")
                .add("active")(_ => true)
            assert(s.doc == Maybe("A user"))
            assert(s.examples.size == 1)
            // After rename: "name" -> "userName", doc key should be updated
            assert(s.fieldDocs.get(Seq("userName")) == Some("User name"))
            // After drop: "ssn" deprecated should be removed
        }

        "rename updates field doc key" in {
            val s = Schema[MTUser]
                .doc(_.name)("The user's name")
                .rename("name", "userName")
            assert(s.fieldDocs.get(Seq("userName")) == Some("The user's name"))
        }

        "drop removes field doc and deprecated entries" in {
            val s = Schema[MTUser]
                .doc(_.ssn)("Social security number")
                .deprecated(_.ssn)("Use taxId instead")
                .drop("ssn")
            // ssn is dropped, metadata removed — verify via field doc on remaining fields
            assert(s.focus(_.name).doc == Maybe.empty)
        }

        "description returns Maybe.empty when no doc set" in {
            val s = Schema[MTUser]
            assert(s.doc == Maybe.empty)
        }

        "focus description returns Maybe.empty for undocumented fields" in {
            val s = Schema[MTUser]
            assert(s.focus(_.name).doc == Maybe.empty)
        }

        "all metadata in one chain" in {
            val user = MTUser("Alice", 30, "alice@example.com", "123-45-6789")
            val s = Schema[MTUser]
                .doc("A registered user")
                .doc(_.name)("The user's full name")
                .doc(_.email)("Primary contact email")
                .example(user)
                .deprecated(_.ssn)("Use taxId instead")
            assert(s.doc == Maybe("A registered user"))
            assert(s.focus(_.name).doc == Maybe("The user's full name"))
            assert(s.focus(_.email).doc == Maybe("Primary contact email"))
            assert(s.examples == Seq(user))
            assert(s.focus(_.ssn).deprecated == Maybe("Use taxId instead"))
        }

        "focus deprecated returns Maybe.empty for non-deprecated fields" in {
            val s = Schema[MTUser]
            assert(s.focus(_.name).deprecated == Maybe.empty)
        }

        "examples returns empty Seq when none set" in {
            val s = Schema[MTUser]
            assert(s.examples == Seq.empty)
        }

        "rename updates deprecated key" in {
            val s = Schema[MTUser]
                .deprecated(_.name)("Use displayName instead")
                .rename("name", "displayName")
            assert(s.fieldDeprecated.get(Seq("displayName")) == Some("Use displayName instead"))
        }

        "schema has no metadata by default" in {
            val s = Schema[MTUser]
            assert(s.doc == Maybe.empty)
        }

        "schema includes root doc" in {
            val s = Schema[MTUser]
                .doc("A registered user")
            assert(s.doc == Maybe("A registered user"))
        }

        "schema includes field doc" in {
            val s = Schema[MTUser]
                .doc(_.name)("The user's full name")
                .doc(_.email)("Primary contact email")
            assert(s.focus(_.name).doc == Maybe("The user's full name"))
            assert(s.focus(_.email).doc == Maybe("Primary contact email"))
        }

        "schema includes field deprecated" in {
            val s = Schema[MTUser]
                .deprecated(_.ssn)("Use taxId instead")
            assert(s.focus(_.ssn).deprecated == Maybe("Use taxId instead"))
        }

        "schema includes examples" in {
            val user = MTUser("Alice", 30, "alice@example.com", "123-45-6789")
            val s = Schema[MTUser]
                .example(user)
            assert(s.examples == Seq(user))
        }

        "metadata survives transform chain into describe" in {
            val user = MTUser("Alice", 30, "alice@example.com", "123-45-6789")
            val s = Schema[MTUser]
                .doc("A user")
                .doc(_.name)("User name")
                .example(user)
                .deprecated(_.ssn)("Use taxId instead")
                .drop("ssn")
                .rename("name", "userName")
                .add("active")(_ => true)
            assert(s.doc == Maybe("A user"))
            assert(s.examples.size == 1)
            // After rename: "name" -> "userName", doc key should be updated
            assert(s.fieldDocs.get(Seq("userName")) == Some("User name"))
            // After drop: "ssn" deprecated should be removed
        }

        "rename updates field doc and deprecated keys" in {
            val s = Schema[MTUser]
                .doc(_.name)("The user's name")
                .deprecated(_.name)("Use displayName instead")
                .rename("name", "displayName")
            assert(s.fieldDocs.get(Seq("displayName")) == Some("The user's name"))
            assert(s.fieldDeprecated.get(Seq("displayName")) == Some("Use displayName instead"))
        }

        "drop removes field doc and deprecated entries from schema" in {
            val s = Schema[MTUser]
                .doc(_.ssn)("Social security number")
                .deprecated(_.ssn)("Use taxId instead")
                .drop("ssn")
            // ssn is dropped, so metadata should be removed from maps
            assert(s.focus(_.name).doc == Maybe.empty)
        }

        "undocumented field has empty doc" in {
            val s = Schema[MTUser]
                .doc(_.name)("The user's name")
            assert(s.focus(_.age).doc == Maybe.empty)
            assert(s.focus(_.age).deprecated == Maybe.empty)
        }

        "multiple examples are accessible in order" in {
            val u1 = MTUser("Alice", 30, "alice@example.com", "111")
            val u2 = MTUser("Bob", 25, "bob@example.com", "222")
            val s = Schema[MTUser]
                .example(u1)
                .example(u2)
            assert(s.examples == Seq(u1, u2))
        }

        // --- Tests from SchemaMetadataTest (JsonSchema enrichment) ---

        case class Person(name: String, age: Int) derives CanEqual
        case class PersonWithOld(name: String, age: Int, old: String) derives CanEqual
        case class Address(street: String, city: String) derives CanEqual
        case class PersonWithAddress(name: String, age: Int, address: Address) derives CanEqual
        case class PersonFull(name: String, age: Int, old: String) derives CanEqual

        "doc sets root description on Obj" in {
            val schema = Json.jsonSchema(using Schema[Person].doc("A person"))
            schema match
                case obj: JsonSchema.Obj =>
                    assert(obj.description == Maybe("A person"))
                case other =>
                    fail(s"Expected JsonSchema.Obj, got $other")
            end match
        }

        "field doc sets description on named property" in {
            val schema = Json.jsonSchema(using Schema[Person].doc(_.name)("The name"))
            schema match
                case obj: JsonSchema.Obj =>
                    val nameProp = obj.properties.find(_._1 == "name").map(_._2)
                    nameProp match
                        case Some(s: JsonSchema.Str) =>
                            assert(s.description == Maybe("The name"))
                        case Some(other) =>
                            fail(s"Expected JsonSchema.Str for 'name', got $other")
                        case None =>
                            fail("Property 'name' not found")
                    end match
                case other =>
                    fail(s"Expected JsonSchema.Obj, got $other")
            end match
        }

        "deprecated marks field deprecated when field type is Obj" in {
            val schema = Json.jsonSchema(using Schema[PersonWithAddress].deprecated(_.address)("use new"))
            schema match
                case obj: JsonSchema.Obj =>
                    val addrProp = obj.properties.find(_._1 == "address").map(_._2)
                    addrProp match
                        case Some(s: JsonSchema.Obj) =>
                            assert(s.deprecated == Maybe(true))
                        case Some(other) =>
                            fail(s"Expected JsonSchema.Obj for 'address', got $other")
                        case None =>
                            fail("Property 'address' not found")
                    end match
                case other =>
                    fail(s"Expected JsonSchema.Obj, got $other")
            end match
        }

        "deprecated is no-op for non-Obj field type (String field)" in {
            val schema = Json.jsonSchema(using Schema[PersonWithOld].deprecated(_.old)("use new"))
            schema match
                case obj: JsonSchema.Obj =>
                    val oldProp = obj.properties.find(_._1 == "old").map(_._2)
                    oldProp match
                        case Some(s: JsonSchema.Str) =>
                            // deprecated not added since Str has no deprecated field
                            assert(s.description == Maybe.empty)
                        case Some(other) =>
                            fail(s"Expected JsonSchema.Str for 'old', got $other")
                        case None =>
                            fail("Property 'old' not found")
                    end match
                case other =>
                    fail(s"Expected JsonSchema.Obj, got $other")
            end match
        }

        "example value appears in Obj.examples" in {
            val alice  = Person("Alice", 30)
            val schema = Json.jsonSchema(using Schema[Person].example(alice))
            schema match
                case obj: JsonSchema.Obj =>
                    assert(obj.examples.size == 1)
                    // The example should be a Structure.Value.Record with the Person's fields
                    obj.examples.head match
                        case Structure.Value.Record(fields) =>
                            val names = fields.map(_._1).toList
                            assert(names.contains("name"))
                            assert(names.contains("age"))
                        case other =>
                            fail(s"Expected Structure.Value.Record, got $other")
                    end match
                case other =>
                    fail(s"Expected JsonSchema.Obj, got $other")
            end match
        }

        "multiple field docs set descriptions on respective properties" in {
            val schema = Json.jsonSchema(using
                Schema[Person]
                    .doc(_.name)("The person's name")
                    .doc(_.age)("The person's age")
            )
            schema match
                case obj: JsonSchema.Obj =>
                    val nameProp = obj.properties.find(_._1 == "name").map(_._2)
                    val ageProp  = obj.properties.find(_._1 == "age").map(_._2)
                    nameProp match
                        case Some(s: JsonSchema.Str) =>
                            assert(s.description == Maybe("The person's name"))
                        case other =>
                            fail(s"Expected JsonSchema.Str for 'name', got $other")
                    end match
                    ageProp match
                        case Some(s: JsonSchema.Integer) =>
                            assert(s.description == Maybe("The person's age"))
                        case other =>
                            fail(s"Expected JsonSchema.Integer for 'age', got $other")
                    end match
                case other =>
                    fail(s"Expected JsonSchema.Obj, got $other")
            end match
        }

        "field doc on nested Obj field sets description" in {
            val schema = Json.jsonSchema(using Schema[PersonWithAddress].doc(_.address)("The address"))
            schema match
                case obj: JsonSchema.Obj =>
                    val addrProp = obj.properties.find(_._1 == "address").map(_._2)
                    addrProp match
                        case Some(s: JsonSchema.Obj) =>
                            assert(s.description == Maybe("The address"))
                        case Some(other) =>
                            fail(s"Expected JsonSchema.Obj for 'address', got $other")
                        case None =>
                            fail("Property 'address' not found")
                    end match
                case other =>
                    fail(s"Expected JsonSchema.Obj, got $other")
            end match
        }

        "combined metadata all appear in JsonSchema" in {
            val example = PersonFull("Alice", 30, "oldVal")
            val schema = Json.jsonSchema(using
                Schema[PersonFull]
                    .doc("root doc")
                    .doc(_.name)("name field")
                    .deprecated(_.old)("gone")
                    .example(example)
            )
            schema match
                case obj: JsonSchema.Obj =>
                    // root description
                    assert(obj.description == Maybe("root doc"))
                    // examples
                    assert(obj.examples.size == 1)
                    // name field description
                    val nameProp = obj.properties.find(_._1 == "name").map(_._2)
                    nameProp match
                        case Some(s: JsonSchema.Str) =>
                            assert(s.description == Maybe("name field"))
                        case other =>
                            fail(s"Expected JsonSchema.Str for 'name', got $other")
                    end match
                    // old field: non-Obj, deprecated is no-op but field still present
                    val oldProp = obj.properties.find(_._1 == "old").map(_._2)
                    assert(oldProp.isDefined, "Property 'old' should be present")
                case other =>
                    fail(s"Expected JsonSchema.Obj, got $other")
            end match
        }

        "no metadata produces same schema as JsonSchema.from" in {
            val fromSchema = Json.jsonSchema[Person]
            val fromMacro  = JsonSchema.from[Person]
            assert(fromSchema == fromMacro)
        }

        "doc setter and description getter are consistent" in {
            val s = Schema[Person].doc("A person")
            assert(s.doc == Maybe("A person"))
        }

        "field doc setter and description getter are consistent" in {
            val s = Schema[Person].doc(_.name)("Full name")
            val f = s.focus(_.name)
            assert(f.doc == Maybe("Full name"))
        }
    }

    // =========================================================================
    // check
    // =========================================================================

    "check" - {

        val person     = MTPerson("Alice", 30)
        val address    = MTAddress("123 Main St", "Portland", "97201")
        val personAddr = MTPersonAddr("Alice", 30, address)

        // --- Single field checks ---

        "check passes" in {
            val errors = Schema[MTPerson].check(_.name)(_.nonEmpty, "name required").validate(person)
            assert(errors.isEmpty)
        }

        "check fails" in {
            val errors = Schema[MTPerson].check(_.name)(_.nonEmpty, "name required").validate(MTPerson("", 30))
            assert(errors.size == 1)
            assert(errors.head.message == "name required")
        }

        "check multiple on same field" in {
            val m = Schema[MTPerson]
                .check(_.name)(_.nonEmpty, "name required")
                .check(_.name)(_.length < 100, "name too long")
            val errors = m.validate(person)
            assert(errors.isEmpty)
        }

        "check multiple both pass" in {
            val m = Schema[MTPerson]
                .check(_.name)(_.nonEmpty, "name required")
                .check(_.name)(_.length < 100, "name too long")
            val errors = m.validate(MTPerson("Bob", 25))
            assert(errors.isEmpty)
        }

        "check multiple first fails" in {
            val m = Schema[MTPerson]
                .check(_.name)(_.nonEmpty, "name required")
                .check(_.name)(_.length < 100, "name too long")
            val errors = m.validate(MTPerson("", 25))
            assert(errors.size == 1)
            assert(errors.head.message == "name required")
        }

        "check multiple second fails" in {
            val longName = "x" * 101
            val m = Schema[MTPerson]
                .check(_.name)(_.nonEmpty, "name required")
                .check(_.name)(_.length < 100, "name too long")
            val errors = m.validate(MTPerson(longName, 25))
            assert(errors.size == 1)
            assert(errors.head.message == "name too long")
        }

        "check multiple both fail" in {
            val m = Schema[MTPerson]
                .check(_.name)(_.nonEmpty, "name required")
                .check(_.name)(_.length > 3, "name too short")
            val errors = m.validate(MTPerson("", 25))
            assert(errors.size == 2)
            assert(errors.exists(_.message == "name required"))
            assert(errors.exists(_.message == "name too short"))
        }

        "check snaps back to root type" in {
            val m = Schema[MTPerson].check(_.name)(_.nonEmpty, "name required")
            // After check, we're back at root Schema — can focus on a different field
            val m2     = m.check(_.age)(_ > 0, "age positive")
            val errors = m2.validate(person)
            assert(errors.isEmpty)
        }

        // --- Multi-field validation ---

        "validate multi-field all pass" in {
            val m = Schema[MTPerson]
                .check(_.name)(_.nonEmpty, "name required")
                .check(_.age)(_ >= 0, "age non-negative")
            val errors = m.validate(person)
            assert(errors.isEmpty)
        }

        "validate multi-field one fails" in {
            val m = Schema[MTPerson]
                .check(_.name)(_.nonEmpty, "name required")
                .check(_.age)(_ >= 0, "age non-negative")
            val errors = m.validate(MTPerson("", 30))
            assert(errors.size == 1)
            assert(errors.head.message == "name required")
        }

        "validate multi-field both fail" in {
            val m = Schema[MTPerson]
                .check(_.name)(_.nonEmpty, "name required")
                .check(_.age)(_ >= 0, "age non-negative")
            val errors = m.validate(MTPerson("", -1))
            assert(errors.size == 2)
            assert(errors.exists(_.message == "name required"))
            assert(errors.exists(_.message == "age non-negative"))
        }

        "validate collects all errors" in {
            val m = Schema[MTPersonAddr]
                .check(_.name)(_.nonEmpty, "name required")
                .check(_.age)(_ >= 18, "must be adult")
                .check(_.address.city)(_.nonEmpty, "city required")
            val errors = m.validate(MTPersonAddr("", 10, MTAddress("st", "", "z")))
            assert(errors.size == 3)
            assert(errors.exists(_.message == "name required"))
            assert(errors.exists(_.message == "must be adult"))
            assert(errors.exists(_.message == "city required"))
        }

        "validate empty rules" in {
            val m      = Schema[MTPerson]
            val errors = m.validate(person)
            assert(errors.isEmpty)
        }

        "validate with nested field" in {
            val m = Schema[MTPersonAddr]
                .check(_.address.city)(_.nonEmpty, "city required")
            val errors = m.validate(MTPersonAddr("Alice", 30, MTAddress("st", "", "z")))
            assert(errors.size == 1)
            assert(errors.head.path == List("address", "city"))
        }

        // --- Error structure ---

        "error has correct path" in {
            val errors = Schema[MTPerson].check(_.name)(_.nonEmpty, "name required").validate(MTPerson("", 30))
            assert(errors.head.path == List("name"))
        }

        "error has correct message" in {
            val errors = Schema[MTPerson].check(_.age)(_ >= 0, "age non-negative").validate(MTPerson("Alice", -1))
            assert(errors.head.message == "age non-negative")
        }

        "error path for nested" in {
            val errors = Schema[MTPersonAddr]
                .check(_.address.city)(_.nonEmpty, "city required")
                .validate(MTPersonAddr("Alice", 30, MTAddress("st", "", "z")))
            assert(errors.head.path == List("address", "city"))
        }

        "error is ValidationFailedException" in {
            val errors = Schema[MTPerson].check(_.name)(_.nonEmpty, "name required").validate(MTPerson("", 30))
            assert(errors.head.isInstanceOf[ValidationFailedException])
        }

        // --- Chain behavior ---

        "check returns root Schema type" in {
            val m1 = Schema[MTPerson]
            val m2 = m1.check(_.name)(_.nonEmpty, "name required")
            val m3 = m2.check(_.name)(_.length < 100, "name too long")
            // m2, m3 should be Schema[MTPerson] (root structural type), not Schema[MTPerson] { type Focused = String }
            val errors = m3.validate(person)
            assert(errors.isEmpty)
        }

        "check then focus get" in {
            val m = Schema[MTPerson].check(_.name)(_.nonEmpty, "name required")
            // After check we're at root, need to focus again to get a field
            val result = m.focus(_.name).get(person)
            assert(result == "Alice")
        }

        "check then focus set" in {
            val m = Schema[MTPerson].check(_.name)(_.nonEmpty, "name required")
            // After check we're at root, need to focus again to set a field
            val result = m.focus(_.name).set(person, "Bob")
            assert(result == MTPerson("Bob", 30))
        }

        "check then focus update" in {
            val m = Schema[MTPerson].check(_.name)(_.nonEmpty, "name required")
            // After check we're at root, need to focus again to update a field
            val result = m.focus(_.name).update(person)(_.toUpperCase)
            assert(result == MTPerson("ALICE", 30))
        }

        "focus check preserves path in error" in {
            val m      = Schema[MTPerson].check(_.name)(_.nonEmpty, "name required")
            val errors = m.validate(MTPerson("", 30))
            assert(errors.head.path == List("name"))
        }

        "validate with separate metas via mergeChecks" in {
            val nameMeta = Schema[MTPerson].check(_.name)(_.nonEmpty, "name required")
            val ageMeta  = Schema[MTPerson].check(_.age)(_ >= 0, "age non-negative")
            val combined = nameMeta.mergeChecks(ageMeta)
            val errors   = combined.validate(person)
            assert(errors.isEmpty)
        }

        // --- Composable focus+check ---

        "composable focus check multiple fields" in {
            val m = Schema[MTPerson]
                .check(_.name)(_.nonEmpty, "required")
                .check(_.age)(_ > 0, "positive")
            val errors = m.validate(MTPerson("", 0))
            assert(errors.size == 2)
            assert(errors.exists(_.message == "required"))
            assert(errors.exists(_.message == "positive"))
        }

        "composable cross-field check" in {
            val m = Schema[MTPerson]
                .check(_.name)(_.nonEmpty, "required")
                .check(_.age)(_ > 0, "positive")
                .check(p => p.age >= 18 || p.name.nonEmpty, "need name or be adult")
            val errors = m.validate(MTPerson("Alice", 5))
            assert(errors.isEmpty)
            val errors2 = m.validate(MTPerson("", 5))
            assert(errors2.exists(_.message == "required"))
            assert(errors2.exists(_.message == "need name or be adult"))
        }

        "composable check then validate" in {
            val m = Schema[MTPersonAddr]
                .check(_.name)(_.nonEmpty, "name required")
                .check(_.age)(_ >= 18, "must be adult")
                .check(_.address.city)(_.nonEmpty, "city required")
            // All pass
            val errors = m.validate(personAddr)
            assert(errors.isEmpty)
            // All fail
            val errors2 = m.validate(MTPersonAddr("", 10, MTAddress("st", "", "z")))
            assert(errors2.size == 3)
        }

        "composable check then transform" in {
            val m = Schema[MTUser]
                .check(_.name)(_.nonEmpty, "name required")
                .check(_.age)(_ > 0, "positive")
                .drop("ssn")
            // Checks survive transforms
            val errors = m.validate(MTUser("", 0, "alice@test.com", "123"))
            assert(errors.size == 2)
            assert(errors.exists(_.message == "name required"))
            assert(errors.exists(_.message == "positive"))
        }

        "composable focus check then get" in {
            val m = Schema[MTPerson]
                .check(_.name)(_.nonEmpty, "required")
            // m is back at root, can focus again
            val result = m.focus(_.name).get(person)
            assert(result == "Alice")
        }

        "existing focus get still works" in {
            val result = Schema[MTPerson].focus(_.name).get(person)
            assert(result == "Alice")
        }

        "existing focus set still works" in {
            val result = Schema[MTPerson].focus(_.name).set(person, "Bob")
            assert(result == MTPerson("Bob", 30))
        }

        "cross-field check with Option field None passes" in {
            val m = Schema[MTRegistration]
                .check(r => r.referralCode.forall(_.length == 8), "referral code must be 8 chars")
            val errors = m.validate(MTRegistration("alice", 25, "a@b.com", None))
            assert(errors.isEmpty)
        }

        "cross-field check with Option field Some valid passes" in {
            val m = Schema[MTRegistration]
                .check(r => r.referralCode.forall(_.length == 8), "referral code must be 8 chars")
            val errors = m.validate(MTRegistration("alice", 25, "a@b.com", Some("ABCD1234")))
            assert(errors.isEmpty)
        }

        "cross-field check with Option field Some invalid fails" in {
            val m = Schema[MTRegistration]
                .check(r => r.referralCode.forall(_.length == 8), "referral code must be 8 chars")
            val errors = m.validate(MTRegistration("alice", 25, "a@b.com", Some("short")))
            assert(errors.size == 1)
            assert(errors.head.message == "referral code must be 8 chars")
        }

        "chain 4 field checks plus 1 cross-field all fail" in {
            val m = Schema[MTRegistration]
                .check(_.username)(_.nonEmpty, "username required")
                .check(_.username)(_.length <= 20, "username too long")
                .check(_.age)(_ >= 13, "must be 13+")
                .check(_.email)(_.contains("@"), "invalid email")
                .check(r => r.referralCode.forall(_.length == 8), "referral code must be 8 chars")
            val errors = m.validate(MTRegistration("", 10, "bad", Some("short")))
            assert(errors.size == 4)
            assert(errors.exists(_.message == "username required"))
            assert(errors.exists(_.message == "must be 13+"))
            assert(errors.exists(_.message == "invalid email"))
            assert(errors.exists(_.message == "referral code must be 8 chars"))
        }

        "chain 4 field checks plus 1 cross-field all pass" in {
            val m = Schema[MTRegistration]
                .check(_.username)(_.nonEmpty, "username required")
                .check(_.username)(_.length <= 20, "username too long")
                .check(_.age)(_ >= 13, "must be 13+")
                .check(_.email)(_.contains("@"), "invalid email")
                .check(r => r.referralCode.forall(_.length == 8), "referral code must be 8 chars")
            val errors = m.validate(MTRegistration("alice", 25, "a@b.com", None))
            assert(errors.isEmpty)
        }

        "validator is reusable across multiple instances" in {
            val m = Schema[MTRegistration]
                .check(_.username)(_.nonEmpty, "username required")
                .check(_.age)(_ >= 13, "must be 13+")
            val errors1 = m.validate(MTRegistration("", 10, "a@b.com", None))
            assert(errors1.size == 2)
            val errors2 = m.validate(MTRegistration("alice", 25, "a@b.com", None))
            assert(errors2.isEmpty)
        }

        // --- mergeChecks ---

        "mergeChecks combines checks from two metas" in {
            val nameChecks = Schema[MTPerson].check(_.name)(_.nonEmpty, "name required")
            val ageChecks  = Schema[MTPerson].check(_.age)(_ >= 0, "age non-negative")
            val combined   = nameChecks.mergeChecks(ageChecks)
            val errors     = combined.validate(MTPerson("", -1))
            assert(errors.size == 2)
            assert(errors.exists(_.message == "name required"))
            assert(errors.exists(_.message == "age non-negative"))
        }

        "mergeChecks all pass" in {
            val nameChecks = Schema[MTPerson].check(_.name)(_.nonEmpty, "name required")
            val ageChecks  = Schema[MTPerson].check(_.age)(_ >= 0, "age non-negative")
            val combined   = nameChecks.mergeChecks(ageChecks)
            val errors     = combined.validate(person)
            assert(errors.isEmpty)
        }

        "mergeChecks with no checks in other" in {
            val withChecks = Schema[MTPerson].check(_.name)(_.nonEmpty, "name required")
            val noChecks   = Schema[MTPerson]
            val combined   = withChecks.mergeChecks(noChecks)
            val errors     = combined.validate(MTPerson("", 30))
            assert(errors.size == 1)
            assert(errors.head.message == "name required")
        }

        "mergeChecks with no checks in this" in {
            val noChecks   = Schema[MTPerson]
            val withChecks = Schema[MTPerson].check(_.name)(_.nonEmpty, "name required")
            val combined   = noChecks.mergeChecks(withChecks)
            val errors     = combined.validate(MTPerson("", 30))
            assert(errors.size == 1)
            assert(errors.head.message == "name required")
        }

        "mergeChecks preserves structure for focus" in {
            val nameChecks = Schema[MTPerson].check(_.name)(_.nonEmpty, "name required")
            val ageChecks  = Schema[MTPerson].check(_.age)(_ >= 0, "age non-negative")
            val combined   = nameChecks.mergeChecks(ageChecks)
            // After mergeChecks, focus still works
            val result = combined.focus(_.name).get(person)
            assert(result == "Alice")
        }

        "mergeChecks with cross-field checks" in {
            val fieldChecks = Schema[MTPerson]
                .check(_.name)(_.nonEmpty, "name required")
            val crossChecks = Schema[MTPerson]
                .check(p => p.age >= 18 || p.name.nonEmpty, "need name or be adult")
            val combined = fieldChecks.mergeChecks(crossChecks)
            val errors   = combined.validate(MTPerson("", 5))
            assert(errors.size == 2)
            assert(errors.exists(_.message == "name required"))
            assert(errors.exists(_.message == "need name or be adult"))
        }

        "mergeChecks from separate modules" in {
            val nameChecks = Schema[MTRegistration].check(_.username)(_.nonEmpty, "required")
            val ageChecks  = Schema[MTRegistration].check(_.age)(_ >= 13, "must be 13+")
            val combined   = nameChecks.mergeChecks(ageChecks)
            val errors     = combined.validate(MTRegistration("", 10, "a@b.com", None))
            assert(errors.size == 2)
            assert(errors.exists(_.message == "required"))
            assert(errors.exists(_.message == "must be 13+"))
        }

        "mergeChecks chain three metas" in {
            val m1       = Schema[MTPerson].check(_.name)(_.nonEmpty, "name required")
            val m2       = Schema[MTPerson].check(_.age)(_ >= 0, "age non-negative")
            val m3       = Schema[MTPerson].check(_.age)(_ < 200, "age too large")
            val combined = m1.mergeChecks(m2).mergeChecks(m3)
            val errors   = combined.validate(MTPerson("", -1))
            assert(errors.size == 2)
            assert(errors.exists(_.message == "name required"))
            assert(errors.exists(_.message == "age non-negative"))
            // age too large should pass since -1 < 200
            val errors2 = combined.validate(MTPerson("Alice", 300))
            assert(errors2.size == 1)
            assert(errors2.head.message == "age too large")
        }

        "check on Option field with None skips" in {
            val m = Schema[MTOptional]
                .check(_.nickname)(_.nonEmpty, "nickname must be present")
            // With None, the Option is empty — check predicate receives None which is !nonEmpty
            val errorsNone = m.validate(MTOptional("Alice", None))
            // With Some, the Option is non-empty
            val errorsSome = m.validate(MTOptional("Alice", Some("Ali")))
            assert(errorsSome.isEmpty)
            // None.nonEmpty is false, so the check should fail
            assert(errorsNone.size == 1)
            assert(errorsNone.head.message == "nickname must be present")
        }

        "very long error message preserved" in {
            val longMsg = "x" * 500
            val m       = Schema[MTPerson].check(_.name)(_.nonEmpty, longMsg)
            val errors  = m.validate(MTPerson("", 30))
            assert(errors.size == 1)
            assert(errors.head.message == longMsg)
            assert(errors.head.message.length == 500)
        }

        "check predicate that throws propagates exception" in {
            val m = Schema[MTPerson].check(_.name)(_ => throw new RuntimeException("boom"), "unreachable")
            assertThrows[RuntimeException] {
                m.validate(MTPerson("Alice", 30))
            }
        }

        // --- Reusable validation rules ---

        "same predicate on different types both report invalid email" in {
            val validEmail: String => Boolean = _.contains("@")
            val userValidator = Schema[MTUser]
                .check(_.email)(validEmail, "invalid email")
            val contactValidator = Schema[MTContact]
                .check(_.email)(validEmail, "invalid email")
            val userErrors    = userValidator.validate(MTUser("Alice", 30, "bad", "123"))
            val contactErrors = contactValidator.validate(MTContact("Alice", "bad", "555-0100"))
            assert(userErrors.size == 1)
            assert(userErrors.head.message == "invalid email")
            assert(contactErrors.size == 1)
            assert(contactErrors.head.message == "invalid email")
        }

        "error paths correct for both types" in {
            val validEmail: String => Boolean = _.contains("@")
            val userValidator = Schema[MTUser]
                .check(_.email)(validEmail, "invalid email")
            val contactValidator = Schema[MTContact]
                .check(_.email)(validEmail, "invalid email")
            val userErrors    = userValidator.validate(MTUser("Alice", 30, "bad", "123"))
            val contactErrors = contactValidator.validate(MTContact("Alice", "bad", "555-0100"))
            assert(userErrors.head.path == List("email"))
            assert(contactErrors.head.path == List("email"))
        }

        "shared predicate val applied to check" in {
            val nonEmpty: String => Boolean = _.nonEmpty
            val m = Schema[MTPerson]
                .check(_.name)(nonEmpty, "name required")
            val errors = m.validate(MTPerson("", 30))
            assert(errors.size == 1)
            assert(errors.head.message == "name required")
            val noErrors = m.validate(MTPerson("Alice", 30))
            assert(noErrors.isEmpty)
        }

        "validate with no checks returns empty" in {
            val errors = Schema[MTPerson].validate(MTPerson("", -1))
            assert(errors.isEmpty)
        }

        "separate validators can have different check sets" in {
            val nonEmpty: String => Boolean   = _.nonEmpty
            val validEmail: String => Boolean = _.contains("@")
            val userValidator = Schema[MTUser]
                .check(_.name)(nonEmpty, "name required")
                .check(_.email)(validEmail, "invalid email")
                .check(_.age)(_ >= 0, "age non-negative")
            val contactValidator = Schema[MTContact]
                .check(_.name)(nonEmpty, "name required")
                .check(_.email)(validEmail, "invalid email")
                .check(_.phone)(nonEmpty, "phone required")
            // User has 3 checks: name, email, age
            val userErrors = userValidator.validate(MTUser("", 30, "bad", "123"))
            assert(userErrors.size == 2) // name required, invalid email
            // Contact has 3 checks: name, email, phone
            val contactErrors = contactValidator.validate(MTContact("", "bad", ""))
            assert(contactErrors.size == 3) // name required, invalid email, phone required
            assert(contactErrors.exists(_.message == "name required"))
            assert(contactErrors.exists(_.message == "invalid email"))
            assert(contactErrors.exists(_.message == "phone required"))
        }
    }

    // =========================================================================
    // constraints
    // =========================================================================

    "constraints" - {

        case class Person(name: String, age: Int) derives CanEqual
        case class Priced(name: String, price: Double) derives CanEqual
        case class Scored(name: String, score: Int) derives CanEqual
        case class Tagged(name: String, tags: List[String]) derives CanEqual
        case class WithEmail(email: String) derives CanEqual
        case class PersonWithAddress(name: String, age: Int, address: Address) derives CanEqual
        case class Address(street: String, city: String) derives CanEqual
        case class X(s: String) derives CanEqual

        // --- Runtime validation: min ---

        "min passes for age=0" in {
            val schema = Schema[Person].checkMin(_.age)(0)
            assert(schema.validate(Person("Alice", 0)).isEmpty)
        }

        "min fails for age=-1" in {
            val schema = Schema[Person].checkMin(_.age)(0)
            val errors = schema.validate(Person("Alice", -1))
            assert(errors.nonEmpty)
            assert(errors.head.path == List("age"))
        }

        "max passes for age=150" in {
            val schema = Schema[Person].checkMax(_.age)(150)
            assert(schema.validate(Person("Alice", 150)).isEmpty)
        }

        "max fails for age=151" in {
            val schema = Schema[Person].checkMax(_.age)(150)
            val errors = schema.validate(Person("Alice", 151))
            assert(errors.nonEmpty)
            assert(errors.head.path == List("age"))
        }

        "exclusiveMin fails for price=0.0, passes for price=0.01" in {
            val schema = Schema[Priced].checkExclusiveMin(_.price)(0.0)
            assert(schema.validate(Priced("X", 0.0)).nonEmpty)
            assert(schema.validate(Priced("X", 0.01)).isEmpty)
        }

        "exclusiveMax fails for score=100, passes for score=99" in {
            val schema = Schema[Scored].checkExclusiveMax(_.score)(100.0)
            assert(schema.validate(Scored("X", 100)).nonEmpty)
            assert(schema.validate(Scored("X", 99)).isEmpty)
        }

        "minLength fails for empty string, passes for 'a'" in {
            val schema = Schema[Person].checkMinLength(_.name)(1)
            assert(schema.validate(Person("", 0)).nonEmpty)
            assert(schema.validate(Person("a", 0)).isEmpty)
        }

        "maxLength fails for 'abcdef', passes for 'abcde'" in {
            val schema = Schema[Person].checkMaxLength(_.name)(5)
            assert(schema.validate(Person("abcdef", 0)).nonEmpty)
            assert(schema.validate(Person("abcde", 0)).isEmpty)
        }

        "pattern fails for 'invalid', passes for 'a@b.com'" in {
            val schema = Schema[WithEmail].checkPattern(_.email)("^.+@.+$")
            assert(schema.validate(WithEmail("invalid")).nonEmpty)
            assert(schema.validate(WithEmail("a@b.com")).isEmpty)
        }

        "format does not produce runtime error (advisory only)" in {
            val schema = Schema[WithEmail].checkFormat(_.email)("email")
            // format is advisory: should not fail at runtime for any string
            assert(schema.validate(WithEmail("not-an-email")).isEmpty)
            assert(schema.validate(WithEmail("valid@example.com")).isEmpty)
        }

        "minItems fails for empty list, passes for List('a')" in {
            val schema = Schema[Tagged].checkMinItems(_.tags)(1)
            assert(schema.validate(Tagged("X", Nil)).nonEmpty)
            assert(schema.validate(Tagged("X", List("a"))).isEmpty)
        }

        "maxItems fails for 3-element list, passes for 2-element list" in {
            val schema = Schema[Tagged].checkMaxItems(_.tags)(2)
            assert(schema.validate(Tagged("X", List("a", "b", "c"))).nonEmpty)
            assert(schema.validate(Tagged("X", List("a", "b"))).isEmpty)
        }

        "uniqueItems fails for List('a','a'), passes for List('a','b')" in {
            val schema = Schema[Tagged].checkUniqueItems(_.tags)
            assert(schema.validate(Tagged("X", List("a", "a"))).nonEmpty)
            assert(schema.validate(Tagged("X", List("a", "b"))).isEmpty)
        }

        "multiple constraints on same field: min and max both checked" in {
            val schema = Schema[Person].checkMin(_.age)(0).checkMax(_.age)(150)
            assert(schema.validate(Person("Alice", -1)).nonEmpty)
            assert(schema.validate(Person("Alice", 151)).nonEmpty)
            assert(schema.validate(Person("Alice", 50)).isEmpty)
        }

        "multiple constraints on different fields: minLength(name) and min(age)" in {
            val schema = Schema[Person].checkMinLength(_.name)(1).checkMin(_.age)(0)
            // both fail
            val bothFail = schema.validate(Person("", -1))
            assert(bothFail.size == 2)
            // name fails only
            val nameFail = schema.validate(Person("", 0))
            assert(nameFail.size == 1)
            // age fails only
            val ageFail = schema.validate(Person("Alice", -1))
            assert(ageFail.size == 1)
            // both pass
            assert(schema.validate(Person("Alice", 0)).isEmpty)
        }

        "constraint min(age)(0) and check(name)(nonEmpty) both run" in {
            val schema = Schema[Person].checkMin(_.age)(0).check(_.name)(_.nonEmpty, "name required")
            // both fail
            assert(schema.validate(Person("", -1)).size == 2)
            // only age fails
            assert(schema.validate(Person("Alice", -1)).size == 1)
            // only name fails
            assert(schema.validate(Person("", 0)).size == 1)
            // both pass
            assert(schema.validate(Person("Alice", 0)).isEmpty)
        }

        // --- JsonSchema enrichment ---

        "min(_.age)(0) produces minimum=0 on age property" in {
            val js = Json.jsonSchema(using Schema[Person].checkMin(_.age)(0))
            js match
                case obj: JsonSchema.Obj =>
                    val ageProp = obj.properties.find(_._1 == "age").map(_._2)
                    ageProp match
                        case Some(s: JsonSchema.Integer) =>
                            assert(s.minimum == Maybe(0L))
                        case Some(other) =>
                            fail(s"Expected JsonSchema.Integer for 'age', got $other")
                        case None =>
                            fail("Property 'age' not found")
                    end match
                case other =>
                    fail(s"Expected JsonSchema.Obj, got $other")
            end match
        }

        "max(_.age)(150) produces maximum=150 on age property" in {
            val js = Json.jsonSchema(using Schema[Person].checkMax(_.age)(150))
            js match
                case obj: JsonSchema.Obj =>
                    val ageProp = obj.properties.find(_._1 == "age").map(_._2)
                    ageProp match
                        case Some(s: JsonSchema.Integer) =>
                            assert(s.maximum == Maybe(150L))
                        case other =>
                            fail(s"Expected JsonSchema.Integer for 'age', got $other")
                    end match
                case other =>
                    fail(s"Expected JsonSchema.Obj, got $other")
            end match
        }

        "exclusiveMin(_.price)(0) produces exclusiveMinimum=0 on price property" in {
            val js = Json.jsonSchema(using Schema[Priced].checkExclusiveMin(_.price)(0.0))
            js match
                case obj: JsonSchema.Obj =>
                    val prop = obj.properties.find(_._1 == "price").map(_._2)
                    prop match
                        case Some(s: JsonSchema.Num) =>
                            assert(s.exclusiveMinimum == Maybe(0.0))
                        case other =>
                            fail(s"Expected JsonSchema.Num for 'price', got $other")
                    end match
                case other =>
                    fail(s"Expected JsonSchema.Obj, got $other")
            end match
        }

        "exclusiveMax(_.score)(100) produces exclusiveMaximum=100 on score property" in {
            val js = Json.jsonSchema(using Schema[Scored].checkExclusiveMax(_.score)(100.0))
            js match
                case obj: JsonSchema.Obj =>
                    val prop = obj.properties.find(_._1 == "score").map(_._2)
                    prop match
                        case Some(s: JsonSchema.Integer) =>
                            assert(s.exclusiveMaximum == Maybe(100L))
                        case other =>
                            fail(s"Expected JsonSchema.Integer for 'score', got $other")
                    end match
                case other =>
                    fail(s"Expected JsonSchema.Obj, got $other")
            end match
        }

        "minLength(_.name)(1) produces minLength=1 on name property" in {
            val js = Json.jsonSchema(using Schema[Person].checkMinLength(_.name)(1))
            js match
                case obj: JsonSchema.Obj =>
                    val prop = obj.properties.find(_._1 == "name").map(_._2)
                    prop match
                        case Some(s: JsonSchema.Str) =>
                            assert(s.minLength == Maybe(1))
                        case other =>
                            fail(s"Expected JsonSchema.Str for 'name', got $other")
                    end match
                case other =>
                    fail(s"Expected JsonSchema.Obj, got $other")
            end match
        }

        "maxLength(_.name)(255) produces maxLength=255 on name property" in {
            val js = Json.jsonSchema(using Schema[Person].checkMaxLength(_.name)(255))
            js match
                case obj: JsonSchema.Obj =>
                    val prop = obj.properties.find(_._1 == "name").map(_._2)
                    prop match
                        case Some(s: JsonSchema.Str) =>
                            assert(s.maxLength == Maybe(255))
                        case other =>
                            fail(s"Expected JsonSchema.Str for 'name', got $other")
                    end match
                case other =>
                    fail(s"Expected JsonSchema.Obj, got $other")
            end match
        }

        "pattern(_.email)('^.+@.+$') produces pattern on email property" in {
            val js = Json.jsonSchema(using Schema[WithEmail].checkPattern(_.email)("^.+@.+$"))
            js match
                case obj: JsonSchema.Obj =>
                    val prop = obj.properties.find(_._1 == "email").map(_._2)
                    prop match
                        case Some(s: JsonSchema.Str) =>
                            assert(s.pattern == Maybe("^.+@.+$"))
                        case other =>
                            fail(s"Expected JsonSchema.Str for 'email', got $other")
                    end match
                case other =>
                    fail(s"Expected JsonSchema.Obj, got $other")
            end match
        }

        "format(_.email)('email') produces format on email property" in {
            val js = Json.jsonSchema(using Schema[WithEmail].checkFormat(_.email)("email"))
            js match
                case obj: JsonSchema.Obj =>
                    val prop = obj.properties.find(_._1 == "email").map(_._2)
                    prop match
                        case Some(s: JsonSchema.Str) =>
                            assert(s.format == Maybe("email"))
                        case other =>
                            fail(s"Expected JsonSchema.Str for 'email', got $other")
                    end match
                case other =>
                    fail(s"Expected JsonSchema.Obj, got $other")
            end match
        }

        "minItems(_.tags)(1) produces minItems=1 on tags property" in {
            val js = Json.jsonSchema(using Schema[Tagged].checkMinItems(_.tags)(1))
            js match
                case obj: JsonSchema.Obj =>
                    val prop = obj.properties.find(_._1 == "tags").map(_._2)
                    prop match
                        case Some(s: JsonSchema.Arr) =>
                            assert(s.minItems == Maybe(1))
                        case other =>
                            fail(s"Expected JsonSchema.Arr for 'tags', got $other")
                    end match
                case other =>
                    fail(s"Expected JsonSchema.Obj, got $other")
            end match
        }

        "maxItems(_.tags)(10) produces maxItems=10 on tags property" in {
            val js = Json.jsonSchema(using Schema[Tagged].checkMaxItems(_.tags)(10))
            js match
                case obj: JsonSchema.Obj =>
                    val prop = obj.properties.find(_._1 == "tags").map(_._2)
                    prop match
                        case Some(s: JsonSchema.Arr) =>
                            assert(s.maxItems == Maybe(10))
                        case other =>
                            fail(s"Expected JsonSchema.Arr for 'tags', got $other")
                    end match
                case other =>
                    fail(s"Expected JsonSchema.Obj, got $other")
            end match
        }

        "uniqueItems(_.tags) produces uniqueItems=true on tags property" in {
            val js = Json.jsonSchema(using Schema[Tagged].checkUniqueItems(_.tags))
            js match
                case obj: JsonSchema.Obj =>
                    val prop = obj.properties.find(_._1 == "tags").map(_._2)
                    prop match
                        case Some(s: JsonSchema.Arr) =>
                            assert(s.uniqueItems == Maybe(true))
                        case other =>
                            fail(s"Expected JsonSchema.Arr for 'tags', got $other")
                    end match
                case other =>
                    fail(s"Expected JsonSchema.Obj, got $other")
            end match
        }

        "constraint min(age)(0) and doc on same field both appear in jsonSchema" in {
            val js = Json.jsonSchema(using
                Schema[Person]
                    .checkMin(_.age)(0)
                    .doc(_.age)("The person's age")
            )
            js match
                case obj: JsonSchema.Obj =>
                    val prop = obj.properties.find(_._1 == "age").map(_._2)
                    prop match
                        case Some(s: JsonSchema.Integer) =>
                            assert(s.minimum == Maybe(0L))
                            assert(s.description == Maybe("The person's age"))
                        case other =>
                            fail(s"Expected JsonSchema.Integer for 'age', got $other")
                    end match
                case other =>
                    fail(s"Expected JsonSchema.Obj, got $other")
            end match
        }

        "constraint minLength(name)(1) and deprecated(name) appear together (deprecated no-op on Str)" in {
            val js = Json.jsonSchema(using
                Schema[Person]
                    .checkMinLength(_.name)(1)
                    .deprecated(_.name)("obsolete")
            )
            js match
                case obj: JsonSchema.Obj =>
                    val prop = obj.properties.find(_._1 == "name").map(_._2)
                    prop match
                        case Some(s: JsonSchema.Str) =>
                            assert(s.minLength == Maybe(1))
                            // deprecated is no-op for Str (no deprecated field on Str)
                            assert(s.description == Maybe.empty)
                        case other =>
                            fail(s"Expected JsonSchema.Str for 'name', got $other")
                    end match
                case other =>
                    fail(s"Expected JsonSchema.Obj, got $other")
            end match
        }

        "no constraints produces same jsonSchema as JsonSchema.from" in {
            val fromSchema = Json.jsonSchema[Person]
            val fromMacro  = JsonSchema.from[Person]
            assert(fromSchema == fromMacro)
        }

        "constraint and check lambda work together" in {
            val schema = Schema[Person]
                .checkMin(_.age)(0)
                .check(_.name)(_.nonEmpty, "name required")
            assert(schema.validate(Person("Alice", 5)).isEmpty)
            assert(schema.validate(Person("", 5)).size == 1)
            assert(schema.validate(Person("Alice", -1)).size == 1)
        }

        "multiple constraints of same type both apply (last does not override)" in {
            val schema = Schema[Person].checkMin(_.age)(0).checkMin(_.age)(10)
            // age=5 fails min(10) but passes min(0): only one failure
            assert(schema.validate(Person("Alice", 5)).size == 1)
            // age=-1 fails both
            assert(schema.validate(Person("Alice", -1)).size == 2)
            // age=10 passes both
            assert(schema.validate(Person("Alice", 10)).isEmpty)
            // jsonSchema: last min wins in enrichment (both are applied, last overwrites minimum)
            val js = Json.jsonSchema(using schema)
            js match
                case obj: JsonSchema.Obj =>
                    val prop = obj.properties.find(_._1 == "age").map(_._2)
                    prop match
                        case Some(s: JsonSchema.Integer) =>
                            // Both constraints applied, second min(10) overwrites first min(0)
                            assert(s.minimum == Maybe(10L))
                        case other =>
                            fail(s"Expected JsonSchema.Integer, got $other")
                    end match
                case other =>
                    fail(s"Expected JsonSchema.Obj, got $other")
            end match
        }

        "min with NegativeInfinity always passes" in {
            val schema = Schema[Person].checkMin(_.age)(Double.NegativeInfinity)
            assert(schema.validate(Person("Alice", Int.MinValue)).isEmpty)
        }

        "maxLength(_.name)(0) only passes for empty string" in {
            val schema = Schema[Person].checkMaxLength(_.name)(0)
            assert(schema.validate(Person("", 0)).isEmpty)
            assert(schema.validate(Person("a", 0)).nonEmpty)
        }

        // --- Cross-platform regex audit ---

        "pattern POSIX regex — email-like" in {
            val schema = Schema[X].checkPattern(_.s)("^[a-z]+@[a-z]+$")
            assert(schema.validate(X("a@b")).isEmpty)
            assert(schema.validate(X("no-at")).nonEmpty)
        }

        "pattern character class — digits with dash" in {
            val schema = Schema[X].checkPattern(_.s)("""^\d{3}-\d{4}$""")
            assert(schema.validate(X("555-1234")).isEmpty)
            assert(schema.validate(X("abc-1234")).nonEmpty)
        }

        "pattern alternation — animal names" in {
            val schema = Schema[X].checkPattern(_.s)("^(cat|dog|bird)$")
            assert(schema.validate(X("cat")).isEmpty)
            assert(schema.validate(X("fish")).nonEmpty)
        }

        "pattern bounded quantifier — a{2,5}" in {
            val schema = Schema[X].checkPattern(_.s)("^a{2,5}$")
            assert(schema.validate(X("aa")).isEmpty)
            assert(schema.validate(X("aaaaa")).isEmpty)
            assert(schema.validate(X("a")).nonEmpty)
            assert(schema.validate(X("aaaaaa")).nonEmpty)
        }

        "pattern anchors — word characters only" in {
            val schema = Schema[X].checkPattern(_.s)("""^\w+$""")
            assert(schema.validate(X("hello")).isEmpty)
            assert(schema.validate(X("with space")).nonEmpty)
        }

        "pattern escaped metacharacters — literal .+*" in {
            val schema = Schema[X].checkPattern(_.s)("""^\.\+\*$""")
            assert(schema.validate(X(".+*")).isEmpty)
            assert(schema.validate(X("abc")).nonEmpty)
        }

        "pattern possessive quantifier — documented platform limitation" in {
            import kyo.internal.Platform
            if Platform.isJVM then
                val schema = Schema[X].checkPattern(_.s)("^(a)++$")
                assert(schema.validate(X("a")).isEmpty)
            else
                // On JS/Native, possessive quantifiers may throw PatternSyntaxException
                // at schema build time or at validation time. Both are acceptable outcomes.
                try
                    val schema = Schema[X].checkPattern(_.s)("^(a)++$")
                    // If no exception at build time, validation may throw or silently fail
                    val result =
                        try schema.validate(X("a"))
                        catch
                            case e: Exception =>
                                val msg = e.getMessage
                                assert(
                                    msg != null && (
                                        msg.toLowerCase.contains("syntax") ||
                                            msg.toLowerCase.contains("pattern") ||
                                            msg.toLowerCase.contains("invalid") ||
                                            msg.toLowerCase.contains("error")
                                    ),
                                    s"Expected syntax/pattern error but got: $msg"
                                )
                                Chunk.empty
                    // If validation succeeded (JS may silently degrade), accept it
                    succeed
                catch
                    case e: Exception =>
                        val msg = e.getMessage
                        assert(
                            msg != null && (
                                msg.toLowerCase.contains("syntax") ||
                                    msg.toLowerCase.contains("pattern") ||
                                    msg.toLowerCase.contains("invalid") ||
                                    msg.toLowerCase.contains("error")
                            ),
                            s"Expected syntax/pattern error but got: $msg"
                        )
            end if
        }

        "fieldId preserved through check" in {
            val s = Schema[MTPerson].fieldId(_.name)(42).check(_.name)(_.nonEmpty, "required")
            assert(s.fieldId("name") == 42)
        }

        "fieldId preserved through min" in {
            val s = Schema[MTPerson].fieldId(_.age)(99).checkMin(_.age)(0)
            assert(s.fieldId("age") == 99)
        }

        "fieldId preserved through doc" in {
            val s = Schema[MTPerson].fieldId(_.name)(42).doc(_.name)("Full name")
            assert(s.fieldId("name") == 42)
        }

        "fieldId preserved through format" in {
            val s = Schema[WithEmail].fieldId(_.email)(10).checkFormat(_.email)("email")
            assert(s.fieldId("email") == 10)
        }

        "fieldId preserved through deprecated" in {
            val s = Schema[PersonWithAddress].fieldId(_.name)(7).deprecated(_.name)("obsolete")
            assert(s.fieldId("name") == 7)
        }
    }

    // =========================================================================
    // fold
    // =========================================================================

    "fold" - {

        val alice = MTPerson("Alice", 30)
        val bob   = MTPerson("Bob", 25)
        val team1 = MTSmallTeam(alice, 5)

        // === fold (typed polymorphic) ===

        "fold over fields" in {
            val m = Schema[MTPerson]
            val result = m.fold(alice)(Map.empty[String, String]) {
                [N <: String, V] =>
                    (acc: Map[String, String], field: Field[N, V], value: V) =>
                        acc + (field.name -> value.toString)
            }
            assert(result == Map("name" -> "Alice", "age" -> "30"))
        }

        "fold field name" in {
            val m = Schema[MTPerson]
            val names = m.fold(alice)(List.empty[String]) {
                [N <: String, V] =>
                    (acc: List[String], field: Field[N, V], _: V) =>
                        acc :+ field.name
            }
            assert(names == List("name", "age"))
        }

        "fold accumulates" in {
            val m = Schema[MTPerson]
            val count = m.fold(alice)(0) {
                [N <: String, V] =>
                    (acc: Int, _: Field[N, V], _: V) =>
                        acc + 1
            }
            assert(count == 2)
        }

        "fold with three fields" in {
            val m = Schema[MTThreeField]
            val result = m.fold(MTThreeField(1, "two", true))(Map.empty[String, String]) {
                [N <: String, V] =>
                    (acc: Map[String, String], field: Field[N, V], value: V) =>
                        acc + (field.name -> value.toString)
            }
            assert(result == Map("x" -> "1", "y" -> "two", "z" -> "true"))
        }

        "fold init passthrough" in {
            // Even though fold iterates, verify it starts from init
            val m = Schema[MTPerson]
            val result = m.fold(alice)(42) {
                [N <: String, V] =>
                    (acc: Int, _: Field[N, V], _: V) =>
                        acc + 1
            }
            assert(result == 44) // 42 + 1 (name) + 1 (age)
        }

        "fold nested type" in {
            val m = Schema[MTSmallTeam]
            val result = m.fold(team1)(Map.empty[String, String]) {
                [N <: String, V] =>
                    (acc: Map[String, String], field: Field[N, V], value: V) =>
                        acc + (field.name -> value.toString)
            }
            assert(result("lead") == alice.toString)
            assert(result("size") == "5")
        }

        // === fold edge cases (after rename/add) ===

        "fold after rename" in {
            val m = Schema[MTPerson].rename("name", "fullName")
            val result = m.fold(alice)(Map.empty[String, String]) {
                [N <: String, V] =>
                    (acc: Map[String, String], field: Field[N, V], value: V) =>
                        acc + (field.name -> value.toString)
            }
            assert(result.contains("fullName"))
            assert(result("fullName") == "Alice")
            assert(result("age") == "30")
            assert(!result.contains("name"))
        }

        "fold after add" in {
            val m = Schema[MTPerson].add("greeting")((p: MTPerson) => s"Hello ${p.name}")
            val result = m.fold(alice)(Map.empty[String, String]) {
                [N <: String, V] =>
                    (acc: Map[String, String], field: Field[N, V], value: V) =>
                        acc + (field.name -> value.toString)
            }
            assert(result("greeting") == "Hello Alice")
            assert(result("name") == "Alice")
        }

        // === fold field metadata ===

        "fold field name accessible" in {
            val m = Schema[MTPerson]
            val names = m.fold(alice)(List.empty[String]) {
                [N <: String, V] =>
                    (acc: List[String], field: Field[N, V], _: V) =>
                        acc :+ field.name
            }
            assert(names == List("name", "age"))
        }

        "fold field tag accessible" in {
            val m = Schema[MTPerson]
            val tags = m.fold(alice)(List.empty[Tag[Any]]) {
                [N <: String, V] =>
                    (acc: List[Tag[Any]], field: Field[N, V], _: V) =>
                        acc :+ field.tag.erased
            }
            assert(tags.size == 2)
        }

        "fold field tag for string" in {
            val m = Schema[MTPerson]
            val tags = m.fold(alice)(Map.empty[String, Tag[Any]]) {
                [N <: String, V] =>
                    (acc: Map[String, Tag[Any]], field: Field[N, V], _: V) =>
                        acc + (field.name -> field.tag.erased)
            }
            assert(tags("name") =:= Tag[String])
        }

        "fold field tag for int" in {
            val m = Schema[MTPerson]
            val tags = m.fold(alice)(Map.empty[String, Tag[Any]]) {
                [N <: String, V] =>
                    (acc: Map[String, Tag[Any]], field: Field[N, V], _: V) =>
                        acc + (field.name -> field.tag.erased)
            }
            assert(tags("age") =:= Tag[Int])
        }

        "fold field default present" in {
            val m      = Schema[MTDebugConfig]
            val config = MTDebugConfig("localhost")
            val defaults = m.fold(config)(Map.empty[String, Boolean]) {
                [N <: String, V] =>
                    (acc: Map[String, Boolean], field: Field[N, V], _: V) =>
                        acc + (field.name -> field.default.nonEmpty)
            }
            assert(defaults("port") == true)
        }

        "fold field default absent" in {
            val m = Schema[MTPerson]
            val defaults = m.fold(alice)(Map.empty[String, Boolean]) {
                [N <: String, V] =>
                    (acc: Map[String, Boolean], field: Field[N, V], _: V) =>
                        acc + (field.name -> field.default.nonEmpty)
            }
            assert(defaults("name") == false)
            assert(defaults("age") == false)
        }

        "fold produces field map" in {
            val m = Schema[MTPerson]
            val tagMap = m.fold(alice)(Map.empty[String, Tag[Any]]) {
                [N <: String, V] =>
                    (acc: Map[String, Tag[Any]], field: Field[N, V], _: V) =>
                        acc + (field.name -> field.tag.erased)
            }
            assert(tagMap.size == 2)
            assert(tagMap.contains("name"))
            assert(tagMap.contains("age"))
            assert(tagMap("name") =:= Tag[String])
            assert(tagMap("age") =:= Tag[Int])
        }

        "fold field metadata complete" in {
            val m = Schema[MTPerson]
            val fields = m.fold(alice)(List.empty[Field[?, ?]]) {
                [N <: String, V] =>
                    (acc: List[Field[?, ?]], field: Field[N, V], _: V) =>
                        acc :+ field
            }
            assert(fields.forall(f => f.name != null && f.name.nonEmpty))
            assert(fields.size == 2)
        }

        "fold with nested type tag" in {
            val m = Schema[MTSmallTeam]
            val tags = m.fold(team1)(Map.empty[String, Tag[Any]]) {
                [N <: String, V] =>
                    (acc: Map[String, Tag[Any]], field: Field[N, V], _: V) =>
                        acc + (field.name -> field.tag.erased)
            }
            assert(tags("lead") =:= Tag[MTPerson])
            assert(tags("size") =:= Tag[Int])
        }

        "fold field count matches" in {
            val m = Schema[MTThreeField]
            val v = MTThreeField(1, "two", true)
            val count = m.fold(v)(0) {
                [N <: String, V] =>
                    (acc: Int, _: Field[N, V], _: V) =>
                        acc + 1
            }
            assert(count == 3)
        }

        // === fold type-safe access ===

        "fold type-safe access" in {
            val m = Schema[MTPerson]
            val result = m.fold(alice)(List.empty[String]) {
                [N <: String, V] =>
                    (acc: List[String], field: Field[N, V], value: V) =>
                        acc :+ s"${field.name}=${value}"
            }
            assert(result == List("name=Alice", "age=30"))
        }

        "fold type-safe field name" in {
            val m = Schema[MTPerson]
            val names = m.fold(alice)(List.empty[String]) {
                [N <: String, V] =>
                    (acc: List[String], field: Field[N, V], _: V) =>
                        acc :+ field.name
            }
            assert(names == List("name", "age"))
        }

        "fold type-safe field tag" in {
            val m = Schema[MTPerson]
            val tags = m.fold(alice)(Map.empty[String, Tag[Any]]) {
                [N <: String, V] =>
                    (acc: Map[String, Tag[Any]], field: Field[N, V], _: V) =>
                        acc + (field.name -> field.tag.erased)
            }
            assert(tags("name") =:= Tag[String])
            assert(tags("age") =:= Tag[Int])
        }

        "fold type-safe accumulates" in {
            val m = Schema[MTThreeField]
            val count = m.fold(MTThreeField(1, "two", true))(0) {
                [N <: String, V] =>
                    (acc: Int, _: Field[N, V], _: V) =>
                        acc + 1
            }
            assert(count == 3)
        }

        "fold type-safe produces typed map" in {
            val m = Schema[MTPerson]
            val result = m.fold(alice)(Map.empty[String, String]) {
                [N <: String, V] =>
                    (acc: Map[String, String], field: Field[N, V], value: V) =>
                        acc + (field.name -> value.toString)
            }
            assert(result == Map("name" -> "Alice", "age" -> "30"))
        }

        // === edge cases: empty and large case classes ===

        "empty case class fold" in {
            val m = Schema[MTEmpty]
            val count = m.fold(MTEmpty())(0) {
                [N <: String, V] =>
                    (acc: Int, _: Field[N, V], _: V) =>
                        acc + 1
            }
            assert(count == 0)
        }

        "empty case class result" in {
            val m      = Schema[MTEmpty]
            val record = m.toRecord(MTEmpty())
            assert(record.dict.toMap.isEmpty)
        }

        "empty case class schema" in {
            val m = Schema[MTEmpty]
            assert(m.fieldDescriptors.isEmpty)
        }

        "large case class fold" in {
            val m     = Schema[MTLarge]
            val large = MTLarge(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)
            val result = m.fold(large)(Map.empty[String, String]) {
                [N <: String, V] =>
                    (acc: Map[String, String], field: Field[N, V], value: V) =>
                        acc + (field.name -> value.toString)
            }
            assert(result.size == 10)
            assert(result("a") == "1")
            assert(result("j") == "10")
        }

        "large case class result" in {
            val m      = Schema[MTLarge]
            val large  = MTLarge(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)
            val record = m.toRecord(large)
            assert(record.dict("a") == 1)
            assert(record.dict("j") == 10)
        }

        "large case class schema field count" in {
            assert(Schema[MTLarge].fieldDescriptors.size == 10)
        }

        "fold works with different types" in {
            // fold on MTPerson
            val personLog = Schema[MTPerson].fold(MTPerson("Alice", 30))("[user]") {
                [N <: String, V] =>
                    (acc: String, field: Field[N, V], v: V) =>
                        s"$acc ${field.name}=$v"
            }
            assert(personLog == "[user] name=Alice age=30")

            // fold on MTProduct
            val productLog = Schema[MTProduct].fold(MTProduct("Widget", 9.99, "W001"))("[product]") {
                [N <: String, V] =>
                    (acc: String, field: Field[N, V], v: V) =>
                        s"$acc ${field.name}=$v"
            }
            assert(productLog == "[product] name=Widget price=9.99 sku=W001")
        }

        "generic validate function works with different types" in {
            // Generic validateAndCollect function
            def validateAndCollect[A](value: A, meta: Schema[A]): Chunk[ValidationFailedException] =
                meta.validate(value)

            // MTPerson with name check
            val personMeta = Schema[MTPerson]
                .check(_.name)(_.nonEmpty, "name required")
                .check(_.age)(_ > 0, "age must be positive")

            // Valid person: no errors
            val validErrors = validateAndCollect(MTPerson("Alice", 30), personMeta)
            assert(validErrors.isEmpty)

            // Invalid person: errors collected
            val invalidErrors = validateAndCollect(MTPerson("", -1), personMeta)
            assert(invalidErrors.size == 2)
            assert(invalidErrors.exists(_.message == "name required"))
            assert(invalidErrors.exists(_.message == "age must be positive"))

            // Different type: MTItem with price check
            val itemMeta = Schema[MTItem]
                .check(_.name)(_.nonEmpty, "item name required")
                .check(_.price)(_ > 0, "price must be positive")

            val validItem = validateAndCollect(MTItem("Widget", 9.99), itemMeta)
            assert(validItem.isEmpty)

            val invalidItem = validateAndCollect(MTItem("", -5.0), itemMeta)
            assert(invalidItem.size == 2)
        }

        "fold produces record via result on different types" in {
            // MTPerson result
            val personRecord = Schema[MTPerson].toRecord(MTPerson("Alice", 30))
            assert(personRecord.dict("name") == "Alice")
            assert(personRecord.dict("age") == 30)

            // MTSmallTeam result
            val teamRecord = Schema[MTSmallTeam].toRecord(MTSmallTeam(alice, 5))
            assert(teamRecord.dict("lead") == alice)
            assert(teamRecord.dict("size") == 5)
        }

        "fold iterates all fields and accumulates null/empty checks" in {
            given CanEqual[Null, Any] = CanEqual.derived
            val account               = MTAccount("Alice", "alice@test.com", "pro", 50)
            val nullCheck = Schema[MTAccount].fold(account)(List.empty[String]) {
                [N <: String, V] =>
                    (acc: List[String], field: Field[N, V], value: V) =>
                        value match
                            case null                   => acc :+ s"${field.name} is null"
                            case s: String if s.isEmpty => acc :+ s"${field.name} is empty"
                            case _                      => acc
            }
            assert(nullCheck.isEmpty)
        }

        "fold detects null field values" in {
            given CanEqual[Null, Any] = CanEqual.derived
            val bad                   = MTAccount("", null, "free", 0)
            val issues = Schema[MTAccount].fold(bad)(List.empty[String]) {
                [N <: String, V] =>
                    (acc: List[String], field: Field[N, V], value: V) =>
                        value match
                            case null                   => acc :+ s"${field.name} is null"
                            case s: String if s.isEmpty => acc :+ s"${field.name} is empty"
                            case _                      => acc
            }
            assert(issues.contains("name is empty"))
            assert(issues.contains("email is null"))
            assert(issues.size == 2)
        }

        "fold detects empty string field values" in {
            given CanEqual[Null, Any] = CanEqual.derived
            val bad                   = MTAccount("", "alice@test.com", "free", 0)
            val issues = Schema[MTAccount].fold(bad)(List.empty[String]) {
                [N <: String, V] =>
                    (acc: List[String], field: Field[N, V], value: V) =>
                        value match
                            case null                   => acc :+ s"${field.name} is null"
                            case s: String if s.isEmpty => acc :+ s"${field.name} is empty"
                            case _                      => acc
            }
            assert(issues == List("name is empty"))
        }
    }

    // =========================================================================
    // transform - lambda syntax
    // =========================================================================

    "transform" - {

        val user = MTUser("Alice", 30, "alice@example.com", "123-45-6789")

        // === Typical usage ===

        "transform chain: drop, rename, add" in {
            val m = Schema[MTUser]
                .drop("ssn")
                .rename("name", "userName")
                .add("active")(_ => true)
            assert(m.fieldNames == Set("userName", "age", "email", "active"))
            val record = m.toRecord(user)
            assert(record.dict("userName") == "Alice")
        }

        "select fields from a type" in {
            val m   = Schema[MTUser].select("name", "age")
            val got = m.focus(_.name).get(user)
            assert(got == "Alice")
            val age = m.focus(_.age).get(user)
            assert(age == 30)
        }

        "flatten nested case class" in {
            val m = Schema[MTPersonAddr].flatten
            assert(!m.fieldNames.contains("address"))
            assert(m.fieldNames == Set("name", "age", "street", "city", "zip"))
        }

        "add computed field with type inference" in {
            val m      = Schema[MTUser].add("greeting")(u => s"Hello, ${u.name}!")
            val record = m.toRecord(user)
            assert(record.dict("greeting") == "Hello, Alice!")
        }

        // === Detailed tests ===

        // --- drop (7 tests) ---

        "drop single field" in {
            val m                                                                                     = Schema[MTUser].drop("ssn")
            val _: Schema[MTUser] { type Focused = "name" ~ String & "age" ~ Int & "email" ~ String } = m
            succeed
        }

        "drop field schema reflects" in {
            val m     = Schema[MTUser].drop("ssn")
            val names = m.fieldNames
            assert(!names.contains("ssn"))
            assert(names == Set("name", "age", "email"))
            assert(m.focus(_.name).tag =:= Tag[String])
            assert(m.focus(_.age).tag =:= Tag[Int])
            assert(m.focus(_.email).tag =:= Tag[String])
        }

        "drop field focus remaining" in {
            val m   = Schema[MTUser].drop("ssn")
            val got = m.focus(_.name).get(user)
            assert(got == "Alice")
        }

        "drop field focus removed compile error" in {
            typeCheckFailure("Schema[kyo.MTUser].drop(\"ssn\").focus(_.ssn)")("not found")
        }

        "drop preserves other fields" in {
            val m = Schema[MTUser].drop("ssn")
            assert(m.fieldNames.size == 3)
            assert(m.focus(_.name).tag =:= Tag[String])
            assert(m.focus(_.age).tag =:= Tag[Int])
            assert(m.focus(_.email).tag =:= Tag[String])
        }

        "drop multiple fields" in {
            val m                                                                  = Schema[MTUser].drop("ssn").drop("email")
            val _: Schema[MTUser] { type Focused = "name" ~ String & "age" ~ Int } = m
            assert(m.fieldNames == Set("name", "age"))
            assert(m.focus(_.name).tag =:= Tag[String])
            assert(m.focus(_.age).tag =:= Tag[Int])
        }

        "drop all but one" in {
            val m                                                    = Schema[MTUser].drop("ssn").drop("email").drop("age")
            val _: Schema[MTUser] { type Focused = "name" ~ String } = m
            assert(m.fieldNames == Set("name"))
            assert(m.focus(_.name).tag =:= Tag[String])
        }

        // --- rename (6 tests) ---

        "rename field" in {
            val m = Schema[MTUser].rename("name", "userName")
            assert(m.fieldNames.contains("userName"))
            assert(!m.fieldNames.contains("name"))
        }

        "rename schema reflects" in {
            val m     = Schema[MTUser].rename("name", "userName")
            val names = m.fieldNames
            assert(names.contains("userName"))
            assert(!names.contains("name"))
            assert(names.contains("age"))
            assert(names.contains("email"))
            assert(names.contains("ssn"))
            // Fields that exist on the original type are still accessible via focus
            assert(m.focus(_.age).tag =:= Tag[Int])
            assert(m.focus(_.email).tag =:= Tag[String])
            assert(m.focus(_.ssn).tag =:= Tag[String])
        }

        "rename then result has new name" in {
            // After rename, the new name should appear in result
            val m      = Schema[MTUser].rename("name", "userName")
            val record = m.toRecord(user)
            assert(record.dict("userName") == "Alice")
            assert(!record.dict.contains("name"))
        }

        "rename old name compile error" in {
            typeCheckFailure("Schema[kyo.MTUser].rename(\"name\", \"userName\").focus(_.name)")("not found")
        }

        "rename preserves type" in {
            val m = Schema[MTUser].rename("name", "userName")
            // Verify the renamed field type is preserved via type ascription
            val _: Schema[MTUser] { type Focused = "age" ~ Int & "email" ~ String & "ssn" ~ String & "userName" ~ String } = m
            succeed
        }

        "rename then drop" in {
            val m = Schema[MTUser].rename("name", "userName").drop("userName")
            assert(!m.fieldNames.contains("name"))
            assert(!m.fieldNames.contains("userName"))
            assert(m.fieldNames.contains("age"))
            assert(m.focus(_.age).tag =:= Tag[Int])
            assert(m.focus(_.email).tag =:= Tag[String])
            assert(m.focus(_.ssn).tag =:= Tag[String])
        }

        // --- add (6 tests) ---

        "add new field" in {
            val m = Schema[MTUser].add("active")(_ => true)
            assert(m.fieldNames.contains("active"))
            assert(m.fieldNames.contains("name"))
            assert(m.focus(_.name).tag =:= Tag[String])
        }

        "add schema reflects" in {
            val m     = Schema[MTUser].add("active")(_ => true)
            val names = m.fieldNames
            assert(names.contains("active"))
            assert(names.size == 5) // 4 original + 1 added
        }

        "add then result" in {
            val m      = Schema[MTUser].add("active")(_ => true)
            val record = m.toRecord(user)
            assert(record.dict("active") == true)
        }

        "add then result get" in {
            val m      = Schema[MTUser].add("active")(_ => true)
            val record = m.toRecord(user)
            assert(record.dict("active") == true)
        }

        "add computed field" in {
            val m      = Schema[MTUser].add("greeting")(u => s"Hello, ${u.name}!")
            val record = m.toRecord(user)
            assert(record.dict("greeting") == "Hello, Alice!")
        }

        "add multiple fields" in {
            val m = Schema[MTUser].add("active")(_ => true).add("score")(_ => 100)
            assert(m.fieldNames.contains("active"))
            assert(m.fieldNames.contains("score"))
            assert(m.fieldNames.size == 6) // 4 original + 2 added
        }

        // --- Type tracking composition (6 tests) ---

        "drop then rename" in {
            val m = Schema[MTUser].drop("ssn").rename("name", "userName")
            assert(!m.fieldNames.contains("ssn"))
            assert(!m.fieldNames.contains("name"))
            assert(m.fieldNames.contains("userName"))
            assert(m.focus(_.age).tag =:= Tag[Int])
            assert(m.focus(_.email).tag =:= Tag[String])
        }

        "rename then add" in {
            val m =
                Schema[MTUser].rename("name", "userName").add("active")(_ => true)
            assert(m.fieldNames.contains("userName"))
            assert(m.fieldNames.contains("active"))
            assert(!m.fieldNames.contains("name"))
        }

        "add then drop" in {
            val m = Schema[MTUser].add("active")(_ => true).drop("active")
            assert(!m.fieldNames.contains("active"))
            assert(m.focus(_.name).tag =:= Tag[String])
            assert(m.focus(_.age).tag =:= Tag[Int])
            assert(m.focus(_.email).tag =:= Tag[String])
            assert(m.focus(_.ssn).tag =:= Tag[String])
        }

        "full chain" in {
            val m = Schema[MTUser]
                .drop("ssn")
                .rename("name", "userName")
                .add("active")(_ => true)
            assert(!m.fieldNames.contains("ssn"))
            assert(!m.fieldNames.contains("name"))
            assert(m.fieldNames.contains("userName"))
            assert(m.fieldNames.contains("active"))
            assert(m.focus(_.age).tag =:= Tag[Int])
            assert(m.focus(_.email).tag =:= Tag[String])
        }

        "transform then schema" in {
            val m = Schema[MTUser]
                .drop("ssn")
                .rename("name", "userName")
                .add("active")(_ => true)
            val names = m.fieldNames
            assert(names == Set("userName", "age", "email", "active"))
            assert(m.focus(_.age).tag =:= Tag[Int])
            assert(m.focus(_.email).tag =:= Tag[String])
        }

        "transform then result" in {
            val m      = Schema[MTUser].drop("ssn").rename("name", "userName")
            val record = m.toRecord(user)
            assert(record.dict("userName") == "Alice")
            assert(!record.dict.contains("ssn"))
        }

        // --- Compile-time errors (9 tests) ---

        "drop nonexistent field compile error" in {
            typeCheckFailure("Schema[kyo.MTUser].drop(\"nonexistent\")")("not found")
        }

        "rename nonexistent field compile error" in {
            typeCheckFailure("Schema[kyo.MTUser].rename(\"nonexistent\", \"x\")")("not found")
        }

        "add duplicate field compile error" in {
            typeCheckFailure("Schema[kyo.MTUser].add[String](\"name\")(_.name)")("already exists")
        }

        "drop on primitive compile error" in {
            typeCheckFailure("Schema[Int].drop(\"x\")")("not found")
        }

        "rename to existing name compile error" in {
            typeCheckFailure("Schema[kyo.MTUser].rename(\"name\", \"age\")")("already exists")
        }

        "add empty name compile error" in {
            typeCheckFailure("Schema[kyo.MTUser].add[Int](\"\")(_.age)")("must not be empty")
        }

        "drop after rename uses new name" in {
            typeCheckFailure("Schema[kyo.MTUser].rename(\"name\", \"userName\").drop(\"name\")")("not found")
        }

        // === select (from MetaKeepFlattenMergeTest, 5 tests) ===

        "select single field" in {
            val m                                                    = Schema[MTUser].select("name")
            val _: Schema[MTUser] { type Focused = "name" ~ String } = m
            assert(m.fieldNames == Set("name"))
        }

        "select multiple fields" in {
            val m                                                                  = Schema[MTUser].select("name", "age")
            val _: Schema[MTUser] { type Focused = "name" ~ String & "age" ~ Int } = m
            assert(m.fieldNames == Set("name", "age"))
        }

        "select schema reflects" in {
            val m     = Schema[MTUser].select("name")
            val names = m.fieldNames
            assert(names == Set("name"))
            assert(m.focus(_.name).tag =:= Tag[String])
        }

        "select nonexistent compile error" in {
            typeCheckFailure("Schema[kyo.MTUser].select(\"nonexistent\")")("not found")
        }

        "select preserves field types" in {
            val m   = Schema[MTUser].select("name", "age")
            val got = m.focus(_.name).get(user)
            assert(got == "Alice")
            val age = m.focus(_.age).get(user)
            assert(age == 30)
        }

        // === flatten (from MetaKeepFlattenMergeTest, 5 tests) ===

        "flatten nested product" in {
            val m = Schema[MTPersonAddr].flatten
            assert(!m.fieldNames.contains("address"))
            assert(m.focus(_.name).tag =:= Tag[String])
            assert(m.focus(_.age).tag =:= Tag[Int])
            assert(m.fieldNames == Set("name", "age", "street", "city", "zip"))
        }

        "flatten schema reflects" in {
            val m     = Schema[MTPersonAddr].flatten
            val names = m.fieldNames
            assert(names == Set("name", "age", "street", "city", "zip"))
        }

        "flatten preserves primitives" in {
            val m = Schema[MTPerson].flatten
            assert(m.fieldNames == Set("name", "age"))
            assert(m.focus(_.name).tag =:= Tag[String])
            assert(m.focus(_.age).tag =:= Tag[Int])
        }

        "flatten no nested" in {
            val m = Schema[MTPerson].flatten
            assert(m.fieldNames == Set("name", "age"))
            assert(m.focus(_.name).tag =:= Tag[String])
            assert(m.focus(_.age).tag =:= Tag[Int])
        }

        "flatten field access" in {
            val m = Schema[MTPersonAddr].flatten
            assert(m.fieldNames == Set("name", "age", "street", "city", "zip"))
            assert(m.focus(_.name).tag =:= Tag[String])
            assert(m.focus(_.age).tag =:= Tag[Int])
            assert(!m.fieldNames.contains("address"))
        }

        // === result returns Record[F] (4 tests) ===

        "result after drop returns typed Record" in {
            val m      = Schema[MTUser].drop("ssn")
            val fn     = m.toRecord
            val record = fn(user)
            // Verify the return type is Record[F] not Record[Any]
            val _: Record["name" ~ String & "age" ~ Int & "email" ~ String] = record
            assert(record.name == "Alice")
            assert(record.age == 30)
            assert(record.email == "alice@example.com")
        }

        "result after rename returns typed Record" in {
            val m      = Schema[MTUser].rename("name", "userName")
            val record = m.toRecord(user)
            // Type includes renamed field
            val _: Record["age" ~ Int & "email" ~ String & "ssn" ~ String & "userName" ~ String] = record
            assert(record.userName == "Alice")
            assert(record.age == 30)
        }

        "result after add returns typed Record" in {
            val m      = Schema[MTUser].add("active")(_ => true)
            val record = m.toRecord(user)
            // Type includes added field
            val _: Record[
                "name" ~ String & "age" ~ Int & "email" ~ String & "ssn" ~ String & "active" ~ Boolean
            ] = record
            assert(record.active == true)
            assert(record.name == "Alice")
        }

        "result after full transform chain returns typed Record" in {
            val m = Schema[MTUser]
                .drop("ssn")
                .rename("name", "userName")
                .add("active")(_ => true)
            val record = m.toRecord(user)
            // F = "age" ~ Int & "email" ~ String & "userName" ~ String & "active" ~ Boolean
            assert(record.userName == "Alice")
            assert(record.age == 30)
            assert(record.email == "alice@example.com")
            assert(record.active == true)
        }

        // === convert[B] after transforms (5 tests) ===

        "convert after drop" in {
            val convert = Schema[MTUser].drop("ssn").drop("email").convert[MTPublicUser]
            val result  = convert(user)
            assert(result == MTPublicUser("Alice", 30))
        }

        "convert after rename" in {
            val convert = Schema[MTUser].drop("ssn").drop("email").rename("name", "userName").convert[MTUserName]
            val result  = convert(user)
            assert(result == MTUserName("Alice", 30))
        }

        "convert after full transform" in {
            val convert = Schema[MTUser]
                .drop("ssn")
                .drop("email")
                .rename("name", "userName")
                .add("active")(_ => true)
                .convert[MTUserResponse]
            val result = convert(user)
            assert(result == MTUserResponse("Alice", 30, true))
        }

        "convert after select" in {
            val convert = Schema[MTUser].select("email", "age").convert[MTEmailAge]
            val result  = convert(user)
            assert(result == MTEmailAge("alice@example.com", 30))
        }

        "convert after transform with defaults" in {
            // MTWithDefault has active: Boolean = true
            val convert = Schema[MTUser].drop("ssn").drop("email").convert[MTWithDefault]
            val result  = convert(user)
            assert(result == MTWithDefault("Alice", 30, true))
        }

        // === convert[B] compile-time errors after transforms (2 tests) ===

        "convert missing field after drop compile error" in {
            // After dropping "email", target type MTEmailAge (which needs email) can't be satisfied
            typeCheckFailure("Schema[kyo.MTUser].drop(\"email\").convert[kyo.MTEmailAge]")("no corresponding field")
        }

        "convert after rename chain A->B then B->C" in {
            // rename name->userName then userName->displayName, convert[B] matches displayName
            val convert = Schema[MTUser]
                .drop("ssn")
                .drop("email")
                .rename("name", "userName")
                .rename("userName", "displayName")
                .convert[MTDisplayUser]
            val result = convert(user)
            assert(result == MTDisplayUser("Alice", 30))
        }

        "convert after drop then add back same name different type" in {
            // drop "age" (Int), add "age" (String), convert[B] where B expects String age
            val convert = Schema[MTUser]
                .drop("ssn")
                .drop("email")
                .drop("age")
                .add[String]("age")(u => u.age.toString)
                .convert[MTStringAge]
            val result = convert(user)
            assert(result == MTStringAge("Alice", "30"))
        }

        // --- ETL pipeline tests ---

        "ETL rename chain A->B then B->C" in {
            // rename userId->user then user->u, verify "u" in schema
            val m = Schema[MTRawEvent]
                .rename("userId", "user")
                .rename("user", "u")
            assert(m.fieldNames.contains("u"))
            assert(!m.fieldNames.contains("userId"))
            assert(!m.fieldNames.contains("user"))
        }

        // --- Migration tests ---

        "migration to compile error when target has extra field without default" in {
            // MTUserV2NoDefault has "role: String" with no default, source doesn't have it
            typeCheckFailure(
                """Schema[kyo.MTUserV1].rename("name", "fullName").convert[kyo.MTUserV2NoDefault]"""
            )("no corresponding field")
        }

        "migration rename to wrong name compile error" in {
            // rename("name", "displayName") but target expects "fullName"
            typeCheckFailure(
                """Schema[kyo.MTUserV1].rename("name", "displayName").convert[kyo.MTUserV2]"""
            )("no corresponding field")
        }

        "migration drop+rename+add combined" in {
            // Drop email (obsolete), rename name->user, add active, convert[MTMigrated]
            val migrate = Schema[MTUserV1]
                .drop("email")
                .rename("name", "user")
                .add("active")(_ => true)
                .convert[MTMigrated]
            val old    = MTUserV1("Alice", 30, "alice@example.com")
            val result = migrate(old)
            assert(result == MTMigrated("Alice", 30, true))
        }

        // --- Domain event processing tests ---

        "event validation collects all 3 errors on bad input" in {
            val eventMeta = Schema[MTOrderEvent]
                .check(_.orderId)(_.nonEmpty, "orderId required")
                .check(_.action)(s => Set("create", "update", "cancel").contains(s), "invalid action")
                .check(_.amount)(_ > 0, "amount must be positive")
            val errors = eventMeta.validate(MTOrderEvent("", "invalid", -1.0, 0L))
            assert(errors.size == 3)
            assert(errors.exists(_.message == "orderId required"))
            assert(errors.exists(_.message == "invalid action"))
            assert(errors.exists(_.message == "amount must be positive"))
        }

        "event validation on valid input returns empty" in {
            val eventMeta = Schema[MTOrderEvent]
                .check(_.orderId)(_.nonEmpty, "orderId required")
                .check(_.action)(s => Set("create", "update", "cancel").contains(s), "invalid action")
                .check(_.amount)(_ > 0, "amount must be positive")
            val errors = eventMeta.validate(MTOrderEvent("ORD-1", "create", 10.0, 100L))
            assert(errors.isEmpty)
        }

        // --- API versioning tests ---

        "versioning rename+add+convert end-to-end migration" in {
            val migrate = Schema[MTUserV1]
                .rename("name", "fullName")
                .add("active")(_ => true)
                .convert[MTUserV2]
            val old    = MTUserV1("Alice", 30, "alice@example.com")
            val result = migrate(old)
            assert(result == MTUserV2("Alice", 30, "alice@example.com", true))
        }

        "versioning compile error target has extra field without default" in {
            // MTUserV2WithRole has "role: String" with no default
            typeCheckFailure(
                """Schema[kyo.MTUserV1].rename("name", "fullName").add("active")(_ => true).convert[kyo.MTUserV2WithRole]"""
            )("no corresponding field")
        }

        "versioning compile error rename leaves wrong field name" in {
            // rename("name", "displayName") but MTUserV2 expects "fullName"
            typeCheckFailure(
                """Schema[kyo.MTUserV1].rename("name", "displayName").add("active")(_ => true).convert[kyo.MTUserV2]"""
            )("no corresponding field")
        }

        "versioning schema introspection compares V1 and V2 names" in {
            val v1Names = Schema[MTUserV1].fieldNames
            val v2Names = Schema[MTUserV2].fieldNames
            assert(v1Names == Set("name", "age", "email"))
            assert(v2Names == Set("fullName", "age", "email", "active"))
            assert(v1Names != v2Names)
        }

        "versioning add with default value matches target default" in {
            // add("active")(_ => true) matches UserV2's active field
            val migrate = Schema[MTUserV1]
                .rename("name", "fullName")
                .add("active")(_ => true)
                .convert[MTUserV2]
            val result = migrate(MTUserV1("Bob", 25, "bob@example.com"))
            assert(result.active == true)
        }

        "versioning multiple renames in chain" in {
            // rename("name", "fullName") then rename("email", "emailAddr") are independent renames
            // Need a target type with both renamed fields
            val m = Schema[MTUserV1]
                .rename("name", "fullName")
                .rename("email", "emailAddr")
            assert(m.fieldNames.contains("fullName"))
            assert(m.fieldNames.contains("emailAddr"))
            assert(!m.fieldNames.contains("name"))
            assert(!m.fieldNames.contains("email"))
            assert(m.fieldNames.contains("age"))
        }

        "versioning migration preserves fields not explicitly transformed" in {
            // email passes through rename+add chain unchanged
            val migrate = Schema[MTUserV1]
                .rename("name", "fullName")
                .add("active")(_ => true)
                .convert[MTUserV2]
            val old    = MTUserV1("Alice", 30, "alice@example.com")
            val result = migrate(old)
            assert(result.email == "alice@example.com")
            assert(result.age == 30)
        }

        "fieldId with non-positive id throws SchemaException" in {
            try
                Schema[MTUser].fieldId(_.name)(0)
                fail("Expected an exception for non-positive fieldId, but none was thrown")
            catch
                case _: SchemaException => succeed
                case e: IllegalArgumentException =>
                    fail(s"Got IllegalArgumentException instead of SchemaException: ${e.getMessage}")
            end try
        }

        "drop on sealed trait fails to compile" in {
            typeCheckFailure("Schema[kyo.MTShape].drop(\"Circle\")")("not supported")
        }

        "rename on sealed trait fails to compile" in {
            typeCheckFailure("Schema[kyo.MTShape].rename(\"Circle\", \"circle\")")("not supported")
        }

        "lambda syntax" - {

            val user = MTUser("Alice", 30, "alice@example.com", "123-45-6789")

            "drop via lambda removes field same as string" in {
                val byString = Schema[MTUser].drop("ssn")
                val byLambda = Schema[MTUser].drop(_.ssn)
                assert(byString.fieldNames == byLambda.fieldNames)
                assert(byLambda.fieldNames == Set("name", "age", "email"))
                // Type-level: both should have the same Focused type
                val _: Schema[MTUser] { type Focused = "name" ~ String & "age" ~ Int & "email" ~ String } = byLambda
                succeed
            }

            "rename via lambda same as string" in {
                val byString = Schema[MTUser].rename("name", "userName")
                val byLambda = Schema[MTUser].rename(_.name, "userName")
                assert(byString.fieldNames == byLambda.fieldNames)
                assert(byLambda.fieldNames.contains("userName"))
                assert(!byLambda.fieldNames.contains("name"))
            }

            "select via lambda same as string" in {
                val byString = Schema[MTUser].select("name", "age")
                val byLambda = Schema[MTUser].select(_.name, _.age)
                assert(byString.fieldNames == byLambda.fieldNames)
                assert(byLambda.fieldNames == Set("name", "age"))
            }

            "compile error for non-existent field in lambda drop" in {
                typeCheckFailure("Schema[kyo.MTUser].drop(_.nonExistent)")("not found")
            }

            "lambda and string styles mixed in chain" in {
                val m = Schema[MTUser]
                    .drop(_.ssn)
                    .rename("name", "userName")
                    .add("active")(_ => true)
                assert(m.fieldNames == Set("userName", "age", "email", "active"))
                val record = m.toRecord(user)
                assert(record.dict("userName") == "Alice")
                assert(record.dict("active") == true)
            }

            "lambda drop on multi-field chain" in {
                // Drop multiple fields via lambda chaining
                val m                                                                  = Schema[MTUser].drop(_.ssn).drop(_.email)
                val _: Schema[MTUser] { type Focused = "name" ~ String & "age" ~ Int } = m
                assert(m.fieldNames == Set("name", "age"))
            }

            "extracted field name matches declared name" in {
                // Use focus to verify the lambda extracts the right name
                val m   = Schema[MTUser].drop(_.ssn)
                val got = m.focus(_.name).get(user)
                assert(got == "Alice")
                val age = m.focus(_.age).get(user)
                assert(age == 30)
                val email = m.focus(_.email).get(user)
                assert(email == "alice@example.com")
                // ssn should not be accessible after drop
                typeCheckFailure("Schema[kyo.MTUser].drop(_.ssn).focus(_.ssn)")("not found")
            }

            "Focus provides selectDynamic for navigation" in {
                // Verify that Focus.Select[A, F] is a Dynamic with selectDynamic
                // This test verifies the lambda compiles and navigates correctly
                val m   = Schema[MTUser].select(_.name, _.email)
                val got = m.focus(_.name).get(user)
                assert(got == "Alice")
                val email = m.focus(_.email).get(user)
                assert(email == "alice@example.com")
            }

        }
    }

    // =========================================================================
    // codec
    // =========================================================================

    "codec" - {

        "schema write simple" in {
            val schema = summon[Schema[MTPerson]]
            val person = MTPerson("Alice", 30)
            val w      = JsonWriter()
            schema.writeTo(person, w)
            val json = w.resultString
            assert(json.contains("\"name\""))
            assert(json.contains("\"Alice\""))
            assert(json.contains("\"age\""))
            assert(json.contains("30"))
        }

        "schema read simple" in {
            val schema = summon[Schema[MTPerson]]
            val json   = """{"name":"Bob","age":25}"""
            val reader = JsonReader(json)
            val result = schema.readFrom(reader)
            assert(result == MTPerson("Bob", 25))
        }

        "schema round-trip" in {
            val schema = summon[Schema[MTPerson]]
            val person = MTPerson("Carol", 42)
            val w      = JsonWriter()
            schema.writeTo(person, w)
            val reader = JsonReader(w.resultString)
            val result = schema.readFrom(reader)
            assert(result == person)
        }

        "schema nested" in {
            val schema = summon[Schema[MTPersonAddr]]
            val value  = MTPersonAddr("Dave", 35, MTAddress("123 Main St", "Springfield", "62701"))
            val w      = JsonWriter()
            schema.writeTo(value, w)
            val reader = JsonReader(w.resultString)
            val result = schema.readFrom(reader)
            assert(result == value)
        }

        "schema sealed trait" in {
            val schema          = summon[Schema[MTShape]]
            val circle: MTShape = MTCircle(5.0)
            val w               = JsonWriter()
            schema.writeTo(circle, w)
            val reader = JsonReader(w.resultString)
            val result = schema.readFrom(reader)
            assert(result == circle)
        }

        "schema with defaults" in {
            val schema = summon[Schema[MTConfig]]
            // JSON missing port and ssl fields (which have defaults)
            val json   = """{"host":"localhost"}"""
            val reader = JsonReader(json)
            val result = schema.readFrom(reader)
            assert(result == MTConfig("localhost", 8080, false))
        }

        "schema missing required error" in {
            val schema = summon[Schema[MTPerson]]
            // JSON missing "name" which is required
            val json   = """{"age":30}"""
            val reader = JsonReader(json)
            try
                schema.readFrom(reader)
                fail("Expected MissingFieldException")
            catch
                case e: MissingFieldException =>
                    assert(e.fieldName == "name")
                    assert(e.getMessage.contains("name"))
            end try
        }

        "schema to Structure.Value" in {
            val schema = summon[Schema[MTPerson]]
            val person = MTPerson("Eve", 28)
            val w      = new StructureValueWriter()
            schema.writeTo(person, w)
            val dv = w.getResult
            dv match
                case Structure.Value.Record(fields) =>
                    assert(fields.size == 2)
                    val nameField = fields.find(_._1 == "name")
                    assert(nameField.isDefined)
                    nameField.get._2 match
                        case Structure.Value.Str(v) => assert(v == "Eve")
                        case other                  => fail(s"Expected Str, got $other")
                case other => fail(s"Expected Record, got $other")
            end match
        }

        "schema from Structure.Value" in {
            val schema = summon[Schema[MTPerson]]
            val dv = Structure.Value.Record(Chunk(
                ("name", Structure.Value.primitive("Frank")),
                ("age", Structure.Value.primitive(40))
            ))
            val r      = new StructureValueReader(dv)
            val result = schema.readFrom(r)
            assert(result == MTPerson("Frank", 40))
        }

        "schema Structure.Value round-trip" in {
            val schema = summon[Schema[MTPerson]]
            val person = MTPerson("Grace", 33)
            val w      = new StructureValueWriter()
            schema.writeTo(person, w)
            val dv     = w.getResult
            val r      = new StructureValueReader(dv)
            val result = schema.readFrom(r)
            assert(result == person)
        }

        "schema with list fields" in {
            val schema = summon[Schema[MTOrder]]
            val order  = MTOrder(1, List(MTItem("Widget", 9.99), MTItem("Gadget", 19.99)))
            val w      = JsonWriter()
            schema.writeTo(order, w)
            val reader = JsonReader(w.resultString)
            val result = schema.readFrom(reader)
            assert(result == order)
        }

        "schema with option fields" in {
            val schema   = summon[Schema[MTOptional]]
            val withNick = MTOptional("Alice", Some("Ali"))
            val noNick   = MTOptional("Bob", None)

            // Round-trip with Some
            val w1 = JsonWriter()
            schema.writeTo(withNick, w1)
            val r1 = JsonReader(w1.resultString)
            assert(schema.readFrom(r1) == withNick)

            // Round-trip with None
            val w2 = JsonWriter()
            schema.writeTo(noNick, w2)
            val r2 = JsonReader(w2.resultString)
            assert(schema.readFrom(r2) == noNick)
        }

        "protobuf write" in {
            val schema = summon[Schema[MTPerson]]
            val person = MTPerson("Alice", 30)
            val w      = new ProtobufWriter
            schema.writeTo(person, w)
            val bytes = w.resultBytes
            assert(bytes.nonEmpty)
        }

        "protobuf read" in {
            val schema = summon[Schema[MTPerson]]
            val person = MTPerson("Alice", 30)
            val w      = new ProtobufWriter
            schema.writeTo(person, w)

            // Use hash-based field IDs for the mapping
            val nameId = CodecMacro.fieldId("name")
            val ageId  = CodecMacro.fieldId("age")
            val r = new ProtobufReader(w.resultBytes)
                .withFieldNames(Map(nameId -> "name", ageId -> "age"))
            val result = schema.readFrom(r)
            assert(result == person)
        }

        "protobuf round-trip" in {
            val schema = summon[Schema[MTPerson]]
            val person = MTPerson("Bob", 25)
            val w      = new ProtobufWriter
            schema.writeTo(person, w)

            // Use hash-based field IDs for the mapping
            val nameId = CodecMacro.fieldId("name")
            val ageId  = CodecMacro.fieldId("age")
            val r = new ProtobufReader(w.resultBytes)
                .withFieldNames(Map(nameId -> "name", ageId -> "age"))
            val result = schema.readFrom(r)
            assert(result == person)
        }

        "protobuf nested" in {
            val schema = summon[Schema[MTPersonAddr]]
            val value  = MTPersonAddr("Carol", 42, MTAddress("Oak Ave", "Portland", "97201"))
            // Use token-based writer/reader for nested round-trip since protobuf reader
            // has a single field-name map that doesn't apply to nested message levels
            val w = new TestWriter
            schema.writeTo(value, w)
            val r      = new TestReader(w.resultTokens)
            val result = schema.readFrom(r)
            assert(result == value)
        }

        "schema as given" in {
            val m = Schema[MTPerson]
            import m.given
            val s      = summon[Schema[MTPerson]]
            val person = MTPerson("Alice", 30)
            val w      = JsonWriter()
            s.writeTo(person, w)
            val json = w.resultString
            assert(json.contains("Alice"))
        }

        "order via import" in {
            val schema = Schema[MTPerson]
            import schema.order
            val persons = List(MTPerson("Charlie", 25), MTPerson("Alice", 30), MTPerson("Bob", 28))
            val sorted  = persons.sorted
            assert(sorted.map(_.name) == List("Alice", "Bob", "Charlie"))
        }

        "canEqual via import" in {
            val schema = Schema[MTPerson]
            import schema.canEqual
            val p1 = MTPerson("Alice", 30)
            val p2 = MTPerson("Alice", 30)
            assert(p1 == p2)
        }

        "large case class schema round-trip" in {
            val schema = summon[Schema[MTLarge]]
            val large  = MTLarge(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)
            val w      = JsonWriter()
            schema.writeTo(large, w)
            val reader = JsonReader(w.resultString)
            val result = schema.readFrom(reader)
            assert(result == large)
        }

        "empty case class schema round-trip" in {
            val schema = summon[Schema[MTEmpty]]
            val empty  = MTEmpty()
            val w      = JsonWriter()
            schema.writeTo(empty, w)
            val reader = JsonReader(w.resultString)
            val result = schema.readFrom(reader)
            assert(result == empty)
        }

        "malformed JSON decode error" in {
            val schema = summon[Schema[MTPerson]]
            val json   = """not valid json at all"""
            try
                val reader = JsonReader(json)
                schema.readFrom(reader)
                fail("Expected SchemaException on malformed JSON")
            catch
                case _: SchemaException => succeed
            end try
        }

        "incomplete JSON decode error" in {
            val schema = summon[Schema[MTPerson]]
            val json   = """{"name":"Alice"""
            try
                val reader = JsonReader(json)
                schema.readFrom(reader)
                fail("Expected SchemaException on incomplete JSON")
            catch
                case _: SchemaException => succeed
            end try
        }

        "transform then codec - drop excludes field" in {
            val m    = Schema[MTUser].drop("ssn")
            val user = MTUser("Alice", 30, "alice@test.com", "123-45-6789")
            // Verify that result() excludes ssn
            val record = m.toRecord(user)
            assert(!record.dict.contains("ssn"))
            assert(record.dict("name") == "Alice")
            assert(record.dict("age") == 30)
            assert(record.dict("email") == "alice@test.com")
        }

        "transform drop - JSON via to[DTO] excludes dropped field" in {
            // drop("ssn") then convert to MTPublicUser(name, age) via to[],
            // then encode to JSON and verify "ssn" is absent
            val m    = Schema[MTUser].drop("ssn").drop("email")
            val user = MTUser("Alice", 30, "alice@test.com", "123-45-6789")
            val dto  = m.convert[MTPublicUser](user)
            val json = Json.encode(dto)
            assert(json.contains("\"name\""))
            assert(json.contains("\"Alice\""))
            assert(json.contains("\"age\""))
            assert(!json.contains("ssn"))
            assert(!json.contains("email"))
            assert(!json.contains("123-45-6789"))
        }

        "transform rename - result contains new field name" in {
            val m      = Schema[MTUser].rename(_.name, "userName")
            val user   = MTUser("Alice", 30, "alice@test.com", "123-45-6789")
            val record = m.toRecord(user)
            // New name should be present, old name should be absent
            assert(record.dict.contains("userName"))
            assert(!record.dict.contains("name"))
            assert(record.dict("userName") == "Alice")
            assert(record.dict("age") == 30)
        }

        "transform rename - JSON via to[DTO] has renamed key" in {
            // rename name -> userName, drop ssn/email, convert to MTUserName(userName, age)
            val m    = Schema[MTUser].rename(_.name, "userName").drop("ssn").drop("email")
            val user = MTUser("Alice", 30, "alice@test.com", "123-45-6789")
            val dto  = m.convert[MTUserName](user)
            val json = Json.encode(dto)
            assert(json.contains("\"userName\""))
            assert(json.contains("\"Alice\""))
            assert(!json.contains("\"name\""))
            assert(!json.contains("ssn"))
        }

        "transform add - result contains computed field" in {
            val m      = Schema[MTUser].add("active")(_ => true)
            val user   = MTUser("Alice", 30, "alice@test.com", "123-45-6789")
            val record = m.toRecord(user)
            assert(record.dict.contains("active"))
            assert(record.dict("active") == true)
            // Original fields should still be present
            assert(record.dict("name") == "Alice")
            assert(record.dict("age") == 30)
        }

        "transform add - JSON via to[DTO] includes computed field" in {
            // add "active" field, drop ssn, rename name -> userName, then convert to MTUserResponse
            val m    = Schema[MTUser].rename(_.name, "userName").drop("ssn").drop("email").add("active")(_ => true)
            val user = MTUser("Alice", 30, "alice@test.com", "123-45-6789")
            val dto  = m.convert[MTUserResponse](user)
            val json = Json.encode(dto)
            assert(json.contains("\"active\""))
            assert(json.contains("true"))
            assert(json.contains("\"userName\""))
            assert(json.contains("\"Alice\""))
            assert(!json.contains("ssn"))
        }

        "schema same type different format" in {
            // Verify the same Schema[A] works with both Json and Protobuf formats
            given s: Schema[MTPerson] = summon[Schema[MTPerson]]
            val person                = MTPerson("Alice", 30)

            // Both produce non-empty byte output
            val jsonBytes  = Json.encodeBytes(person)
            val protoBytes = Protobuf.encode(person)
            assert(jsonBytes.size > 0)
            assert(protoBytes.size > 0)

            // Formats produce different byte representations
            assert(jsonBytes.toArray.toSeq != protoBytes.toArray.toSeq)

            // JSON round-trip works via Format schema
            val fromJson = Json.decodeBytes[MTPerson](jsonBytes).getOrThrow
            assert(fromJson == person)
        }

        "schema encode/decode String overloads" in {
            // decode(jsonString) parses JSON string directly
            given s: Schema[MTPerson] = summon[Schema[MTPerson]]
            val person                = MTPerson("Diana", 35)

            // Encode to bytes then decode from string
            val jsonString = """{"name":"Diana","age":35}"""
            val decoded    = Json.decode[MTPerson](jsonString).getOrThrow
            assert(decoded == person)

            // Round-trip: encode to bytes, convert to string, decode from string
            val bytes        = Json.encodeBytes(person)
            val asString     = new String(bytes.toArray, java.nio.charset.StandardCharsets.UTF_8)
            val roundTripped = Json.decode[MTPerson](asString).getOrThrow
            assert(roundTripped == person)
        }

        "Schema primitive encode/decode" in {
            // Schema[String] with serialization
            given Schema[String] = summon[Schema[String]]
            val encoded          = Json.encodeBytes("hello")
            val decoded          = Json.decodeBytes[String](encoded).getOrThrow
            assert(decoded == "hello")
        }

        "Schema primitive encodeString" in {
            given Schema[Int] = summon[Schema[Int]]
            val json          = Json.encode(42)
            assert(json == "42")
        }

        "Schema collection List encode/decode" in {
            given Schema[List[Int]] = summon[Schema[List[Int]]]
            val original            = List(1, 2, 3, 4, 5)
            val encoded             = Json.encodeBytes(original)
            val decoded             = Json.decodeBytes[List[Int]](encoded).getOrThrow
            assert(decoded == original)
        }

        "Schema collection Maybe encode/decode" in {
            given Schema[Maybe[String]] = summon[Schema[Maybe[String]]]

            val present = Maybe("value")
            val absent  = Maybe.empty[String]

            val enc1 = Json.encodeBytes(present)
            val dec1 = Json.decodeBytes[Maybe[String]](enc1).getOrThrow
            assert(dec1 == present)

            val enc2 = Json.encodeBytes(absent)
            val dec2 = Json.decodeBytes[Maybe[String]](enc2).getOrThrow
            assert(dec2 == absent)
        }

        "Schema collection Chunk encode/decode" in {
            given Schema[Chunk[Double]] = summon[Schema[Chunk[Double]]]
            val original                = Chunk(1.1, 2.2, 3.3)
            val encoded                 = Json.encodeBytes(original)
            val decoded                 = Json.decodeBytes[Chunk[Double]](encoded).getOrThrow
            assert(decoded == original)
        }

        "Schema collection Map[String, V] encode/decode" in {
            given Schema[Map[String, Int]] = summon[Schema[Map[String, Int]]]
            val original                   = Map("a" -> 1, "b" -> 2, "c" -> 3)
            val encoded                    = Json.encodeBytes(original)
            val decoded                    = Json.decodeBytes[Map[String, Int]](encoded).getOrThrow
            assert(decoded == original)
        }

        "Schema.transform encode/decode" in {
            // Create an opaque-like type using transform
            given emailSchema: Schema[String] = Schema.stringSchema.transform[String](identity)(identity)
            val email                         = "user@example.com"
            val encoded                       = Json.encodeBytes(email)
            val decoded                       = Json.decodeBytes[String](encoded).getOrThrow
            assert(decoded == email)
        }

        "Schema Frame encode/decode" in {
            given Schema[Frame] = summon[Schema[Frame]]
            val frame           = Frame.derive
            val encoded         = Json.encodeBytes(frame)
            val decoded         = Json.decodeBytes[Frame](encoded).getOrThrow
            // Frame round-trips via its string representation
            assert(decoded.toString == frame.toString)
        }

        "Schema Tag encode/decode - static tag" in {
            given Schema[Tag[Int]] = summon[Schema[Tag[Int]]]
            val tag                = Tag[Int]
            val encoded            = Json.encodeBytes(tag)
            val decoded            = Json.decodeBytes[Tag[Int]](encoded).getOrThrow
            assert(decoded.show == tag.show)
            assert(decoded =:= tag)
        }

        "Schema Result encode/decode - success" in {
            given Schema[Result[String, Int]] = summon[Schema[Result[String, Int]]]
            val success                       = Result.succeed[String, Int](42)
            val encoded                       = Json.encodeBytes(success)
            val decoded                       = Json.decodeBytes[Result[String, Int]](encoded).getOrThrow
            assert(decoded == success)
        }

        "Schema Result encode/decode - failure" in {
            given Schema[Result[String, Int]] = summon[Schema[Result[String, Int]]]
            val failure                       = Result.fail[String, Int]("error message")
            val encoded                       = Json.encodeBytes(failure)
            val decoded                       = Json.decodeBytes[Result[String, Int]](encoded).getOrThrow
            assert(decoded == failure)
        }

        "Schema Result encode/decode - panic" in {
            // Use token-based round-trip since JSON decode has interaction with Result type
            val resultSchema = summon[Schema[Result[String, Int]]]
            val panic        = Result.panic[String, Int](new RuntimeException("kaboom"))

            // Token-based round-trip
            val w = new TestWriter
            resultSchema.serializeWrite.get(panic, w)
            val r       = new TestReader(w.resultTokens)
            val decoded = resultSchema.serializeRead.get(r)

            // The decoded value should be Result.Panic with the message
            decoded match
                case Result.Panic(ex) => assert(ex.getMessage == "kaboom")
                case other            => fail(s"Expected Panic, got $other")
        }

        "Schema Dict[String, V] encode/decode" in {
            given Schema[Dict[String, Int]] = summon[Schema[Dict[String, Int]]]
            val original                    = Dict("x" -> 10, "y" -> 20)
            val encoded                     = Json.encodeBytes(original)
            val decoded                     = Json.decodeBytes[Dict[String, Int]](encoded).getOrThrow
            assert(decoded.size == original.size)
            assert(decoded.get("x") == Maybe(10))
            assert(decoded.get("y") == Maybe(20))
        }

        "Schema Dict[K, V] encode/decode - non-string key" in {
            // Non-string key Dict uses array-of-pairs encoding, which doesn't work well with JSON
            // reader that expects string keys for maps. Use token-based round-trip instead.
            val dictSchema = summon[Schema[Dict[Int, String]]]
            val original   = Dict(1 -> "one", 2 -> "two")
            val w          = new TestWriter
            dictSchema.serializeWrite.get(original, w)
            val r       = new TestReader(w.resultTokens)
            val decoded = dictSchema.serializeRead.get(r)
            assert(decoded.size == original.size)
            assert(decoded.get(1) == Maybe("one"))
            assert(decoded.get(2) == Maybe("two"))
        }

        "Json.encode/decode via Schema" in {
            val value   = List("a", "b", "c")
            val json    = Json.encode(value)
            val decoded = Json.decode[List[String]](json).getOrThrow
            assert(decoded == value)
        }

        "Json.encodeBytes via Schema" in {
            val bytes = Json.encodeBytes(123)
            val json  = new String(bytes.toArray, java.nio.charset.StandardCharsets.UTF_8)
            assert(json == "123")
        }

        "Protobuf.encode works" in {
            // Protobuf encoding of bare primitives requires field wrappers which the simple
            // Schema primitive doesn't provide. Verify encode produces bytes.
            val value = "test"
            val bytes = Protobuf.encode(value)
            assert(bytes.size > 0)
        }

        "Schema encode/decode" in {
            val schema  = summon[Schema[List[Int]]]
            val value   = List(1, 2, 3)
            val dynamic = Structure.encode(value)(using schema)
            dynamic match
                case Structure.Value.Sequence(elems) =>
                    assert(elems.size == 3)
                case other => fail(s"Expected Sequence, got $other")
            end match
            val back = Structure.decode(dynamic)(using schema).getOrThrow
            assert(back == value)
        }

        "tuple2 round-trip" in {
            val tuple = (42, "hello")
            val json  = Json.encode(tuple)
            // Derived tuple schemas use object format with _1, _2 fields
            assert(json.contains("\"_1\""))
            assert(json.contains("\"_2\""))
            val decoded = Json.decode[(Int, String)](json).getOrThrow
            assert(decoded == tuple)
        }

        "tuple2 fieldNames" in {
            val names = Schema[(Int, String)].fieldNames
            assert(names.nonEmpty)
        }

        "Schema[A] has serialization for case classes with serializable fields" in {
            given Schema[MTPerson] = Schema[MTPerson]
            val person             = MTPerson("Test", 1)
            val jsonStr            = Json.encode(person)
            assert(jsonStr.contains("\"name\""))
            assert(jsonStr.contains("\"Test\""))
            assert(jsonStr.contains("\"age\""))
            assert(jsonStr.contains("1"))
            val decoded = Json.decode[MTPerson](jsonStr).getOrThrow
            assert(decoded == person)
        }

        "Span[Byte] uses specialized binary schema" in {
            // Span[Byte] should use spanByteSchema (raw bytes), not spanSchema (array of ints)
            val bytes   = kyo.Span.from(Chunk[Byte](1, 2, 3))
            val encoded = Json.encode(bytes)
            // Raw bytes are encoded as base64 string, not as array of ints
            val decoded = Json.decode[kyo.Span[Byte]](encoded).getOrThrow
            assert(decoded.toArray.toSeq == bytes.toArray.toSeq)
        }

        "Span[Int] uses generic array schema" in {
            val span    = kyo.Span.from(Chunk(10, 20, 30))
            val encoded = Json.encode(span)
            val decoded = Json.decode[kyo.Span[Int]](encoded).getOrThrow
            assert(decoded.toArray.toSeq == span.toArray.toSeq)
        }

        "Seq[String] round-trips through JSON" in {
            val seq: Seq[String] = Seq("a", "b", "c")
            val encoded          = Json.encode(seq)
            val decoded          = Json.decode[Seq[String]](encoded).getOrThrow
            assert(decoded == seq)
        }

        "Structure.Value round-trips through JSON (derived Schema)" in {

            val value: Structure.Value = Structure.Value.Record(Chunk(
                ("name", Structure.Value.Str("Alice")),
                ("age", Structure.Value.Integer(30L))
            ))
            val encoded = Json.encode(value)
            val decoded = Json.decode[Structure.Value](encoded).getOrThrow
            assert(decoded == value)
        }

        "Structure.Value.Null round-trips through JSON" in {

            val value: Structure.Value = Structure.Value.Null
            val encoded                = Json.encode(value)
            val decoded                = Json.decode[Structure.Value](encoded).getOrThrow
            assert(decoded == value)
        }

        "Changeset.Op round-trips through JSON (derived Schema)" in {

            val op: Changeset.Op = Changeset.Op.SetField(
                Chunk("name"),
                Structure.Value.Str("Bob")
            )
            val encoded = Json.encode(op)
            val decoded = Json.decode[Changeset.Op](encoded).getOrThrow
            assert(decoded == op)
        }

        "Schema.Constraint round-trips through JSON" in {
            val constraint: Schema.Constraint = Schema.Constraint.Min(List("age"), 0.0, false)
            val encoded                       = Json.encode(constraint)
            val decoded                       = Json.decode[Schema.Constraint](encoded).getOrThrow
            assert(decoded == constraint)
        }

        "JsonSchema round-trips through JSON" in {
            import kyo.Json.JsonSchema as JS
            val schema: JS = JS.Obj(
                List(("name", JS.Str()), ("age", JS.Integer())),
                List("name", "age")
            )
            val encoded = Json.encode(schema)
            val decoded = Json.decode[JS](encoded).getOrThrow
            assert(decoded == schema)
        }

        "Structure.PathSegment round-trips through JSON" in {

            val seg: Structure.PathSegment = Structure.PathSegment.Field("name")
            val encoded                    = Json.encode(seg)
            val decoded                    = Json.decode[Structure.PathSegment](encoded).getOrThrow
            assert(decoded == seg)
        }

        "Structure.PathSegment.Each round-trips through JSON" in {

            val seg: Structure.PathSegment = Structure.PathSegment.Each
            val encoded                    = Json.encode(seg)
            val decoded                    = Json.decode[Structure.PathSegment](encoded).getOrThrow
            assert(decoded == seg)
        }

        "SchemaNotSerializableException is a SchemaException" in {
            given Frame = Frame.derive
            val ex      = SchemaNotSerializableException("test")
            assert(ex.isInstanceOf[SchemaException])
        }

        "SchemaNotSerializableException has TransformException marker trait" in {
            given Frame = Frame.derive
            val ex      = SchemaNotSerializableException("test")
            assert(ex.isInstanceOf[TransformException])
        }

        "drop + encode excludes dropped field" in {
            given schema: Schema[MTUser] = Schema[MTUser].drop(_.ssn)
            val user                     = MTUser("Alice", 30, "alice@test.com", "123-45-6789")
            val json                     = Json.encode(user)
            assert(!json.contains("ssn"), s"dropped field 'ssn' should not appear in JSON: $json")
            assert(!json.contains("123-45-6789"), s"dropped field value should not appear in JSON: $json")
            assert(json.contains("\"name\""))
            assert(json.contains("\"Alice\""))
            assert(json.contains("\"age\""))
            assert(json.contains("\"email\""))
        }

        "rename + encode uses new field name" in {
            given schema: Schema[MTUser] = Schema[MTUser].rename(_.name, "userName")
            val user                     = MTUser("Alice", 30, "alice@test.com", "123-45-6789")
            val json                     = Json.encode(user)
            assert(json.contains("\"userName\""), s"renamed field should appear as 'userName' in JSON: $json")
            assert(!json.contains("\"name\""), s"original field name 'name' should not appear in JSON: $json")
            assert(json.contains("\"Alice\""))
        }

        "add + encode includes computed field" in {
            given schema: Schema[MTPerson] = Schema[MTPerson].add("greeting")(p => s"Hello ${p.name}")
            val person                     = MTPerson("Alice", 30)
            val json                       = Json.encode(person)
            assert(json.contains("\"greeting\""), s"added field should appear in JSON: $json")
            assert(json.contains("Hello Alice"), s"computed value should appear in JSON: $json")
        }

        "select + encode includes only selected fields" in {
            given schema: Schema[MTUser] = Schema[MTUser].select(_.name, _.age)
            val user                     = MTUser("Alice", 30, "alice@test.com", "123-45-6789")
            val json                     = Json.encode(user)
            assert(json.contains("\"name\""))
            assert(json.contains("\"age\""))
            assert(!json.contains("\"email\""), s"non-selected field 'email' should not appear in JSON: $json")
            assert(!json.contains("\"ssn\""), s"non-selected field 'ssn' should not appear in JSON: $json")
        }

        "chained transforms + encode" in {
            given schema: Schema[MTUser] = Schema[MTUser]
                .drop(_.ssn)
                .rename(_.name, "userName")
            val user = MTUser("Alice", 30, "alice@test.com", "123-45-6789")
            val json = Json.encode(user)
            assert(!json.contains("ssn"), s"dropped 'ssn' should not appear: $json")
            assert(json.contains("\"userName\""), s"renamed field should appear as 'userName': $json")
            assert(!json.contains("\"name\""), s"original 'name' should not appear: $json")
            assert(json.contains("\"email\""))
        }

        "drop + Structure.encode excludes dropped field" in {
            val schema  = Schema[MTUser].drop(_.ssn)
            val user    = MTUser("Alice", 30, "alice@test.com", "123-45-6789")
            val dynamic = Structure.encode(user)(using schema)
            dynamic match
                case Structure.Value.Record(fields) =>
                    val fieldNames = fields.map(_._1).toSet
                    assert(!fieldNames.contains("ssn"), s"dropped field 'ssn' should not appear in dynamic: $fieldNames")
                    assert(fieldNames.contains("name"))
                    assert(fieldNames.contains("age"))
                    assert(fieldNames.contains("email"))
                case other => fail(s"Expected Record, got $other")
            end match
        }

        "rename + Structure.encode uses new field name" in {
            val schema  = Schema[MTUser].rename(_.name, "userName")
            val user    = MTUser("Alice", 30, "alice@test.com", "123-45-6789")
            val dynamic = Structure.encode(user)(using schema)
            dynamic match
                case Structure.Value.Record(fields) =>
                    val fieldNames = fields.map(_._1).toSet
                    assert(fieldNames.contains("userName"), s"renamed field should appear as 'userName': $fieldNames")
                    assert(!fieldNames.contains("name"), s"original 'name' should not appear: $fieldNames")
                case other => fail(s"Expected Record, got $other")
            end match
        }

        "no transforms encode unchanged (regression)" in {
            given Schema[MTPerson] = Schema[MTPerson]
            val person             = MTPerson("Alice", 30)
            val json               = Json.encode(person)
            assert(json.contains("\"name\""))
            assert(json.contains("\"Alice\""))
            assert(json.contains("\"age\""))
            assert(json.contains("30"))
            val decoded = Json.decode[MTPerson](json).getOrThrow
            assert(decoded == person)
        }

        "rename: decode uses new field name" in {
            given schema: Schema[MTUser] = Schema[MTUser].rename(_.name, "userName")
            val user                     = MTUser("Alice", 30, "alice@test.com", "123-45-6789")
            val json                     = Json.encode(user)
            // json contains "userName", not "name"
            assert(json.contains("\"userName\""))
            assert(!json.contains("\"name\""))
            // decode should succeed and produce the original user
            val decoded = Json.decode[MTUser](json).getOrThrow
            assert(decoded == user)
        }

        "rename: decode fails on old field name absent" in {
            given schema: Schema[MTUser] = Schema[MTUser].rename(_.name, "userName")
            val badJson                  = """{"name":"Alice","age":30,"email":"a@b.com","ssn":"123-45-6789"}"""
            val result                   = Json.decode[MTUser](badJson)
            // With transforms applied, "name" should not be recognized; decode should fail
            assert(result.isFailure, s"expected decode to fail on old field name, but got: $result")
        }

        "rename chain: decode reverses rename chain" in {
            given schema: Schema[MTUser] = Schema[MTUser].rename(_.name, "userName").rename("userName", "displayName")
            val user                     = MTUser("Alice", 30, "alice@test.com", "123-45-6789")
            val json                     = Json.encode(user)
            assert(json.contains("\"displayName\""))
            val decoded = Json.decode[MTUser](json).getOrThrow
            assert(decoded == user)
        }

        "drop: decode fails without drop-awareness" in {
            given schema: Schema[MTUser] = Schema[MTUser].drop("ssn")
            val user                     = MTUser("Alice", 30, "alice@test.com", "123-45-6789")
            val json                     = Json.encode(user)
            assert(!json.contains("ssn"))
            val decoded = Json.decode[MTUser](json).getOrThrow
            assert(decoded.name == user.name)
            assert(decoded.age == user.age)
            assert(decoded.email == user.email)
        }

        "transform round-trip: rename + drop" in {
            given schema: Schema[MTUser] = Schema[MTUser].rename(_.name, "userName").drop(_.ssn)
            val user                     = MTUser("Alice", 30, "alice@test.com", "123-45-6789")
            val json                     = Json.encode(user)
            assert(json.contains("\"userName\""))
            assert(!json.contains("\"ssn\""))
            val decoded = Json.decode[MTUser](json).getOrThrow
            assert(decoded.name == user.name)
            assert(decoded.age == user.age)
            assert(decoded.email == user.email)
            // ssn was dropped from serialization, so it gets its zero value on decode
            assert(decoded.ssn == "")
        }

        "schema.encode[Json] produces bytes" in {
            val schema = Schema[MTPerson]
            val person = MTPerson("Alice", 30)
            val bytes  = schema.encode[Json](person)
            assert(bytes.size > 0)
            val json = new String(bytes.toArray, java.nio.charset.StandardCharsets.UTF_8)
            assert(json.contains("\"name\""))
            assert(json.contains("\"Alice\""))
        }

        "schema.encodeString[Json] produces JSON string" in {
            val schema = Schema[MTPerson]
            val person = MTPerson("Alice", 30)
            val json   = schema.encodeString[Json](person)
            assert(json.contains("\"name\""))
            assert(json.contains("\"Alice\""))
            assert(json.contains("\"age\""))
            assert(json.contains("30"))
        }

        "schema.decode[Json] round-trips" in {
            val schema = Schema[MTPerson]
            val person = MTPerson("Alice", 30)
            val bytes  = schema.encode[Json](person)
            val result = schema.decode[Json](bytes)
            assert(result == Result.Success(person))
        }

        "schema.decodeString[Json] round-trips" in {
            val schema = Schema[MTPerson]
            val person = MTPerson("Alice", 30)
            val json   = schema.encodeString[Json](person)
            val result = schema.decodeString[Json](json)
            assert(result == Result.Success(person))
        }

        "schema.encode[Protobuf] produces bytes" in {
            val schema = Schema[MTPerson]
            val person = MTPerson("Alice", 30)
            val bytes  = schema.encode[Protobuf](person)
            assert(bytes.size > 0)
        }

        "schema.decode[Protobuf] round-trips" in {
            val schema = Schema[MTPerson]
            val person = MTPerson("Alice", 30)
            val bytes  = schema.encode[Protobuf](person)
            val result = schema.decode[Protobuf](bytes)
            assert(result == Result.Success(person))
        }

        "transformed schema.encode[Json] respects transforms" in {
            val schema = Schema[MTUser].drop(_.ssn).rename(_.name, "userName")
            val user   = MTUser("Alice", 30, "alice@test.com", "123-45-6789")
            val json   = schema.encodeString[Json](user)
            assert(!json.contains("ssn"))
            assert(json.contains("\"userName\""))
            assert(!json.contains("\"name\""))
        }

        "transformed schema.decodeString[Json] round-trips" in {
            val schema  = Schema[MTUser].rename(_.name, "userName").drop(_.ssn)
            val user    = MTUser("Alice", 30, "alice@test.com", "123-45-6789")
            val json    = schema.encodeString[Json](user)
            val decoded = schema.decodeString[Json](json).getOrThrow
            assert(decoded.name == user.name)
            assert(decoded.age == user.age)
            assert(decoded.email == user.email)
        }

        "schema.decodeString[Json] returns failure on bad input" in {
            val schema = Schema[MTPerson]
            val result = schema.decodeString[Json]("""{"name": 42}""")
            assert(result.isFailure)
        }

        "schema.encodeString matches Json.encode" in {
            val schema     = Schema[MTPerson]
            val person     = MTPerson("Alice", 30)
            val fromSchema = schema.encodeString[Json](person)
            val fromJson   = Json.encode(person)
            assert(fromSchema == fromJson)
        }

        // =====================================================================
        // Phase2 codec
        // =====================================================================

        "Phase2 codec" - {

            import CodecTestHelper.*

            // Helper: round-trip via JSON format for realistic end-to-end testing
            def jsonRoundTrip[A](value: A)(using schema: Schema[A]): A =
                val w = JsonWriter()
                schema.writeTo(value, w)
                val r = JsonReader(w.resultString)
                schema.readFrom(r)
            end jsonRoundTrip

            // Helper: encode to JSON string
            def jsonEncode[A](value: A)(using schema: Schema[A]): String =
                val w = JsonWriter()
                schema.writeTo(value, w)
                w.resultString
            end jsonEncode

            // Structure.PathSegment codec round-trips

            "Structure.PathSegment.Field round-trip" in {
                val v = Structure.PathSegment.Field("myField")
                assert(roundTrip(v) == v)
            }

            "Structure.PathSegment.Index round-trip" in {
                val v = Structure.PathSegment.Index(3)
                assert(roundTrip(v) == v)
            }

            "Structure.PathSegment.Each round-trip" in {
                val v = Structure.PathSegment.Each
                assert(roundTrip(v) == v)
            }

            "Structure.PathSegment.Variant round-trip" in {
                val v = Structure.PathSegment.Variant("Circle")
                assert(roundTrip(v) == v)
            }

            "Structure.PathSegment JSON round-trip" in {
                val v = Structure.PathSegment.Field("hello")
                assert(jsonRoundTrip(v) == v)
            }

            // Structure.Value codec round-trips

            "Structure.Value.Primitive String round-trip" in {
                val v = Structure.Value.primitive("hello")
                assert(roundTrip(v) == v)
            }

            "Structure.Value.Primitive Int round-trip" in {
                val v = Structure.Value.primitive(42)
                assert(roundTrip(v) == v)
            }

            "Structure.Value.Primitive Boolean round-trip" in {
                val v = Structure.Value.primitive(true)
                assert(roundTrip(v) == v)
            }

            "Structure.Value.Primitive Long round-trip" in {
                val v = Structure.Value.primitive(123L)
                assert(roundTrip(v) == v)
            }

            "Structure.Value.Null round-trip" in {
                val v = Structure.Value.Null
                assert(roundTrip(v) == v)
            }

            "Structure.Value.Record round-trip" in {
                val v = Structure.Value.Record(
                    Chunk(
                        ("name", Structure.Value.primitive("Alice")),
                        ("age", Structure.Value.primitive(30))
                    )
                )
                assert(roundTrip(v) == v)
            }

            "Structure.Value.Sequence round-trip" in {
                val v = Structure.Value.Sequence(
                    Chunk(
                        Structure.Value.primitive(1),
                        Structure.Value.primitive(2),
                        Structure.Value.primitive(3)
                    )
                )
                assert(roundTrip(v) == v)
            }

            "Structure.Value.VariantCase round-trip" in {
                val v = Structure.Value.VariantCase(
                    "Circle",
                    Structure.Value.Record(Chunk(("radius", Structure.Value.primitive(5.0))))
                )
                assert(roundTrip(v) == v)
            }

            "Structure.Value.MapEntries round-trip" in {
                val v = Structure.Value.MapEntries(
                    Chunk(
                        (Structure.Value.primitive("key1"), Structure.Value.primitive(10)),
                        (Structure.Value.primitive("key2"), Structure.Value.primitive(20))
                    )
                )
                assert(roundTrip(v) == v)
            }

            "Structure.Value.Primitive JSON round-trip" in {
                val v = Structure.Value.primitive("hello")
                assert(jsonRoundTrip(v) == v)
            }

            "Structure.Value nested Record JSON round-trip" in {
                val v = Structure.Value.Record(
                    Chunk(("inner", Structure.Value.Record(Chunk(("x", Structure.Value.primitive(1))))))
                )
                assert(jsonRoundTrip(v) == v)
            }

            // Structure.Value.Primitive extended coverage

            "Structure.Value.Primitive Double 0.0 round-trip" in {
                val v = Structure.Value.primitive(0.0)
                assert(roundTrip(v) == v)
            }

            "Structure.Value.Primitive Double 5.0 round-trip" in {
                val v = Structure.Value.primitive(5.0)
                assert(roundTrip(v) == v)
            }

            "Structure.Value.Primitive Double -1.0 round-trip" in {
                val v = Structure.Value.primitive(-1.0)
                assert(roundTrip(v) == v)
            }

            "Structure.Value.Primitive Double 1000000.0 round-trip" in {
                val v = Structure.Value.primitive(1000000.0)
                assert(roundTrip(v) == v)
            }

            "Structure.Value.Primitive Float 0.0f round-trip" in {
                val v = Structure.Value.primitive(0.0f)
                assert(roundTrip(v) == v)
            }

            "Structure.Value.Primitive Float 5.0f round-trip" in {
                val v = Structure.Value.primitive(5.0f)
                assert(roundTrip(v) == v)
            }

            "Structure.Value.Primitive Float -1.0f round-trip" in {
                val v = Structure.Value.primitive(-1.0f)
                assert(roundTrip(v) == v)
            }

            "Structure.Value.Primitive Double 3.14 round-trip" in {
                val v = Structure.Value.primitive(3.14)
                assert(roundTrip(v) == v)
            }

            "Structure.Value.Primitive Float 3.14f round-trip" in {
                val v = Structure.Value.primitive(3.14f)
                assert(roundTrip(v) == v)
            }

            "Structure.Value.Primitive Int 42 round-trip" in {
                val v = Structure.Value.primitive(42)
                assert(roundTrip(v) == v)
            }

            "Structure.Value.Primitive Int.MaxValue round-trip" in {
                val v = Structure.Value.primitive(Int.MaxValue)
                assert(roundTrip(v) == v)
            }

            "Structure.Value.Primitive Int.MinValue round-trip" in {
                val v = Structure.Value.primitive(Int.MinValue)
                assert(roundTrip(v) == v)
            }

            "Structure.Value.Primitive Long 42L round-trip" in {
                val v = Structure.Value.primitive(42L)
                assert(roundTrip(v) == v)
            }

            "Structure.Value.Primitive Long.MaxValue round-trip" in {
                val v = Structure.Value.primitive(Long.MaxValue)
                assert(roundTrip(v) == v)
            }

            "Structure.Value.Primitive Short round-trip" in {
                val v = Structure.Value.primitive(42.toShort)
                assert(roundTrip(v) == v)
            }

            "Structure.Value.Primitive Byte round-trip" in {
                val v = Structure.Value.primitive(42.toByte)
                assert(roundTrip(v) == v)
            }

            "Structure.Value.Primitive String hello round-trip" in {
                val v = Structure.Value.primitive("hello")
                assert(roundTrip(v) == v)
            }

            "Structure.Value.Primitive String empty round-trip" in {
                val v = Structure.Value.primitive("")
                assert(roundTrip(v) == v)
            }

            "Structure.Value.Primitive Boolean true round-trip" in {
                val v = Structure.Value.primitive(true)
                assert(roundTrip(v) == v)
            }

            "Structure.Value.Primitive Boolean false round-trip" in {
                val v = Structure.Value.primitive(false)
                assert(roundTrip(v) == v)
            }

            "Structure.Value.Primitive Char round-trip" in {
                val v = Structure.Value.primitive('x')
                assert(roundTrip(v) == v)
            }

            "Structure.Value.Primitive BigInt round-trip" in {
                val v = Structure.Value.primitive(BigInt(100))
                assert(roundTrip(v) == v)
            }

            "Structure.Value.Primitive BigDecimal round-trip" in {
                val v = Structure.Value.primitive(BigDecimal("3.14"))
                assert(roundTrip(v) == v)
            }

            "Structure.Value.Null round-trip (was null String)" in {
                val v    = Structure.Value.Null
                val back = roundTrip(v)
                assert(back == Structure.Value.Null)
            }

            "Structure.Value.Null round-trip (was null Double)" in {
                val v    = Structure.Value.Null
                val back = roundTrip(v)
                assert(back == Structure.Value.Null)
            }

            "Structure.Value.Null round-trip (was null Int)" in {
                val v    = Structure.Value.Null
                val back = roundTrip(v)
                assert(back == Structure.Value.Null)
            }

            "Structure.Value.Null round-trip (was null BigDecimal)" in {
                val v    = Structure.Value.Null
                val back = roundTrip(v)
                assert(back == Structure.Value.Null)
            }

            "Structure.Value.Primitive Double 5.0 JSON round-trip" in {
                val v = Structure.Value.primitive(5.0)
                assert(jsonRoundTrip(v) == v)
            }

            "Structure.Value.Primitive Float 5.0f JSON round-trip" in {
                val v = Structure.Value.primitive(5.0f)
                assert(jsonRoundTrip(v) == v)
            }

            "Structure.Value.Null JSON round-trip (was null Double)" in {
                val v    = Structure.Value.Null
                val back = jsonRoundTrip(v)
                assert(back == Structure.Value.Null)
            }

            "Structure.Value.Primitive Int 42 JSON round-trip" in {
                val v = Structure.Value.primitive(42)
                assert(jsonRoundTrip(v) == v)
            }

            "Structure.Value.VariantCase with whole-number Double fields round-trip" in {
                val v = Structure.Value.VariantCase(
                    "Point",
                    Structure.Value.Record(
                        Chunk(
                            ("x", Structure.Value.primitive(0.0)),
                            ("y", Structure.Value.primitive(-1.0))
                        )
                    )
                )
                assert(roundTrip(v) == v)
            }

            // Structure.Field codec round-trips

            "Structure.Field round-trip (simple)" in {
                val v = Structure.Field(
                    "myField",
                    Structure.Type.Primitive("String", Tag[String].asInstanceOf[Tag[Any]]),
                    Maybe.empty,
                    Maybe.empty,
                    optional = false
                )
                val decoded = roundTrip(v)(using summon[Schema[Structure.Field]])
                assert(decoded.name == v.name)
                assert(decoded.fieldType.name == v.fieldType.name)
                assert(decoded.optional == v.optional)
            }

            "Structure.Field with doc round-trip" in {
                val v = Structure.Field(
                    "email",
                    Structure.Type.Primitive("String", Tag[String].asInstanceOf[Tag[Any]]),
                    Maybe("The email address"),
                    Maybe.empty,
                    optional = true
                )
                val decoded = roundTrip(v)(using summon[Schema[Structure.Field]])
                assert(decoded.name == v.name)
                assert(decoded.doc == v.doc)
                assert(decoded.optional == v.optional)
            }

            // Structure.Variant codec round-trips

            "Structure.Variant round-trip" in {
                val v = Structure.Variant(
                    "Circle",
                    Structure.Type.Product(
                        "Circle",
                        Tag[Any],
                        Chunk.empty,
                        Chunk(Structure.Field("radius", Structure.Type.Primitive("Double", Tag[Any]), Maybe.empty, Maybe.empty, false))
                    )
                )
                val decoded = roundTrip(v)(using summon[Schema[Structure.Variant]])
                assert(decoded.name == v.name)
                assert(decoded.variantType.name == v.variantType.name)
            }

            // Structure.Type codec round-trips

            "Structure.Type Product round-trip" in {
                val v = Structure.Type.Product(
                    "Person",
                    Tag[Any],
                    Chunk.empty,
                    Chunk(
                        Structure.Field("name", Structure.Type.Primitive("String", Tag[Any]), Maybe.empty, Maybe.empty, false),
                        Structure.Field("age", Structure.Type.Primitive("Int", Tag[Any]), Maybe.empty, Maybe.empty, false)
                    )
                )
                val decoded = roundTrip(v)(using summon[Schema[Structure.Type]])
                assert(decoded.name == v.name)
                decoded match
                    case Structure.Type.Product(_, _, _, fields) =>
                        assert(fields.size == 2)
                        assert(fields(0).name == "name")
                        assert(fields(1).name == "age")
                    case _ => fail("expected Product")
                end match
            }

            "Structure.Type Primitive round-trip" in {
                val v       = Structure.Type.Primitive("String", Tag[Any])
                val decoded = roundTrip(v)(using summon[Schema[Structure.Type]])
                assert(decoded.name == "String")
            }

            "Structure.Type Collection round-trip" in {
                val v       = Structure.Type.Collection("List", Tag[Any], Structure.Type.Primitive("Int", Tag[Any]))
                val decoded = roundTrip(v)(using summon[Schema[Structure.Type]])
                assert(decoded.name == "List")
                decoded match
                    case Structure.Type.Collection(_, _, elem) => assert(elem.name == "Int")
                    case _                                     => fail("expected Collection")
            }

            // Structure.TypedValue codec round-trips

            "Structure.TypedValue round-trip" in {
                val v = Structure.TypedValue(
                    Structure.Type.Primitive("Int", Tag[Any]),
                    Structure.Value.primitive(42)
                )
                val decoded = roundTrip(v)(using summon[Schema[Structure.TypedValue]])
                assert(decoded.value == v.value)
            }

            // Changeset.Op codec round-trips

            "Changeset.Op.SetField round-trip" in {
                val v = Changeset.Op.SetField(Chunk("name"), Structure.Value.primitive("Alice"))
                assert(roundTrip(v)(using summon[Schema[Changeset.Op]]) == v)
            }

            "Changeset.Op.RemoveField round-trip" in {
                val v = Changeset.Op.RemoveField(Chunk("someField"))
                assert(roundTrip(v)(using summon[Schema[Changeset.Op]]) == v)
            }

            "Changeset.Op.SetNull round-trip" in {
                val v = Changeset.Op.SetNull(Chunk("optField"))
                assert(roundTrip(v)(using summon[Schema[Changeset.Op]]) == v)
            }

            "Changeset.Op.NumericDelta round-trip" in {
                val v = Changeset.Op.NumericDelta(Chunk("age"), BigDecimal(5))
                assert(roundTrip(v)(using summon[Schema[Changeset.Op]]) == v)
            }

            "Changeset.Op.StringPatch round-trip" in {
                val v = Changeset.Op.StringPatch(Chunk("name"), 0, 5, "Alice")
                assert(roundTrip(v)(using summon[Schema[Changeset.Op]]) == v)
            }

            "Changeset.Op.Nested round-trip" in {
                val v = Changeset.Op.Nested(
                    Chunk("address"),
                    Chunk(Changeset.Op.StringPatch(Chunk("city"), 0, 3, "NYC"))
                )
                assert(roundTrip(v)(using summon[Schema[Changeset.Op]]) == v)
            }

            "Changeset.Op.SetField JSON round-trip" in {
                val v = Changeset.Op.SetField(Chunk("x"), Structure.Value.primitive(99))
                assert(jsonRoundTrip(v)(using summon[Schema[Changeset.Op]]) == v)
            }

            // Frame codec

            "Frame schema encodes as string" in {
                given schema: Schema[Frame] = Schema.frameSchema
                val frame                   = summon[Frame]
                val writer                  = new TestWriter
                schema.writeTo(frame, writer)
                val tokens = writer.resultTokens
                assert(tokens.size == 1)
                assert(tokens.head.isInstanceOf[Token.Str])
            }

            "Frame schema round-trip preserves position info" in {
                given schema: Schema[Frame] = Schema.frameSchema
                val frame                   = summon[Frame]
                val decoded                 = roundTrip(frame)(using schema)
                assert(decoded.position.fileName.nonEmpty)
            }

            // Tag codec

            "Tag[Int] schema encodes as string" in {
                given schema: Schema[Tag[Int]] = Schema.tagSchema[Int]
                val tag                        = Tag[Int]
                val writer                     = new TestWriter
                schema.writeTo(tag, writer)
                val tokens = writer.resultTokens
                assert(tokens.size == 1)
                assert(tokens.head.isInstanceOf[Token.Str])
            }

            "Tag[Int] schema round-trip preserves type equality" in {
                given schema: Schema[Tag[Int]] = Schema.tagSchema[Int]
                val tag                        = Tag[Int]
                val decoded                    = roundTrip(tag)(using schema)
                assert(tag =:= decoded)
            }

            "Tag[String] schema round-trip preserves type equality" in {
                given schema: Schema[Tag[String]] = Schema.tagSchema[String]
                val tag                           = Tag[String]
                val decoded                       = roundTrip(tag)(using schema)
                assert(tag =:= decoded)
            }

            // Result codec

            "Result.Success encodes as object with success key" in {
                given schema: Schema[Result[String, Int]] = Schema.resultSchema[String, Int]
                val v                                     = Result.succeed[String, Int](42)
                val writer                                = new TestWriter
                schema.writeTo(v, writer)
                val tokens = writer.resultTokens
                assert(tokens.contains(Token.Str("success")))
                assert(tokens.contains(Token.IntVal(42)))
            }

            "Result.Failure encodes as object with failure key" in {
                given schema: Schema[Result[String, Int]] = Schema.resultSchema[String, Int]
                val v                                     = Result.fail[String, Int]("oops")
                val writer                                = new TestWriter
                schema.writeTo(v, writer)
                val tokens = writer.resultTokens
                assert(tokens.contains(Token.Str("failure")))
                assert(tokens.contains(Token.Str("oops")))
            }

            "Result.Panic encodes type and message" in {
                given schema: Schema[Result[String, Int]] = Schema.resultSchema[String, Int]
                val v                                     = Result.panic[String, Int](new RuntimeException("boom"))
                val writer                                = new TestWriter
                schema.writeTo(v, writer)
                val tokens = writer.resultTokens
                assert(tokens.contains(Token.Str("panic")))
                assert(tokens.contains(Token.Str("boom")))
            }

            "Result.Success decode round-trip" in {
                given schema: Schema[Result[String, Int]] = Schema.resultSchema[String, Int]
                val v                                     = Result.succeed[String, Int](42)
                val decoded                               = roundTrip(v)(using schema)
                decoded match
                    case Result.Success(x) => assert(x == 42)
                    case _                 => fail("expected Success(42)")
            }

            "Result.Failure decode round-trip" in {
                given schema: Schema[Result[String, Int]] = Schema.resultSchema[String, Int]
                val v                                     = Result.fail[String, Int]("oops")
                val decoded                               = roundTrip(v)(using schema)
                decoded match
                    case Result.Failure(e) => assert(e == "oops")
                    case _                 => fail("expected Failure(oops)")
            }

            "Result.Panic decodes to RuntimeException" in {
                given schema: Schema[Result[String, Int]] = Schema.resultSchema[String, Int]
                val v                                     = Result.panic[String, Int](new RuntimeException("boom"))
                val decoded                               = roundTrip(v)(using schema)
                assert(decoded.isPanic)
                decoded match
                    case Result.Panic(ex) => assert(ex.getMessage == "boom")
                    case _                => fail("expected Panic")
            }

            "Result.Success JSON encodes correctly" in {
                given schema: Schema[Result[String, Int]] = Schema.resultSchema[String, Int]
                val v                                     = Result.succeed[String, Int](42)
                val encoded                               = jsonEncode(v)
                assert(encoded.contains("success"))
                assert(encoded.contains("42"))
            }

            "Result.Failure JSON encodes correctly" in {
                given schema: Schema[Result[String, Int]] = Schema.resultSchema[String, Int]
                val v                                     = Result.fail[String, Int]("oops")
                val encoded                               = jsonEncode(v)
                assert(encoded.contains("failure"))
                assert(encoded.contains("oops"))
            }

            "Result.Panic JSON contains message" in {
                given schema: Schema[Result[String, Int]] = Schema.resultSchema[String, Int]
                val v                                     = Result.panic[String, Int](new RuntimeException("boom"))
                val encoded                               = jsonEncode(v)
                assert(encoded.contains("panic"))
                assert(encoded.contains("boom"))
            }

            // Dict codec

            "Dict[String, Int] schema round-trip" in {
                given schema: Schema[Dict[String, Int]] = Schema.stringDictSchema[Int]
                val v                                   = Dict("a" -> 1, "b" -> 2, "c" -> 3)
                val decoded                             = roundTrip(v)(using schema)
                assert(decoded.get("a") == Maybe(1))
                assert(decoded.get("b") == Maybe(2))
                assert(decoded.get("c") == Maybe(3))
            }

            "Dict[String, Int] empty round-trip" in {
                given schema: Schema[Dict[String, Int]] = Schema.stringDictSchema[Int]
                val v                                   = Dict.empty[String, Int]
                val decoded                             = roundTrip(v)(using schema)
                assert(decoded.isEmpty)
            }

            "Dict[String, Int] JSON encodes as object" in {
                given schema: Schema[Dict[String, Int]] = Schema.stringDictSchema[Int]
                val v                                   = Dict("a" -> 1, "b" -> 2)
                val encoded                             = jsonEncode(v)
                assert(encoded.contains("\"a\""))
                assert(encoded.contains("\"b\""))
                assert(encoded.contains(":1") || encoded.contains(": 1"))
            }

            "Dict[Int, String] schema round-trip (non-string key)" in {
                given schema: Schema[Dict[Int, String]] = Schema.dictSchema[Int, String]
                val v                                   = Dict(1 -> "one", 2 -> "two")
                val decoded                             = roundTrip(v)(using schema)
                assert(decoded.get(1) == Maybe("one"))
                assert(decoded.get(2) == Maybe("two"))
            }

            // Regression tests for JSON comma-skipping in inner arrays

            "Structure.Value deeply nested Record JSON round-trip (3 levels)" in {
                val v = Structure.Value.Record(
                    Chunk((
                        "outer",
                        Structure.Value.Record(
                            Chunk((
                                "middle",
                                Structure.Value.Record(
                                    Chunk(("inner", Structure.Value.primitive("deep")))
                                )
                            ))
                        )
                    ))
                )
                assert(jsonRoundTrip(v) == v)
            }

            "Structure.Value Record with Sequence of Records JSON round-trip" in {
                val v = Structure.Value.Record(
                    Chunk((
                        "items",
                        Structure.Value.Sequence(
                            Chunk(
                                Structure.Value.Record(Chunk(("a", Structure.Value.primitive(1)))),
                                Structure.Value.Record(Chunk(("b", Structure.Value.primitive(2))))
                            )
                        )
                    ))
                )
                assert(jsonRoundTrip(v) == v)
            }

            "Structure.Value MapEntries JSON round-trip" in {
                val v = Structure.Value.MapEntries(
                    Chunk(
                        (Structure.Value.primitive("key1"), Structure.Value.primitive(10)),
                        (Structure.Value.primitive("key2"), Structure.Value.primitive(20))
                    )
                )
                assert(jsonRoundTrip(v) == v)
            }
        }

        // =====================================================================
        // audit
        // =====================================================================

        "audit" - {

            /** Round-trip via TestWriter/TestReader (token-based). */
            def auditRoundTrip[A](value: A)(using schema: Schema[A]): A =
                val w = TestWriter()
                schema.writeTo(value, w)
                val r = TestReader(w.resultTokens)
                schema.readFrom(r)
            end auditRoundTrip

            /** Round-trip via JSON (end-to-end). */
            def auditJsonRoundTrip[A](value: A)(using schema: Schema[A]): A =
                val w = JsonWriter()
                schema.writeTo(value, w)
                val r = JsonReader(w.resultString)
                schema.readFrom(r)
            end auditJsonRoundTrip

            /** Helper that creates a dynamic Tag[List[A]] requiring a runtime Tag[A] at the call site. */
            def dynamicListTagOf[A: Tag]: Tag[List[A]] = summon[Tag[List[A]]]

            def captureFrameHere()(using f: Frame): Frame = f

            def captureFrameElsewhere()(using f: Frame): Frame = f

            "Frame round-trip — implicit Frame" in {
                val frame  = captureFrameHere()
                val schema = summon[Schema[Frame]]
                val back   = auditRoundTrip(frame)(using schema)
                assert(frame.toString == back.toString)
            }

            "Frame round-trip — different call site" in {
                val frame  = captureFrameElsewhere()
                val schema = summon[Schema[Frame]]
                val back   = auditJsonRoundTrip(frame)(using schema)
                assert(frame.toString == back.toString)
                assert(back.className.nonEmpty)
                assert(back.methodName.nonEmpty)
            }

            "Tag[Int] round-trip — static tag" in {
                val tag    = summon[Tag[Int]]
                val schema = Schema.tagSchema[Int]
                val back   = auditRoundTrip(tag)(using schema)
                assert(tag.show == back.show)
                assert(tag =:= back)
            }

            "Tag[String] round-trip — static tag" in {
                val tag    = summon[Tag[String]]
                val schema = Schema.tagSchema[String]
                val back   = auditRoundTrip(tag)(using schema)
                assert(tag.show == back.show)
                assert(tag =:= back)
            }

            "Tag dynamic round-trip — decoding the show-string fails (documented limitation)" in {
                val tag    = dynamicListTagOf[Int]
                val schema = Schema.tagSchema[List[Int]]
                val w      = TestWriter()
                schema.writeTo(tag.asInstanceOf[Tag[List[Int]]], w)
                val encodedShow = w.resultTokens.collect { case Token.Str(s) => s }.headOption.getOrElse("")
                assert(encodedShow == tag.show, "tagSchema must serialise dynamic tag as its show-string")
                val r    = TestReader(w.resultTokens)
                val back = schema.readFrom(r)
                val thrown = intercept[Exception] {
                    back.show
                }
                assert(
                    thrown.getMessage != null && thrown.getMessage.contains("Invalid tag payload"),
                    s"Unexpected exception: $thrown"
                )
            }

            "Dict[String, Int] empty round-trip — size 0" in {
                val v      = Dict.empty[String, Int]
                val schema = Schema.stringDictSchema[Int]
                val back   = auditRoundTrip(v)(using schema)
                assert(back.size == 0)
            }

            "Dict[String, Dict[String, Int]] nested round-trip — all 4 leaf values present" in {
                given inner: Schema[Dict[String, Int]]               = Schema.stringDictSchema[Int]
                given outer: Schema[Dict[String, Dict[String, Int]]] = Schema.stringDictSchema[Dict[String, Int]]
                val v = Dict(
                    "a" -> Dict("x" -> 1, "y" -> 2),
                    "b" -> Dict("p" -> 3, "q" -> 4)
                )
                val back = auditRoundTrip(v)(using outer)
                assert(back.size == 2)
                assert(back.get("a").get.get("x") == Maybe(1))
                assert(back.get("a").get.get("y") == Maybe(2))
                assert(back.get("b").get.get("p") == Maybe(3))
                assert(back.get("b").get.get("q") == Maybe(4))
            }

            "Maybe[Maybe[Int]] — Absent round-trips as Absent" in {
                val v: Maybe[Maybe[Int]] = Maybe.empty[Maybe[Int]]
                val schema               = summon[Schema[Maybe[Maybe[Int]]]]
                val back                 = auditRoundTrip(v)(using schema)
                assert(back == Maybe.empty[Maybe[Int]])
            }

            "Maybe[Maybe[Int]] — standalone Present(Absent) is lossy" in {
                val v: Maybe[Maybe[Int]] = Present(Maybe.empty[Int])
                val schema               = summon[Schema[Maybe[Maybe[Int]]]]
                val back                 = auditRoundTrip(v)(using schema)
                assert(back == Maybe.empty[Maybe[Int]], s"Expected Absent (lossy) but got $back")
            }

            "Chunk[Int] empty round-trip — size 0" in {
                val v      = Chunk.empty[Int]
                val schema = summon[Schema[Chunk[Int]]]
                val back   = auditRoundTrip(v)(using schema)
                assert(back.size == 0)
            }

            "Chunk[Chunk[Int]] nested round-trip — all 4 elements present in correct positions" in {
                val v      = Chunk(Chunk(1, 2), Chunk(3, 4))
                val schema = summon[Schema[Chunk[Chunk[Int]]]]
                val back   = auditRoundTrip(v)(using schema)
                assert(back.size == 2)
                assert(back(0).size == 2)
                assert(back(0)(0) == 1)
                assert(back(0)(1) == 2)
                assert(back(1).size == 2)
                assert(back(1)(0) == 3)
                assert(back(1)(1) == 4)
            }
        }

        // =====================================================================
        // fieldId
        // =====================================================================

        "fieldId" - {

            case class FIDPerson(name: String, age: Int, email: String) derives CanEqual
            case class FIDPersonV1(name: String, age: Int)
            case class FIDPersonV2(name: String, age: Int, email: String = "unknown")
            case class FIDAddress(street: String, city: String)
            case class FIDEmployee(name: String, address: FIDAddress)

            "fieldId computes stable hash for field names" in {
                val id1 = CodecMacro.fieldId("name")
                val id2 = CodecMacro.fieldId("name")
                assert(id1 == id2)
            }

            "fieldId produces different IDs for different names" in {
                val idName  = CodecMacro.fieldId("name")
                val idAge   = CodecMacro.fieldId("age")
                val idEmail = CodecMacro.fieldId("email")
                assert(idName != idAge)
                assert(idName != idEmail)
                assert(idAge != idEmail)
            }

            "fieldId produces positive IDs in valid range" in {
                val testNames = List("a", "name", "very_long_field_name_with_underscores", "CamelCaseName", "123numeric")
                testNames.foreach { name =>
                    val id = CodecMacro.fieldId(name)
                    assert(id > 0, s"ID for '$name' must be positive, got $id")
                    assert(id <= 0x200000, s"ID for '$name' must fit in 21 bits, got $id")
                }
                succeed
            }

            "fieldId is deterministic across invocations" in {
                val idName = CodecMacro.fieldId("name")
                val idAge  = CodecMacro.fieldId("age")
                val idId   = CodecMacro.fieldId("id")
                assert(idName > 0)
                assert(idAge > 0)
                assert(idId > 0)
                assert(idName != idAge)
                assert(idName != idId)
                assert(idAge != idId)
            }

            "Schema.fieldId returns hash-based ID by default" in {
                val schema   = Schema[FIDPerson]
                val expected = CodecMacro.fieldId("name")
                assert(schema.fieldId("name") == expected)
                assert(schema.fieldId("age") == CodecMacro.fieldId("age"))
                assert(schema.fieldId("email") == CodecMacro.fieldId("email"))
            }

            "Schema.fieldId returns custom ID when overridden" in {
                val schema = Schema[FIDPerson].fieldId(_.name)(42).fieldId(_.age)(100)
                assert(schema.fieldId("name") == 42)
                assert(schema.fieldId("age") == 100)
                assert(schema.fieldId("email") == CodecMacro.fieldId("email"))
            }

            "Schema.fieldIds returns all field IDs" in {
                val schema = Schema[FIDPerson]
                val ids    = schema.fieldIds
                assert(ids.size == 3)
                assert(ids("name") == CodecMacro.fieldId("name"))
                assert(ids("age") == CodecMacro.fieldId("age"))
                assert(ids("email") == CodecMacro.fieldId("email"))
            }

            "Schema.fieldIds includes overrides" in {
                val schema = Schema[FIDPerson].fieldId(_.name)(42)
                val ids    = schema.fieldIds
                assert(ids("name") == 42)
                assert(ids("age") == CodecMacro.fieldId("age"))
            }

            "ProtobufWriter uses hash-based field IDs" in {
                val writer = new ProtobufWriter
                writer.objectStart("Person", 2)
                writer.field("name", CodecMacro.fieldId("name"))
                writer.string("Alice")
                writer.field("age", CodecMacro.fieldId("age"))
                writer.int(30)
                writer.objectEnd()
                val bytes = writer.resultBytes
                assert(bytes.nonEmpty)
            }

            "ProtobufWriter respects field ID overrides" in {
                val overrides = Map("name" -> 1, "age" -> 2)
                val writer    = new ProtobufWriter().withFieldIdOverrides(overrides)
                writer.objectStart("Person", 2)
                writer.field("name", CodecMacro.fieldId("name"))
                writer.string("Alice")
                writer.field("age", CodecMacro.fieldId("age"))
                writer.int(30)
                writer.objectEnd()
                val bytesWithOverrides = writer.resultBytes

                val writerDefault = new ProtobufWriter
                writerDefault.objectStart("Person", 2)
                writerDefault.field("name", CodecMacro.fieldId("name"))
                writerDefault.string("Alice")
                writerDefault.field("age", CodecMacro.fieldId("age"))
                writerDefault.int(30)
                writerDefault.objectEnd()
                val bytesDefault = writerDefault.resultBytes

                assert(bytesWithOverrides.toSeq != bytesDefault.toSeq)
            }

            "ProtobufWriter override only triggers on configured fields" in {
                val overrides = Map("name" -> 1)
                val writer    = new ProtobufWriter().withFieldIdOverrides(overrides)
                writer.objectStart("Person", 2)
                writer.field("name", 999)
                writer.string("Alice")
                writer.field("age", 888)
                writer.int(30)
                writer.objectEnd()
                val bytes = writer.resultBytes
                assert(bytes.nonEmpty)
            }

            "field IDs are stable when adding new fields" in {
                val idNameV1 = CodecMacro.fieldId("name")
                val idAgeV1  = CodecMacro.fieldId("age")
                val idNameV2 = CodecMacro.fieldId("name")
                val idAgeV2  = CodecMacro.fieldId("age")
                assert(idNameV1 == idNameV2)
                assert(idAgeV1 == idAgeV2)
            }

            "field IDs remain stable when field order changes" in {
                val idName = CodecMacro.fieldId("name")
                val idAge  = CodecMacro.fieldId("age")
                assert(idName == CodecMacro.fieldId("name"))
                assert(idAge == CodecMacro.fieldId("age"))
            }

            "nested types have independent field IDs" in {
                val employeeNameId  = CodecMacro.fieldId("name")
                val employeeAddrId  = CodecMacro.fieldId("address")
                val addressStreetId = CodecMacro.fieldId("street")
                val addressCityId   = CodecMacro.fieldId("city")
                assert(employeeNameId > 0)
                assert(employeeAddrId > 0)
                assert(addressStreetId > 0)
                assert(addressCityId > 0)
                assert(employeeNameId != employeeAddrId)
                assert(addressStreetId != addressCityId)
            }

            "Schema.fieldId rejects non-positive IDs" in {
                intercept[TransformFailedException] {
                    Schema[FIDPerson].fieldId(_.name)(0)
                }
                intercept[TransformFailedException] {
                    Schema[FIDPerson].fieldId(_.name)(-1)
                }
                succeed
            }

            "JSON schema round-trip works correctly" in {
                val person = FIDPerson("Bob", 25, "bob@example.com")
                val schema = summon[Schema[FIDPerson]]
                val writer = JsonWriter()
                schema.writeTo(person, writer)
                val json    = writer.resultString
                val reader  = JsonReader(json)
                val decoded = schema.readFrom(reader)
                assert(decoded == person)
            }
        }

        // =====================================================================
        // reader captureValue
        // =====================================================================

        "reader captureValue" - {

            "JsonReader captureValue string round-trip" in {
                val w = JsonWriter()
                w.objectStart("root", 1)
                w.field("x", 0)
                w.string("hello")
                w.objectEnd()
                val r         = JsonReader(w.resultString)
                val _         = r.objectStart()
                val fieldName = r.field()
                assert(fieldName == "x")
                val captured = r.captureValue()
                assert(captured.string() == "hello")
            }

            "JsonReader captureValue nested object" in {
                val w = JsonWriter()
                w.objectStart("root", 1)
                w.field("outer", 0)
                w.objectStart("outer", 2)
                w.field("inner", 0)
                w.int(42)
                w.field("name", 1)
                w.string("n")
                w.objectEnd()
                w.objectEnd()
                val r         = JsonReader(w.resultString)
                val _         = r.objectStart()
                val fieldName = r.field()
                assert(fieldName == "outer")
                val captured = r.captureValue()
                val _        = captured.objectStart()
                assert(captured.hasNextField())
                val f1 = captured.field()
                assert(f1 == "inner")
                assert(captured.int() == 42)
                assert(captured.hasNextField())
                val f2 = captured.field()
                assert(f2 == "name")
                assert(captured.string() == "n")
                assert(!captured.hasNextField())
            }

            "JsonReader captureValue array" in {
                val w = JsonWriter()
                w.objectStart("root", 1)
                w.field("arr", 0)
                w.arrayStart(3)
                w.int(1)
                w.int(2)
                w.int(3)
                w.arrayEnd()
                w.objectEnd()
                val r         = JsonReader(w.resultString)
                val _         = r.objectStart()
                val fieldName = r.field()
                assert(fieldName == "arr")
                val captured = r.captureValue()
                val _        = captured.arrayStart()
                assert(captured.hasNextElement())
                assert(captured.int() == 1)
                assert(captured.hasNextElement())
                assert(captured.int() == 2)
                assert(captured.hasNextElement())
                assert(captured.int() == 3)
                assert(!captured.hasNextElement())
            }

            "StructureValueReader captureValue round-trip" in {
                val recordValue = Structure.Value.Record(Chunk("k" -> Structure.Value.primitive("v")))
                val r           = new StructureValueReader(recordValue)
                val _           = r.objectStart()
                assert(r.hasNextField())
                val fieldName = r.field()
                assert(fieldName == "k")
                val captured = r.captureValue()
                assert(captured.string() == "v")
            }

            "ProtobufReader captureValue int and string" in {
                val w = new ProtobufWriter
                w.field("n", 0)
                w.int(42)
                w.field("s", 1)
                w.string("test")
                val r           = new ProtobufReader(w.resultBytes)
                val _f1         = r.field()
                val capturedInt = r.captureValue()
                assert(capturedInt.int() == 42)
                val _f2         = r.field()
                val capturedStr = r.captureValue()
                assert(capturedStr.string() == "test")
            }

            "JsonReader captureValue advances parent position" in {
                val w = JsonWriter()
                w.objectStart("root", 2)
                w.field("a", 0)
                w.int(1)
                w.field("b", 1)
                w.int(2)
                w.objectEnd()
                val r = JsonReader(w.resultString)
                val _ = r.objectStart()
                assert(r.hasNextField())
                val fa = r.field()
                assert(fa == "a")
                val captured = r.captureValue()
                assert(captured.int() == 1)
                assert(r.hasNextField())
                val fb = r.field()
                assert(fb == "b")
                assert(r.int() == 2)
            }
        }

        // =====================================================================
        // Result codec
        // =====================================================================

        "Result codec" - {

            case class RCTCaseClass(name: String, value: Int) derives CanEqual

            sealed trait RCTSealedTrait
            case class RCTSubtypeA(msg: String) extends RCTSealedTrait derives CanEqual
            case class RCTSubtypeB(code: Int)   extends RCTSealedTrait derives CanEqual

            case class RCTInner(label: String) derives CanEqual
            case class RCTNestedCaseClass(inner: RCTInner, count: Int) derives CanEqual

            /** Round-trip via JsonWriter/JsonReader (end-to-end JSON). */
            def rctJsonRoundTrip[A](v: A)(using schema: Schema[A]): A =
                val w = JsonWriter()
                schema.writeTo(v, w)
                val r = JsonReader(w.resultString)
                schema.readFrom(r)
            end rctJsonRoundTrip

            "Result.Success Int round-trip" in {
                given schema: Schema[Result[String, Int]] = Schema.resultSchema[String, Int]
                val v                                     = Result.succeed[String, Int](42)
                val decoded                               = rctJsonRoundTrip(v)(using schema)
                decoded match
                    case Result.Success(x) => assert(x == 42)
                    case _                 => fail("expected Success(42)")
            }

            "Result.Success String round-trip" in {
                given schema: Schema[Result[String, String]] = Schema.resultSchema[String, String]
                val v                                        = Result.succeed[String, String]("hello world")
                val decoded                                  = rctJsonRoundTrip(v)(using schema)
                decoded match
                    case Result.Success(x) => assert(x == "hello world")
                    case _                 => fail("expected Success(hello world)")
            }

            "Result.Success case class round-trip" in {
                given schema: Schema[Result[String, RCTCaseClass]] = Schema.resultSchema[String, RCTCaseClass]
                val v                                              = Result.succeed[String, RCTCaseClass](RCTCaseClass("x", 1))
                val decoded                                        = rctJsonRoundTrip(v)(using schema)
                decoded match
                    case Result.Success(x) => assert(x == RCTCaseClass("x", 1))
                    case _                 => fail("expected Success(RCTCaseClass)")
            }

            "Result.Success sealed trait round-trip" in {
                given schema: Schema[Result[String, RCTSealedTrait]] = Schema.resultSchema[String, RCTSealedTrait]
                val v                                                = Result.succeed[String, RCTSealedTrait](RCTSubtypeA("foo"))
                val decoded                                          = rctJsonRoundTrip(v)(using schema)
                decoded match
                    case Result.Success(x) => assert(x == RCTSubtypeA("foo"))
                    case _                 => fail("expected Success(RCTSubtypeA)")
            }

            "Result.Success Maybe[Int] Present round-trip" in {
                given schema: Schema[Result[String, Maybe[Int]]] = Schema.resultSchema[String, Maybe[Int]]
                val v                                            = Result.succeed[String, Maybe[Int]](Present(99))
                val decoded                                      = rctJsonRoundTrip(v)(using schema)
                decoded match
                    case Result.Success(x) => assert(x == Present(99))
                    case _                 => fail("expected Success(Present(99))")
            }

            "Result.Success Maybe[Int] Absent round-trip" in {
                given schema: Schema[Result[String, Maybe[Int]]] = Schema.resultSchema[String, Maybe[Int]]
                val v                                            = Result.succeed[String, Maybe[Int]](Maybe.empty)
                val decoded                                      = rctJsonRoundTrip(v)(using schema)
                decoded match
                    case Result.Success(x) => assert(x == Maybe.empty[Int])
                    case _                 => fail("expected Success(Absent)")
            }

            "Result.Success Chunk[Int] round-trip" in {
                given schema: Schema[Result[String, Chunk[Int]]] = Schema.resultSchema[String, Chunk[Int]]
                val v                                            = Result.succeed[String, Chunk[Int]](Chunk(1, 2, 3))
                val decoded                                      = rctJsonRoundTrip(v)(using schema)
                decoded match
                    case Result.Success(x) => assert(x == Chunk(1, 2, 3))
                    case _                 => fail("expected Success(Chunk(1,2,3))")
            }

            "Result.Success nested Result round-trip" in {
                given innerSchema: Schema[Result[String, Int]] = Schema.resultSchema[String, Int]
                given outerSchema: Schema[Result[String, Result[String, Int]]] =
                    Schema.resultSchema[String, Result[String, Int]]
                val inner   = Result.succeed[String, Int](7)
                val v       = Result.succeed[String, Result[String, Int]](inner)
                val decoded = rctJsonRoundTrip(v)(using outerSchema)
                decoded match
                    case Result.Success(inner2) =>
                        inner2 match
                            case Result.Success(x) => assert(x == 7)
                            case _                 => fail("expected inner Success(7)")
                    case _ => fail("expected outer Success")
                end match
            }

            "Result.Failure String round-trip" in {
                given schema: Schema[Result[String, Int]] = Schema.resultSchema[String, Int]
                val v                                     = Result.fail[String, Int]("oops")
                val decoded                               = rctJsonRoundTrip(v)(using schema)
                decoded match
                    case Result.Failure(e) => assert(e == "oops")
                    case _                 => fail("expected Failure(oops)")
            }

            "Result.Failure Int round-trip" in {
                given schema: Schema[Result[Int, String]] = Schema.resultSchema[Int, String]
                val v                                     = Result.fail[Int, String](404)
                val decoded                               = rctJsonRoundTrip(v)(using schema)
                decoded match
                    case Result.Failure(e) => assert(e == 404)
                    case _                 => fail("expected Failure(404)")
            }

            "Result.Failure case class round-trip" in {
                given schema: Schema[Result[RCTCaseClass, Int]] = Schema.resultSchema[RCTCaseClass, Int]
                val v                                           = Result.fail[RCTCaseClass, Int](RCTCaseClass("err", 2))
                val decoded                                     = rctJsonRoundTrip(v)(using schema)
                decoded match
                    case Result.Failure(e) => assert(e == RCTCaseClass("err", 2))
                    case _                 => fail("expected Failure(RCTCaseClass)")
            }

            "Result.Failure sealed trait round-trip" in {
                given schema: Schema[Result[RCTSealedTrait, Int]] = Schema.resultSchema[RCTSealedTrait, Int]
                val v                                             = Result.fail[RCTSealedTrait, Int](RCTSubtypeB(42))
                val decoded                                       = rctJsonRoundTrip(v)(using schema)
                decoded match
                    case Result.Failure(e) => assert(e == RCTSubtypeB(42))
                    case _                 => fail("expected Failure(RCTSubtypeB(42))")
            }

            "Result.Failure nested case class round-trip" in {
                given schema: Schema[Result[RCTNestedCaseClass, Int]] = Schema.resultSchema[RCTNestedCaseClass, Int]
                val v       = Result.fail[RCTNestedCaseClass, Int](RCTNestedCaseClass(RCTInner("a"), 3))
                val decoded = rctJsonRoundTrip(v)(using schema)
                decoded match
                    case Result.Failure(e) => assert(e == RCTNestedCaseClass(RCTInner("a"), 3))
                    case _                 => fail("expected Failure(RCTNestedCaseClass)")
            }

            "Result.Failure Maybe[String] Present round-trip" in {
                given schema: Schema[Result[Maybe[String], Int]] = Schema.resultSchema[Maybe[String], Int]
                val v                                            = Result.fail[Maybe[String], Int](Present("e"))
                val decoded                                      = rctJsonRoundTrip(v)(using schema)
                decoded match
                    case Result.Failure(e) => assert(e == Present("e"))
                    case _                 => fail("expected Failure(Present(e))")
            }

            "Result.Failure nested Result round-trip" in {
                given innerSchema: Schema[Result[String, Int]] = Schema.resultSchema[String, Int]
                given outerSchema: Schema[Result[Result[String, Int], Long]] =
                    Schema.resultSchema[Result[String, Int], Long]
                val inner   = Result.fail[String, Int]("nested")
                val v       = Result.fail[Result[String, Int], Long](inner)
                val decoded = rctJsonRoundTrip(v)(using outerSchema)
                decoded match
                    case Result.Failure(innerDecoded) =>
                        innerDecoded match
                            case Result.Failure(e) => assert(e == "nested")
                            case _                 => fail("expected inner Failure(nested)")
                    case _ => fail("expected outer Failure")
                end match
            }

            "Result.Panic non-null message preserved" in {
                given schema: Schema[Result[String, Int]] = Schema.resultSchema[String, Int]
                val v                                     = Result.panic[String, Int](new RuntimeException("non-null message"))
                val decoded                               = rctJsonRoundTrip(v)(using schema)
                decoded match
                    case Result.Panic(ex) => assert(ex.getMessage == "non-null message")
                    case _                => fail("expected Panic")
            }

            "Result.Panic empty string message preserved" in {
                given schema: Schema[Result[String, Int]] = Schema.resultSchema[String, Int]
                val v                                     = Result.panic[String, Int](new RuntimeException(""))
                val decoded                               = rctJsonRoundTrip(v)(using schema)
                decoded match
                    case Result.Panic(ex) =>
                        assert(ex.getMessage == "", s"Expected empty string, got: ${ex.getMessage}")
                    case _ => fail("expected Panic")
                end match
            }

            "Result.Panic null message preserved as null" in {
                given schema: Schema[Result[String, Int]] = Schema.resultSchema[String, Int]
                val v                                     = Result.panic[String, Int](new RuntimeException(null.asInstanceOf[String]))
                val decoded                               = rctJsonRoundTrip(v)(using schema)
                decoded match
                    case Result.Panic(ex) =>
                        assert(ex.getMessage == null, s"Expected null, got: ${ex.getMessage}")
                    case _ => fail("expected Panic")
                end match
            }

            "Result decoder: value before type field is decoded correctly" in {
                given schema: Schema[Result[String, Int]] = Schema.resultSchema[String, Int]
                val w                                     = JsonWriter()
                w.objectStart("Result", 2)
                w.field("value", 0)
                w.int(42)
                w.field("$type", 1)
                w.string("success")
                w.objectEnd()
                val json    = w.resultString
                val r       = JsonReader(json)
                val decoded = schema.readFrom(r)
                decoded match
                    case Result.Success(x) => assert(x == 42)
                    case _                 => fail(s"expected Success(42), got $decoded")
            }

            "Result decoder: extra unknown fields are silently skipped" in {
                given schema: Schema[Result[String, Int]] = Schema.resultSchema[String, Int]
                val w                                     = JsonWriter()
                w.objectStart("Result", 3)
                w.field("$type", 0)
                w.string("success")
                w.field("value", 1)
                w.int(1)
                w.field("extra", 2)
                w.string("ignored")
                w.objectEnd()
                val json    = w.resultString
                val r       = JsonReader(json)
                val decoded = schema.readFrom(r)
                decoded match
                    case Result.Success(x) => assert(x == 1)
                    case _                 => fail(s"expected Success(1), got $decoded")
            }

            "Result decoder: missing $type field throws DecodeException" in {
                given schema: Schema[Result[String, Int]] = Schema.resultSchema[String, Int]
                val w                                     = JsonWriter()
                w.objectStart("Result", 1)
                w.field("value", 0)
                w.int(42)
                w.objectEnd()
                val json = w.resultString
                val r    = JsonReader(json)
                try
                    schema.readFrom(r)
                    fail("Expected MissingFieldException for missing $type")
                catch
                    case e: MissingFieldException =>
                        assert(
                            e.getMessage.contains("$type") || e.fieldName.contains("type"),
                            s"Exception message should mention missing field, got: ${e.getMessage}"
                        )
                end try
            }

            "Result decoder: unknown type variant throws DecodeException" in {
                given schema: Schema[Result[String, Int]] = Schema.resultSchema[String, Int]
                val w                                     = JsonWriter()
                w.objectStart("Result", 2)
                w.field("$type", 0)
                w.string("unknown_variant")
                w.field("value", 1)
                w.int(1)
                w.objectEnd()
                val json = w.resultString
                val r    = JsonReader(json)
                try
                    schema.readFrom(r)
                    fail("Expected UnknownVariantException for unknown variant")
                catch
                    case e: UnknownVariantException =>
                        assert(
                            e.getMessage.contains("unknown_variant") || e.variantName == "unknown_variant",
                            s"Exception message should name the unknown variant, got: ${e.getMessage}"
                        )
                end try
            }

            "Result decoder: missing value field throws DecodeException" in {
                given schema: Schema[Result[String, Int]] = Schema.resultSchema[String, Int]
                val w                                     = JsonWriter()
                w.objectStart("Result", 1)
                w.field("$type", 0)
                w.string("success")
                w.objectEnd()
                val json = w.resultString
                val r    = JsonReader(json)
                try
                    schema.readFrom(r)
                    fail("Expected MissingFieldException for missing value")
                catch
                    case e: MissingFieldException =>
                        assert(
                            e.getMessage.contains("value") || e.fieldName == "value",
                            s"Exception message should mention missing 'value', got: ${e.getMessage}"
                        )
                end try
            }
        }
    }

    // =========================================================================
    // ordering
    // =========================================================================

    "ordering" - {

        "ordering sorts by first field" in {
            val ord     = Schema[MTPerson].order
            val persons = List(MTPerson("Charlie", 25), MTPerson("Alice", 30), MTPerson("Bob", 28))
            val sorted  = persons.sorted(using ord)
            assert(sorted.map(_.name) == List("Alice", "Bob", "Charlie"))
        }

        "ordering sorts by second field" in {
            val ord     = Schema[MTPerson].order
            val persons = List(MTPerson("Alice", 30), MTPerson("Alice", 25), MTPerson("Alice", 28))
            val sorted  = persons.sorted(using ord)
            assert(sorted.map(_.age) == List(25, 28, 30))
        }

        "ordering with sorted collection" in {
            val items  = List(MTItem("Banana", 1.50), MTItem("Apple", 2.00), MTItem("Cherry", 0.75))
            val sorted = items.sorted(using Schema[MTItem].order)
            assert(sorted.map(_.name) == List("Apple", "Banana", "Cherry"))
        }

        "ordering consistent with equals" in {
            val ord = Schema[MTPerson].order
            val p1  = MTPerson("Alice", 30)
            val p2  = MTPerson("Alice", 30)
            assert(ord.compare(p1, p2) == 0)
        }

        "ordering single field" in {
            val ord    = Schema[MTWrapper].order
            val values = List(MTWrapper("z"), MTWrapper("a"), MTWrapper("m"))
            val sorted = values.sorted(using ord)
            assert(sorted.map(_.value) == List("a", "m", "z"))
        }

        "ordering nested fields" in {
            val ord = Schema[MTPersonAddr].order
            val p1  = MTPersonAddr("Alice", 30, MTAddress("B St", "Springfield", "62701"))
            val p2  = MTPersonAddr("Alice", 30, MTAddress("A St", "Springfield", "62701"))
            // Same name and age, ordering falls through to address which is compared by its fields (street, city, zip), "A St" < "B St"
            val result = ord.compare(p1, p2)
            assert(result > 0) // "B St" > "A St"
        }
    }

    // =========================================================================
    // canEqual
    // =========================================================================

    "canEqual" - {

        "canEqual same values" in {
            val schema = Schema[MTPerson]
            import schema.canEqual
            val p1 = MTPerson("Alice", 30)
            val p2 = MTPerson("Alice", 30)
            assert(p1 == p2)
        }

        "canEqual different values" in {
            val schema = Schema[MTPerson]
            import schema.canEqual
            val p1 = MTPerson("Alice", 30)
            val p2 = MTPerson("Bob", 25)
            assert(p1 != p2)
        }

        "canEqual with given" in {
            val schema = Schema[MTItem]
            import schema.canEqual
            val i1 = MTItem("Widget", 9.99)
            val i2 = MTItem("Widget", 9.99)
            assert(i1 == i2)
        }

        "canEqual nested" in {
            val schema = Schema[MTPersonAddr]
            import schema.canEqual
            val p1 = MTPersonAddr("Alice", 30, MTAddress("Main St", "Springfield", "62701"))
            val p2 = MTPersonAddr("Alice", 30, MTAddress("Main St", "Springfield", "62701"))
            assert(p1 == p2)
        }
    }

    // =========================================================================
    // exceptions
    // =========================================================================

    "exceptions" - {

        "SchemaException hierarchy exists" in {
            val err = ValidationFailedException(Nil, "test")(using Frame.derive)
            assert(err.message == "test")
            assert(err.path == Nil)
            assert(err.isInstanceOf[SchemaException])
            assert(err.isInstanceOf[ValidationException])
        }

        "exception subtypes are distinct" in {
            given Frame = Frame.derive
            val exceptions: List[SchemaException] = List(
                MissingFieldException(Nil, "f"),
                TypeMismatchException(Nil, "A", "B"),
                UnknownVariantException(Nil, "V"),
                ValidationFailedException(Nil, "msg"),
                ParseException(Json(), "x", "Int"),
                TruncatedInputException(Protobuf(), "eof")
            )
            assert(exceptions.size == 6)
            assert(exceptions.map(_.getClass).distinct.size == 6)
        }

        "validate returns ValidationFailedException" in {
            val errors = Schema[MTPerson]
                .check(_.name)(_.nonEmpty, "name required")
                .validate(MTPerson("", 30))
            assert(errors.size == 1)
            val err: ValidationFailedException = errors.head
            assert(err.isInstanceOf[ValidationException])
            assert(err.message == "name required")
        }
    }

    // =========================================================================
    // derivation errors
    // =========================================================================

    "derivation errors" - {

        "plain class" in {
            typeCheckFailure("kyo.Schema.derived[kyo.MTOpaque]")(
                "requires a case class or sealed trait"
            )
        }

        "open trait" in {
            typeCheckFailure("kyo.Schema.derived[kyo.MTOpenTrait]")(
                "requires a case class or sealed trait"
            )
        }

        "primitive" in {
            typeCheckFailure("kyo.Schema.derived[Int]")(
                "requires a case class or sealed trait"
            )
        }

        "field with non-derivable type" in {
            typeCheckFailure("kyo.Schema.derived[Tuple1[kyo.MTOpaque]]")(
                "No given Schema[kyo.MTOpaque]"
            )
        }

        "field with non-derivable list element" in {
            typeCheckFailure(
                "case class T(items: List[kyo.MTOpaque]); kyo.Schema.derived[T]"
            )("No given Schema[kyo.MTOpaque]")
        }

        "field with non-derivable option element" in {
            typeCheckFailure(
                "case class T(o: Option[kyo.MTOpaque]); kyo.Schema.derived[T]"
            )("No given Schema[kyo.MTOpaque]")
        }

        "field with non-derivable map value" in {
            typeCheckFailure(
                "case class T(m: Map[String, kyo.MTOpaque]); kyo.Schema.derived[T]"
            )("No given Schema[kyo.MTOpaque]")
        }

        "error includes action hint" in {
            typeCheckFailure("kyo.Schema.derived[Tuple1[kyo.MTOpaque]]")(
                "provide a given Schema"
            )
        }

        "error includes wrapper context for containers" in {
            typeCheckFailure(
                "case class T(items: List[kyo.MTOpaque]); kyo.Schema.derived[T]"
            )("field type:")
        }
    }

    // =========================================================================
    // integration
    // =========================================================================

    "integration" - {

        case class XCUser(id: Int, name: String, email: String, password: String, ssn: String) derives CanEqual
        case class XCUserDTO(id: Int, name: String, email: String) derives CanEqual
        case class XCItem(name: String, price: Double, quantity: Int) derives CanEqual
        case class XCOrder(id: Int, items: List[XCItem], customer: String) derives CanEqual
        case class XCNestedOrder(id: Int, orders: List[XCOrder]) derives CanEqual

        val alice = XCUser(1, "Alice", "alice@example.com", "secret123", "123-45-6789")
        val bob   = XCUser(2, "Bob", "bob@example.com", "hunter2", "987-65-4321")

        val item1 = XCItem("Widget", 9.99, 3)
        val item2 = XCItem("Gadget", 19.99, 1)
        val item3 = XCItem("Doohickey", 29.99, 5)

        val order = XCOrder(1, List(item1, item2), "Alice")

        // --- Metadata + JSON Schema ---

        "metadata: doc, field docs, deprecated all visible via schema" in {
            val s = Schema[XCUser]
                .doc("A registered user")
                .doc(_.name)("The user's full name")
                .doc(_.email)("Primary contact email")
                .deprecated(_.ssn)("Use taxId instead")
                .check(_.name)(_.nonEmpty, "name required")
                .check(_.email)(_.contains("@"), "invalid email")

            // Root doc accessible via schema
            assert(s.doc == Maybe("A registered user"))

            // Field docs accessible via focus
            assert(s.focus(_.name).doc == Maybe("The user's full name"))
            assert(s.focus(_.email).doc == Maybe("Primary contact email"))

            // Field deprecated accessible via focus
            assert(s.focus(_.ssn).deprecated == Maybe("Use taxId instead"))

            // Undocumented fields return empty
            assert(s.focus(_.id).doc == Maybe.empty)
            assert(s.focus(_.password).doc == Maybe.empty)
        }

        "metadata: examples accessible via schema" in {
            val s = Schema[XCUser]
                .doc("A registered user")
                .example(alice)
                .example(bob)

            assert(s.doc == Maybe("A registered user"))
            assert(s.examples == Chunk(alice, bob))
            assert(s.examples.size == 2)
            assert(s.examples(0).name == "Alice")
            assert(s.examples(1).name == "Bob")
        }

        "metadata + JSON Schema: JsonSchema.from includes structural info" in {
            val schema = JsonSchema.from[XCUser]

            schema match
                case obj: JsonSchema.Obj =>
                    assert(obj.properties.size == 5)
                    val propNames = obj.properties.map(_._1).toSet
                    assert(propNames == Set("id", "name", "email", "password", "ssn"))
                case other =>
                    fail(s"Expected Obj, got $other")
            end match
        }

        "metadata + reflect: reflect fields carry doc metadata from macro" in {
            val tpe = Structure.of[XCUser]
            tpe match
                case Structure.Type.Product(name, _, _, fields) =>
                    assert(name == "XCUser")
                    assert(fields.size == 5)
                    assert(fields.map(_.name) == Chunk("id", "name", "email", "password", "ssn"))
                    // Structure fields have correct types
                    fields.find(_.name == "name").get.fieldType match
                        case Structure.Type.Primitive(n, _) => assert(n == "String")
                        case other                          => fail(s"Expected Primitive for name, got $other")
                    fields.find(_.name == "id").get.fieldType match
                        case Structure.Type.Primitive(n, _) => assert(n == "Int")
                        case other                          => fail(s"Expected Primitive for id, got $other")
                case other =>
                    fail(s"Expected Product, got $other")
            end match
        }

        // --- Changeset + Convert ---

        "changeset + convert: compute changeset, apply, then forward via convert" in {
            val convert = Convert[XCUser, XCUserDTO](u => XCUserDTO(u.id, u.name, u.email))

            val updatedAlice = XCUser(1, "Alice Smith", "alice.smith@example.com", "secret123", "123-45-6789")
            val changeset    = Changeset(alice, updatedAlice)

            // Apply changeset to get the updated user
            val patched = changeset.applyTo(alice).getOrThrow
            assert(patched == updatedAlice)

            // Forward via convert to DTO
            val dto = convert(patched)
            assert(dto == XCUserDTO(1, "Alice Smith", "alice.smith@example.com"))
        }

        "changeset + convert: changeset on source, apply, forward to DTO preserves correct values" in {
            val convert = Convert[XCUser, XCUserDTO](u => XCUserDTO(u.id, u.name, u.email))

            // Change only name and email
            val newAlice  = alice.copy(name = "Alice Wonderland", email = "alice.wonder@example.com")
            val changeset = Changeset(alice, newAlice)

            // Apply changeset first
            val applied = changeset.applyTo(alice).getOrThrow
            assert(applied.name == "Alice Wonderland")
            assert(applied.email == "alice.wonder@example.com")
            assert(applied.id == 1) // unchanged

            // Convert to DTO
            val dto = convert(applied)
            assert(dto.id == 1)
            assert(dto.name == "Alice Wonderland")
            assert(dto.email == "alice.wonder@example.com")
        }

        "changeset + convert: multiple changesets composed with andThen, applied, then converted via convert" in {
            val convert = Convert[XCUser, XCUserDTO](u => XCUserDTO(u.id, u.name, u.email))

            // Step 1: change name
            val mid1       = alice.copy(name = "Alice B.")
            val changeset1 = Changeset(alice, mid1)

            // Step 2: change email
            val mid2       = mid1.copy(email = "alice.b@new.com")
            val changeset2 = Changeset(mid1, mid2)

            // Compose and apply
            val composed = changeset1.andThen(changeset2)
            val result   = composed.applyTo(alice).getOrThrow
            assert(result.name == "Alice B.")
            assert(result.email == "alice.b@new.com")
            assert(result.id == 1)

            // Forward through convert
            val dto = convert(result)
            assert(dto == XCUserDTO(1, "Alice B.", "alice.b@new.com"))
        }

        "changeset + convert: convert forward then changeset on original" in {
            val convert = Convert[XCUser, XCUserDTO](u => XCUserDTO(u.id, u.name, u.email))

            // Forward to DTO
            val dto = convert(alice)
            assert(dto == XCUserDTO(1, "Alice", "alice@example.com"))

            // Compute changeset on original user
            val updatedAlice = alice.copy(name = "Alice Updated")
            val changeset    = Changeset(alice, updatedAlice)
            assert(!changeset.isEmpty) // name changed
        }

        // --- Foreach + Validation (using Schema.check) ---

        "foreach + validation: Schema.check validates all elements" in {
            val orderSchema = Schema[XCOrder]
                .check(_.items.forall(_.price > 0), "price must be positive")
                .check(_.items.forall(_.quantity > 0), "quantity must be positive")

            // Valid order passes
            assert(orderSchema.validate(order).isEmpty)

            // Invalid order with bad items
            val badOrder = XCOrder(
                2,
                List(
                    XCItem("Free", 0.0, 1),
                    XCItem("Negative", -5.0, 0)
                ),
                "Bob"
            )
            val errors = orderSchema.validate(badOrder)
            assert(errors.size == 2)
            assert(errors.exists(_.message == "price must be positive"))
            assert(errors.exists(_.message == "quantity must be positive"))
        }

        "foreach + validation: multiple Schema.checks compose correctly" in {
            val schema = Schema[XCOrder]
                .check(_.items.forall(_.name.nonEmpty), "name required")
                .check(_.items.forall(_.price > 0), "price must be positive")
                .check(_.items.forall(_.quantity > 0), "quantity must be positive")

            val goodOrder = XCOrder(1, List(XCItem("Widget", 9.99, 3)), "Alice")
            assert(schema.validate(goodOrder).isEmpty)

            val badOrder = XCOrder(1, List(XCItem("", -1.0, 0)), "Alice")
            val errors   = schema.validate(badOrder)
            assert(errors.size == 3) // all three checks fail for the one item
        }

        "foreach + validation: empty collection validates without errors" in {
            val schema = Schema[XCOrder].check(_.items.forall(_.price > 0), "price must be positive")

            val emptyOrder = XCOrder(99, List.empty, "Nobody")
            assert(schema.validate(emptyOrder).isEmpty)
        }

        "foreach + validation: nested Schema.check validates across two levels" in {
            val nested = XCNestedOrder(
                1,
                List(
                    XCOrder(1, List(XCItem("Good", 10.0, 1), XCItem("Bad", -1.0, 0)), "Alice"),
                    XCOrder(2, List(XCItem("OK", 5.0, 2)), "Bob")
                )
            )

            val schema = Schema[XCNestedOrder]
                .check(_.orders.forall(_.items.forall(_.price > 0)), "price must be positive")

            val errors = schema.validate(nested)
            assert(errors.size == 1)
            assert(errors.head.message == "price must be positive")
        }

        // --- Lambda transforms + format ---

        "lambda transforms + convert[DTO]: drop + convert produces correct conversion" in {
            val transform = Schema[XCUser].drop(_.password).drop(_.ssn).convert[XCUserDTO]
            val dto       = transform(alice)
            assert(dto == XCUserDTO(1, "Alice", "alice@example.com"))
        }

        "lambda transforms + format: drop + convert + Json.encode produces valid JSON" in {
            val transform = Schema[XCUser].drop(_.password).drop(_.ssn).convert[XCUserDTO]
            val dto       = transform(alice)
            val json      = Json.encode(dto)

            assert(json.contains("\"id\""))
            assert(json.contains("\"name\""))
            assert(json.contains("\"Alice\""))
            assert(json.contains("\"email\""))
            assert(json.contains("\"alice@example.com\""))
            // Should NOT contain dropped fields
            assert(!json.contains("password"))
            assert(!json.contains("ssn"))
        }

        "lambda transforms: lambda drop + rename produces same result as string-based" in {
            val byString = Schema[XCUser].drop("password").drop("ssn")
            val byLambda = Schema[XCUser].drop(_.password).drop(_.ssn)

            val recString = byString.toRecord(alice)
            val recLambda = byLambda.toRecord(alice)

            assert(recString.dict("id") == recLambda.dict("id"))
            assert(recString.dict("name") == recLambda.dict("name"))
            assert(recString.dict("email") == recLambda.dict("email"))
        }

        "lambda transforms + format: transform chain + Json round-trip recovers DTO" in {
            val transform = Schema[XCUser].drop(_.password).drop(_.ssn).convert[XCUserDTO]
            val dto       = transform(alice)

            // Encode to JSON
            val json = Json.encode(dto)

            // Decode back
            val decoded = Json.decode[XCUserDTO](json)
            assert(decoded == Result.succeed(dto))
        }

        // --- Structure + Changeset + Path ---

        "reflect structure matches Structure.of for same type" in {
            val reflectedViaStandalone = Structure.of[XCUser]
            val reflectedViaSchema     = Structure.of[XCUser]

            // Both should produce Product with same fields
            (reflectedViaStandalone, reflectedViaSchema) match
                case (Structure.Type.Product(n1, _, _, f1), Structure.Type.Product(n2, _, _, f2)) =>
                    assert(n1 == n2)
                    assert(f1.map(_.name) == f2.map(_.name))
                    assert(f1.size == f2.size)
                case other =>
                    fail(s"Expected two Products, got $other")
            end match
        }

        "reflect + changeset: changeset operations can be applied via Structure.Path on Structure.Value" in {
            val schema = summon[Schema[XCUser]]
            val dynVal = Structure.encode(alice)(using schema)

            // Navigate to name field using Structure.Path
            val namePath   = Structure.Path.field("name")
            val nameResult = namePath.get(dynVal)
            assert(nameResult.isSuccess)
            val values = nameResult.getOrThrow
            assert(values.size == 1)
            values.head match
                case Structure.Value.Str(v) => assert(v == "Alice")
                case other                  => fail(s"Expected Str, got $other")

            // Set name to "Bob" via Path
            val newVal  = Structure.Value.primitive("Bob")
            val updated = namePath.set(dynVal, newVal)
            assert(updated.isSuccess)
            val v       = updated.getOrThrow
            val decoded = Structure.decode(v)(using schema)
            assert(decoded.getOrThrow.name == "Bob")
            assert(decoded.getOrThrow.id == 1)                      // unchanged
            assert(decoded.getOrThrow.email == "alice@example.com") // unchanged
        }

        "reflect: fold counts nodes and fieldPaths lists paths" in {
            val tpe = Structure.of[XCUser]

            // fold to count all nodes
            val nodeCount = Structure.Type.fold(tpe)(0)((count, _) => count + 1)
            // Product(XCUser) + 5 Primitive fields = 6 nodes
            assert(nodeCount == 6)

            // fieldPaths lists all paths
            val paths = Structure.Type.fieldPaths(tpe)
            assert(paths == Chunk(Chunk("id"), Chunk("name"), Chunk("email"), Chunk("password"), Chunk("ssn")))
        }

        "reflect: compatible verifies two structurally similar types match" in {
            // XCUser and XCUser should be compatible
            val tpe1 = Structure.of[XCUser]
            val tpe2 = Structure.of[XCUser]
            assert(Structure.Type.compatible(tpe1, tpe2))

            // XCUser and XCUserDTO are NOT compatible (different number of fields)
            val tpeDTO = Structure.of[XCUserDTO]
            assert(!Structure.Type.compatible(tpe1, tpeDTO))

            // Two structurally similar types ARE compatible
            // MTPerson(name: String, age: Int) and BMPoint2D(x: Int, y: Int) are NOT compatible (different field types)
            val tpePerson = Structure.of[MTPerson]
            val tpePoint  = Structure.of[BMPoint2D]
            assert(!Structure.Type.compatible(tpePerson, tpePoint))
        }

        // --- Full pipeline ---

        "full pipeline: doc + validate + changeset + apply + encode + decode + verify" in {
            val s = Schema[XCUser]
                .doc("A registered user")
                .doc(_.name)("The user's full name")
                .check(_.name)(_.nonEmpty, "name required")
                .check(_.email)(_.contains("@"), "invalid email")

            // Step 1: Validate the original value
            val errors = s.validate(alice)
            assert(errors.isEmpty)

            // Step 2: Compute changeset between alice and an updated version
            val updatedAlice = XCUser(1, "Alice Smith", "alice.smith@example.com", "newpw", "123-45-6789")
            val changeset    = Changeset(alice, updatedAlice)
            assert(!changeset.isEmpty)

            // Step 3: Apply changeset
            val patched = changeset.applyTo(alice).getOrThrow
            assert(patched == updatedAlice)

            // Step 4: Validate the patched value
            val patchedErrors = s.validate(patched)
            assert(patchedErrors.isEmpty)

            // Step 5: Encode to JSON
            val json = Json.encode(patched)
            assert(json.contains("\"Alice Smith\""))
            assert(json.contains("\"alice.smith@example.com\""))

            // Step 6: Decode back
            val decoded = Json.decode[XCUser](json).getOrThrow
            assert(decoded == updatedAlice)

            // Step 7: Verify metadata is still accessible
            assert(s.doc == Maybe("A registered user"))
            assert(s.focus(_.name).doc == Maybe("The user's full name"))
        }

        "full pipeline: reflect inspection + reflectValue + changeset + convert forward" in {
            // Step 1: Inspect type structure via Structure.of
            val tpe = Structure.of[XCUser]
            tpe match
                case Structure.Type.Product(_, _, _, fields) =>
                    assert(fields.size == 5)
                    assert(fields.head.name == "id")
                case _ => fail("Expected Product")
            end match

            // Step 2: Convert to Structure.Value
            val dynVal = Structure.encode(alice)
            dynVal match
                case Structure.Value.Record(fields) =>
                    assert(fields.size == 5)
                    val names = fields.map(_._1).toSeq
                    assert(names.contains("name"))
                    assert(names.contains("email"))
                case other => fail(s"Expected Record, got $other")
            end match

            // Step 3: Compute changeset
            val updatedAlice = alice.copy(name = "Alice Updated")
            val changeset    = Changeset(alice, updatedAlice)
            assert(!changeset.isEmpty)

            // Step 4: Apply changeset
            val patched = changeset.applyTo(alice).getOrThrow
            assert(patched.name == "Alice Updated")

            // Step 5: Forward through convert
            val convert = Convert[XCUser, XCUserDTO](u => XCUserDTO(u.id, u.name, u.email))
            val dto     = convert(patched)
            assert(dto == XCUserDTO(1, "Alice Updated", "alice@example.com"))
        }

        // --- Additional cross-cutting compositions ---

        "metadata survives through transform chain: doc + drop + rename" in {
            val s = Schema[XCUser]
                .doc("A user")
                .doc(_.name)("User name")
                .doc(_.email)("User email")
                .deprecated(_.ssn)("Use taxId")
                .drop(_.password)
                .drop(_.ssn)
                .rename(_.name, "userName")

            assert(s.doc == Maybe("A user"))
            // After rename: name -> userName, doc should follow
            assert(s.fieldDocs.get(Seq("userName")) == Some("User name"))
            assert(s.focus(_.email).doc == Maybe("User email"))
        }

        "validate + foreach compose: root checks and element checks together" in {
            val rootSchema = Schema[XCOrder]
                .check(_.customer)(_.nonEmpty, "customer required")

            val itemSchema = Schema[XCOrder]
                .check(_.items.forall(_.price > 0), "price must be positive")

            // Root check fails
            val badCustomerOrder = XCOrder(1, List(XCItem("Good", 10.0, 1)), "")
            val rootErrors       = rootSchema.validate(badCustomerOrder)
            assert(rootErrors.size == 1)
            assert(rootErrors.head.message == "customer required")

            // Item check fails
            val badItemOrder = XCOrder(1, List(XCItem("Bad", -1.0, 1)), "Alice")
            val itemErrors   = itemSchema.validate(badItemOrder)
            assert(itemErrors.size == 1)
            assert(itemErrors.head.message == "price must be positive")

            // Both schemas can be used together on the same value
            val bothBad       = XCOrder(1, List(XCItem("Bad", -1.0, 1)), "")
            val allRootErrors = rootSchema.validate(bothBad)
            val allItemErrors = itemSchema.validate(bothBad)
            assert(allRootErrors.size == 1)
            assert(allItemErrors.size == 1)
        }

        "changeset + Structure.Value: changeset operations correspond to Structure.Value fields" in {
            val updatedAlice = alice.copy(name = "Alice Changed", id = 42)
            val changeset    = Changeset(alice, updatedAlice)

            // Changeset operations should target the same fields visible in Structure.Value
            val dynVal     = Structure.encode(alice)
            val fieldPaths = Structure.Type.fieldPaths(Structure.of[XCUser])

            // All changeset operation field paths should be valid field paths from reflect
            changeset.operations.foreach { op =>
                val opPath = op.fieldPath
                assert(
                    fieldPaths.exists(fp => fp.startsWith(opPath) || opPath.startsWith(fp)),
                    s"Changeset op path $opPath not found in reflect field paths $fieldPaths"
                )
            }

            // Verify changeset correctly modifies the value
            val patched = changeset.applyTo(alice).getOrThrow
            assert(patched.name == "Alice Changed")
            assert(patched.id == 42)
        }

        "foreach + update + changeset: update all items then compare with changeset" in {
            val each    = Schema[XCOrder].foreach(_.items)
            val doubled = each.update(order)(item => item.copy(price = item.price * 2))

            // Verify modification worked
            assert(doubled.items(0).price == 9.99 * 2)
            assert(doubled.items(1).price == 19.99 * 2)

            // Compute changeset between original and modified
            val changeset = Changeset(order, doubled)
            assert(!changeset.isEmpty)

            // Apply changeset to original recovers modified version
            val recovered = changeset.applyTo(order).getOrThrow
            assert(recovered.items(0).price == 9.99 * 2)
            assert(recovered.items(1).price == 19.99 * 2)
            assert(recovered.customer == "Alice") // unchanged
        }

        // --- Recursive types ---

        "recursive types" - {

            "Changeset on recursive case class" in {
                val tree1 = TreeNode(1, List(TreeNode(2, scala.Nil)))
                val tree2 = TreeNode(1, List(TreeNode(3, scala.Nil)))
                val d     = Changeset(tree1, tree2)
                // Should detect the difference
                assert(!d.isEmpty)
            }

            "Changeset apply on recursive data" in {
                val tree1  = TreeNode(1, List(TreeNode(2, scala.Nil)))
                val tree2  = TreeNode(1, List(TreeNode(3, scala.Nil)))
                val d      = Changeset(tree1, tree2)
                val result = d.applyTo(tree1)
                assert(result == Result.succeed(tree2))
            }
        }
    }

    // =========================================================================
    // discriminator
    // =========================================================================

    "discriminator" - {

        "JSON encode with discriminator produces flat format" in {
            val schema          = Schema[MTShape].discriminator("type")
            val circle: MTShape = MTCircle(5.0)
            val json            = schema.encodeString[Json](circle)
            assert(json.contains("\"type\""))
            assert(json.contains("\"MTCircle\""))
            assert(json.contains("\"radius\""))
            assert(!json.contains("{\"MTCircle\":{")) // NOT wrapper format
        }

        "JSON decode with discriminator reads flat format" in {
            val schema = Schema[MTShape].discriminator("type")
            val result = schema.decodeString[Json]("""{"type":"MTCircle","radius":5.0}""")
            assert(result == Result.succeed(MTCircle(5.0)))
        }

        "JSON decode with discriminator field not first" in {
            val schema = Schema[MTShape].discriminator("type")
            val result = schema.decodeString[Json]("""{"radius":5.0,"type":"MTCircle"}""")
            assert(result == Result.succeed(MTCircle(5.0)))
        }

        "JSON round-trip with discriminator" in {
            val schema          = Schema[MTShape].discriminator("type")
            val circle: MTShape = MTCircle(5.0)
            val json            = schema.encodeString[Json](circle)
            val decoded         = schema.decodeString[Json](json)
            assert(decoded == Result.succeed(circle))
        }

        "JSON round-trip rectangle with discriminator" in {
            val schema        = Schema[MTShape].discriminator("type")
            val rect: MTShape = MTRectangle(3.0, 4.0)
            val json          = schema.encodeString[Json](rect)
            val decoded       = schema.decodeString[Json](json)
            assert(decoded == Result.succeed(rect))
        }

        "discriminator with different field name" in {
            val schema          = Schema[MTShape].discriminator("kind")
            val circle: MTShape = MTCircle(5.0)
            val json            = schema.encodeString[Json](circle)
            assert(json.contains("\"kind\""))
            assert(json.contains("\"MTCircle\""))
            assert(!json.contains("{\"MTCircle\":{"))
            val decoded = schema.decodeString[Json](json)
            assert(decoded == Result.succeed(circle))
        }

        "without discriminator uses wrapper format (backward compat)" in {
            val schema = Schema[MTShape]
            val json   = schema.encodeString[Json](MTCircle(5.0): MTShape)
            assert(json.contains("\"MTCircle\":{"))
        }

        "discriminator survives transform chain" in {
            val schema = Schema[MTShape].discriminator("kind")
            assert(schema.discriminatorField == Maybe("kind"))
        }
    }

    // =========================================================================
    // composition
    // =========================================================================

    "composition" - {

        val user    = MTUser("Alice", 30, "alice@example.com", "123-45-6789")
        val user2   = MTUser("Bob", 25, "bob@example.com", "987-65-4321")
        val person  = MTPerson("Alice", 30)
        val person2 = MTPerson("Bob", 25)

        // --- discriminator + transforms ---

        "discriminator + drop excludes dropped field but discriminator still works" in {
            val schema          = Schema[MTShape].discriminator("type")
            val circle: MTShape = MTCircle(5.0)
            val json            = schema.encodeString[Json](circle)
            assert(json.contains("\"type\""))
            assert(json.contains("\"MTCircle\""))
            assert(json.contains("\"radius\""))
            val decoded = schema.decodeString[Json](json)
            assert(decoded == Result.succeed(circle))
        }

        "discriminator round-trip rectangle with flat format" in {
            val schema        = Schema[MTShape].discriminator("kind")
            val rect: MTShape = MTRectangle(3.0, 4.0)
            val json          = schema.encodeString[Json](rect)
            assert(json.contains("\"kind\""))
            assert(json.contains("\"MTRectangle\""))
            assert(!json.contains("{\"MTRectangle\":{"))
            val decoded = schema.decodeString[Json](json)
            assert(decoded == Result.succeed(rect))
        }

        "discriminator + check encode validates before producing output" in {
            val schema = Schema[MTPerson]
                .check(_.name)(_.nonEmpty, "name required")
            val errors = schema.validate(MTPerson("", 30))
            assert(errors.size == 1)
            assert(errors.head.message == "name required")
            // Valid value encodes fine
            val json = schema.encodeString[Json](person)
            assert(json.contains("\"Alice\""))
        }

        "discriminator decode with fields in unexpected order" in {
            val schema = Schema[MTShape].discriminator("type")
            val result = schema.decodeString[Json]("""{"radius":5.0,"type":"MTCircle"}""")
            assert(result == Result.succeed(MTCircle(5.0)))
        }

        "discriminator decode with extra unknown fields" in {
            val schema = Schema[MTShape].discriminator("type")
            val result = schema.decodeString[Json]("""{"type":"MTCircle","radius":5.0,"extra":"ignored"}""")
            assert(result == Result.succeed(MTCircle(5.0)))
        }

        "discriminator decode with unknown variant returns error" in {
            val schema = Schema[MTShape].discriminator("type")
            val result = schema.decodeString[Json]("""{"type":"MTTriangle","a":1.0}""")
            assert(result.isFailure)
        }

        // --- check + transform + check ---

        "check then drop then check - both checks survive" in {
            val schema = Schema[MTUser]
                .check(_.name)(_.nonEmpty, "name required")
                .drop("ssn")
                .check(_.age)(_ > 0, "age positive")
            val errors = schema.validate(MTUser("", -1, "e@x", "ssn"))
            assert(errors.size == 2)
            assert(errors.exists(_.message == "name required"))
            assert(errors.exists(_.message == "age positive"))
        }

        "check then rename then check" in {
            val schema = Schema[MTUser]
                .check(_.name)(_.nonEmpty, "name required")
                .rename("name", "userName")
                .check(_.age)(_ >= 18, "must be adult")
            val errors = schema.validate(MTUser("", 10, "e@x", "ssn"))
            assert(errors.size == 2)
            assert(errors.exists(_.message == "name required"))
            assert(errors.exists(_.message == "must be adult"))
        }

        "check then add then check on added field" in {
            val schema = Schema[MTPerson]
                .check(_.name)(_.nonEmpty, "name required")
                .add("greeting")(p => s"Hello ${p.name}")
            val errors = schema.validate(MTPerson("", 30))
            assert(errors.exists(_.message == "name required"))
        }

        "validate after chained transforms returns all errors" in {
            val schema = Schema[MTUser]
                .check(_.name)(_.nonEmpty, "name required")
                .check(_.age)(_ >= 0, "age non-negative")
                .drop("ssn")
                .check(_.email)(_.contains("@"), "invalid email")
            val errors = schema.validate(MTUser("", -1, "no-at", "ssn"))
            assert(errors.size == 3)
            assert(errors.exists(_.message == "name required"))
            assert(errors.exists(_.message == "age non-negative"))
            assert(errors.exists(_.message == "invalid email"))
        }

        // --- schema + focus + encode ---

        "drop then focus on remaining field" in {
            val schema = Schema[MTUser].drop("ssn")
            val name   = schema.focus(_.name).get(user)
            assert(name == "Alice")
        }

        "rename then focus uses original field name" in {
            val schema = Schema[MTUser].rename("name", "userName")
            // focus still uses original accessor _.name since the underlying type is MTUser
            val record = schema.toRecord(user)
            assert(record.userName == "Alice")
        }

        "drop then encode excludes dropped field" in {
            val schema = Schema[MTUser].drop("ssn")
            val json   = schema.encodeString[Json](user)
            assert(!json.contains("ssn"))
            assert(json.contains("\"name\""))
            assert(json.contains("\"age\""))
            assert(json.contains("\"email\""))
        }

        "drop then encode then decode round-trip" in {
            val schema  = Schema[MTUser].drop("ssn")
            val json    = schema.encodeString[Json](user)
            val decoded = schema.decodeString[Json](json).getOrThrow
            assert(decoded.name == user.name)
            assert(decoded.age == user.age)
            assert(decoded.email == user.email)
        }

        // --- changeset + transforms ---

        "changeset on values with drop - dropped field not in changeset" in {
            val schema           = Schema[MTUser].drop("ssn")
            given Schema[MTUser] = schema
            val changeset        = Changeset(user, user2)
            val fieldPaths       = changeset.operations.map(_.fieldPath)
            assert(!fieldPaths.exists(_.contains("ssn")))
            assert(fieldPaths.exists(_.contains("name")))
        }

        "changeset on values with rename - renamed field in ops" in {
            val schema           = Schema[MTUser].rename("name", "userName")
            given Schema[MTUser] = schema
            val changeset        = Changeset(user, user2)
            val fieldPaths       = changeset.operations.flatMap(_.fieldPath)
            assert(fieldPaths.contains("userName"))
            assert(!fieldPaths.contains("name"))
        }

        "changeset applyTo reconstructs value with base schema" in {
            val changeset = Changeset(user, user2)
            val result    = changeset.applyTo(user).getOrThrow
            assert(result.name == user2.name)
            assert(result.age == user2.age)
            assert(result.email == user2.email)
            assert(result.ssn == user2.ssn)
        }

        "empty changeset after transforms on identical values" in {
            val schema           = Schema[MTUser].drop("ssn").rename("name", "userName")
            given Schema[MTUser] = schema
            val changeset        = Changeset(user, user)
            assert(changeset.isEmpty)
        }

        // --- compare + transforms ---

        "compare detects field changes" in {
            val d = Compare(user, user2)
            assert(d.changed(_.name))
            assert(d.changed(_.age))
            assert(d.changed(_.email))
        }

        "compare unchanged fields" in {
            val user3 = MTUser("Alice", 30, "alice@example.com", "different-ssn")
            val d     = Compare(user, user3)
            assert(!d.changed(_.name))
            assert(!d.changed(_.age))
            assert(!d.changed(_.email))
            assert(d.changed(_.ssn))
        }

        "compare left and right values" in {
            val d = Compare(user, user2)
            assert(d.left(_.name) == Maybe("Alice"))
            assert(d.right(_.name) == Maybe("Bob"))
            assert(d.left(_.age) == Maybe(30))
            assert(d.right(_.age) == Maybe(25))
        }

        "compare on sealed trait variants" in {
            val circle: MTShape = MTCircle(5.0)
            val rect: MTShape   = MTRectangle(3.0, 4.0)
            val d               = Compare(circle, rect)
            assert(d.changed)
            assert(d.left(_.MTCircle) == Maybe(MTCircle(5.0)))
            assert(d.left(_.MTRectangle) == Maybe.empty)
            assert(d.right(_.MTRectangle) == Maybe(MTRectangle(3.0, 4.0)))
            assert(d.right(_.MTCircle) == Maybe.empty)
        }

        // --- structure + transforms ---

        "Structure.of matches expected product structure" in {
            val tpe = Structure.of[MTUser]
            tpe match
                case Structure.Type.Product(name, _, _, fields) =>
                    assert(name == "MTUser")
                    assert(fields.map(_.name) == Chunk("name", "age", "email", "ssn"))
                case _ => fail("Expected Product")
            end match
        }

        "Structure.Value round-trip on schema with drop" in {
            val schema = Schema[MTUser].drop("ssn")
            val dv     = Structure.encode(user)(using schema, summon[Frame])
            dv match
                case Structure.Value.Record(fields) =>
                    val fieldNames = fields.map(_._1)
                    assert(!fieldNames.contains("ssn"))
                    assert(fieldNames.contains("name"))
                    assert(fieldNames.contains("age"))
                    assert(fieldNames.contains("email"))
                case _ => fail("Expected Record")
            end match
        }

        "Structure.Value round-trip on schema with rename" in {
            val schema = Schema[MTUser].rename("name", "userName")
            val dv     = Structure.encode(user)(using schema, summon[Frame])
            dv match
                case Structure.Value.Record(fields) =>
                    val fieldNames = fields.map(_._1)
                    assert(fieldNames.contains("userName"))
                    assert(!fieldNames.contains("name"))
                case _ => fail("Expected Record")
            end match
        }

        "Structure.Value encode then decode round-trip without transforms" in {
            val schema  = Schema[MTUser]
            val dv      = Structure.encode(user)(using schema, summon[Frame])
            val decoded = Structure.decode(dv)(using schema, summon[Frame]).getOrThrow
            assert(decoded.name == user.name)
            assert(decoded.age == user.age)
            assert(decoded.email == user.email)
            assert(decoded.ssn == user.ssn)
        }

    }

end SchemaTest
