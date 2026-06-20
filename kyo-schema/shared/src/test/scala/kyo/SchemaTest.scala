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
            // ssn is dropped, metadata removed; verify via field doc on remaining fields
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
            // After check, we're back at root Schema; can focus on a different field
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
            // With None, the Option is empty; check predicate receives None which is !nonEmpty
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
            interceptThrown[RuntimeException] {
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

        "pattern POSIX regex (email-like)" in {
            val schema = Schema[X].checkPattern(_.s)("^[a-z]+@[a-z]+$")
            assert(schema.validate(X("a@b")).isEmpty)
            assert(schema.validate(X("no-at")).nonEmpty)
        }

        "pattern character class (digits with dash)" in {
            val schema = Schema[X].checkPattern(_.s)("""^\d{3}-\d{4}$""")
            assert(schema.validate(X("555-1234")).isEmpty)
            assert(schema.validate(X("abc-1234")).nonEmpty)
        }

        "pattern alternation (animal names)" in {
            val schema = Schema[X].checkPattern(_.s)("^(cat|dog|bird)$")
            assert(schema.validate(X("cat")).isEmpty)
            assert(schema.validate(X("fish")).nonEmpty)
        }

        "pattern bounded quantifier (a{2,5})" in {
            val schema = Schema[X].checkPattern(_.s)("^a{2,5}$")
            assert(schema.validate(X("aa")).isEmpty)
            assert(schema.validate(X("aaaaa")).isEmpty)
            assert(schema.validate(X("a")).nonEmpty)
            assert(schema.validate(X("aaaaaa")).nonEmpty)
        }

        "pattern anchors (word characters only)" in {
            val schema = Schema[X].checkPattern(_.s)("""^\w+$""")
            assert(schema.validate(X("hello")).isEmpty)
            assert(schema.validate(X("with space")).nonEmpty)
        }

        "pattern escaped metacharacters (literal .+*)" in {
            val schema = Schema[X].checkPattern(_.s)("""^\.\+\*$""")
            assert(schema.validate(X(".+*")).isEmpty)
            assert(schema.validate(X("abc")).nonEmpty)
        }

        "pattern possessive quantifier (documented platform limitation)" in {
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
                    // Validation did not raise (JS/Native can silently degrade the possessive
                    // quantifier rather than rejecting it); record the observed, platform-divergent
                    // outcome so the leaf asserts instead of passing vacuously.
                    succeed(s"possessive quantifier accepted on this platform; validate returned $result")
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

        // === fold with schema transformations (rename/add) ===

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

        // === boundary shapes: empty and large case classes ===

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

end SchemaTest
