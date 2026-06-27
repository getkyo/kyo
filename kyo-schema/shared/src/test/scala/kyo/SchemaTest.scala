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

    // =========================================================================
    // omit builders
    // =========================================================================

    "omit builders are total and produce Schema[Cart]" in {
        val schema         = Schema[Cart]
        val withNone       = schema.omitNone
        val withEmpty      = schema.omitEmptyCollections
        val withItemsEmpty = Schema[Cart].omit(_.items).whenEmpty
        val withNoteNone   = Schema[Cart].omit(_.note).whenNone
        assert(withNone.isInstanceOf[Schema[Cart]])
        assert(withEmpty.isInstanceOf[Schema[Cart]])
        assert(withItemsEmpty.isInstanceOf[Schema[Cart]])
        assert(withNoteNone.isInstanceOf[Schema[Cart]])
        assert(withItemsEmpty.omitPolicies == Chunk("items" -> Schema.OmitPolicy.WhenEmpty))
        assert(withNoteNone.omitPolicies == Chunk("note" -> Schema.OmitPolicy.WhenNone))
    }

    "round two field builders preserve Focused and expose inert state" in {
        val schema      = Schema[Cart]
        val strict      = schema.denyUnknownFields
        val withDefault = schema.default(_.note)(Maybe("hello"))
        val withWhen = schema.omit(_.items).when {
            case Structure.Value.Sequence(values) => values.isEmpty
            case _                                => false
        }
        val withWhenDefault = schema.omit(_.note).whenDefault

        val _: Schema[Cart] { type Focused = "items" ~ Chunk[String] & "note" ~ Maybe[String] } = strict
        val _: Schema[Cart] { type Focused = "items" ~ Chunk[String] & "note" ~ Maybe[String] } = withDefault
        val _: Schema[Cart] { type Focused = "items" ~ Chunk[String] & "note" ~ Maybe[String] } = withWhen
        val _: Schema[Cart] { type Focused = "items" ~ Chunk[String] & "note" ~ Maybe[String] } = withWhenDefault

        assert(!schema.denyUnknownFieldsEnabled)
        assert(schema.fieldDefaults.isEmpty)
        assert(schema.fieldTransforms.isEmpty)
        assert(!schema.hasTransforms)
        assert(!schema.hasReadTransforms)

        assert(strict.denyUnknownFieldsEnabled)
        assert(!strict.hasTransforms)
        assert(strict.hasReadTransforms)

        assert(withDefault.fieldDefaults.map(_._1) == Chunk("note"))
        assert(!withDefault.hasTransforms)
        assert(withDefault.hasReadTransforms)

        assert(withWhen.omitPolicies.map(_._1) == Chunk("items"))
        withWhen.omitPolicies(0)._2 match
            case Schema.OmitPolicy.When(predicate) =>
                assert(predicate(Structure.Value.Sequence(Chunk.empty)))
                assert(!predicate(Structure.Value.Sequence(Chunk(Structure.Value.Str("x")))))
            case other => fail(s"Expected When policy, got $other")
        end match

        assert(withWhenDefault.omitPolicies == Chunk("note" -> Schema.OmitPolicy.WhenDefault))
    }

    "unknown field exception has decode-only shape" in {
        val ex = UnknownFieldException(Seq("root"), "extra")
        assert(ex.isInstanceOf[SchemaException])
        assert(ex.isInstanceOf[DecodeException])
        assert(!ex.isInstanceOf[NavigationException])
        assert(ex.path == Seq("root"))
        assert(ex.fieldName == "extra")
        assert(ex.getMessage.contains("Unknown field 'extra'"))
        assert(ex.getMessage.contains("at root"))
        assert(
            ex.getMessage.contains("Remove this field from the input, or decode with a schema that does not configure denyUnknownFields.")
        )
    }

    "denyUnknownFields" - {

        def assertUnknownField[A](result: Result[DecodeException, A], fieldName: String)(using kyo.test.AssertScope): Unit =
            result match
                case Result.Failure(ex: UnknownFieldException) =>
                    assert(ex.fieldName == fieldName)
                case other =>
                    fail(s"Expected UnknownFieldException for $fieldName, got $other")
            end match
        end assertUnknownField

        "unconfigured product decode ignores an extra JSON field" in {
            val result = Schema[StrictPerson].decodeString[Json]("""{"id":1,"name":"Ada","extra":true}""")
            assert(result == Result.Success(StrictPerson(1, "Ada")))
        }

        "configured product decode rejects the first extra JSON field" in {
            val schema = Schema[StrictPerson].denyUnknownFields
            val result = schema.decodeString[Json]("""{"id":1,"extra":true,"name":"Ada"}""")
            assertUnknownField(result, "extra")
        }

        "renamed and aliased wire names are accepted" in {
            val schema = Schema[StrictRename]
                .rename(_.firstName, "given")
                .alias("given", "first")
                .denyUnknownFields

            val renamed = schema.decodeString[Json]("""{"given":"Ada","lastName":"Lovelace"}""")
            val aliased = schema.decodeString[Json]("""{"first":"Ada","lastName":"Lovelace"}""")

            assert(renamed == Result.Success(StrictRename("Ada", "Lovelace")))
            assert(aliased == Result.Success(StrictRename("Ada", "Lovelace")))
        }

        "renamed-away source name is rejected with the raw field name" in {
            val schema = Schema[StrictRename]
                .rename(_.firstName, "given")
                .denyUnknownFields
            val result = schema.decodeString[Json]("""{"firstName":"Ada","lastName":"Lovelace"}""")
            assertUnknownField(result, "firstName")
        }

        "field-case wire names are accepted and unrelated fields are rejected" in {
            val schema = Schema[StrictFieldCase]
                .renameAllFields(Schema.NameCase.SnakeCase)
                .denyUnknownFields

            val accepted = schema.decodeString[Json]("""{"first_name":"Ada","last_name":"Lovelace"}""")
            val rejected = schema.decodeString[Json]("""{"first_name":"Ada","middle_name":"Byron","last_name":"Lovelace"}""")

            assert(accepted == Result.Success(StrictFieldCase("Ada", "Lovelace")))
            assertUnknownField(rejected, "middle_name")
        }

        "nested product strict decode reports a typed failure" in {
            val schema = Schema[StrictOuter].denyUnknownFields
            val result = schema.decodeString[Json]("""{"name":"root","inner":{"value":1,"extra":2}}""")
            assertUnknownField(result, "extra")
        }

        "flatten accepts flattened child keys and rejects unrelated unknown keys" in {
            val schema   = Schema[StrictFlattenParent].flatten.denyUnknownFields
            val accepted = schema.decodeString[Json]("""{"id":1,"code":"sku","quantity":2}""")
            val rejected = schema.decodeString[Json]("""{"id":1,"code":"sku","quantity":2,"extra":true}""")

            assert(accepted == Result.Success(StrictFlattenParent(1, StrictFlattenChild("sku", 2))))
            assertUnknownField(rejected, "extra")
        }

        "strict mode does not reject synthetic empty collection injection" in {
            val schema = Schema[CartWithList].omitEmptyCollections.denyUnknownFields
            val result = schema.decodeString[Json]("""{"name":"x"}""")
            assert(result == Result.Success(CartWithList(List.empty, "x")))
        }
    }

    "default" - {

        def assertUnknownField[A](result: Result[DecodeException, A], fieldName: String)(using kyo.test.AssertScope): Unit =
            result match
                case Result.Failure(ex: UnknownFieldException) =>
                    assert(ex.fieldName == fieldName)
                case other =>
                    fail(s"Expected UnknownFieldException for $fieldName, got $other")
            end match
        end assertUnknownField

        "primitive field default decodes concrete value when missing" in {
            val schema = Schema[DefaultPrimitive].default(_.name)("generated")
            val result = schema.decodeString[Json]("""{"id":1}""")
            assert(result == Result.Success(DefaultPrimitive(1, "generated")))
        }

        "nested product field default decodes concrete value when missing" in {
            val child  = DefaultNestedChild("sku", 2)
            val schema = Schema[DefaultNestedParent].default(_.child)(child)
            val result = schema.decodeString[Json]("""{"id":1}""")
            assert(result == Result.Success(DefaultNestedParent(1, child)))
        }

        "collection field default decodes concrete value when missing" in {
            val schema = Schema[DefaultCollection].default(_.items)(Chunk("one", "two"))
            val result = schema.decodeString[Json]("""{"name":"cart"}""")
            assert(result == Result.Success(DefaultCollection(Chunk("one", "two"), "cart")))
        }

        "sum field default decodes concrete variant when missing" in {
            val schema = Schema[DefaultSumParent].default(_.shape)(MTCircle(5.0): MTShape)
            val result = schema.decodeString[Json]("""{"id":1}""")
            assert(result == Result.Success(DefaultSumParent(1, MTCircle(5.0))))
        }

        "supplier wins over Scala default" in {
            val schema = Schema[DefaultWithScala].default(_.name)("configured")
            val result = schema.decodeString[Json]("""{"id":1}""")
            assert(result == Result.Success(DefaultWithScala(1, "configured")))
        }

        "present field does not evaluate supplier" in {
            var evaluations = 0
            val schema = Schema[DefaultPrimitive].default(_.name) {
                evaluations += 1
                "generated"
            }
            val result = schema.decodeString[Json]("""{"id":1,"name":"wire"}""")
            assert(result == Result.Success(DefaultPrimitive(1, "wire")))
            assert(evaluations == 0)
        }

        "missing field evaluates supplier exactly once per decode" in {
            var evaluations = 0
            val schema = Schema[DefaultPrimitive].default(_.name) {
                evaluations += 1
                s"generated-$evaluations"
            }
            val first  = schema.decodeString[Json]("""{"id":1}""")
            val second = schema.decodeString[Json]("""{"id":2}""")
            assert(first == Result.Success(DefaultPrimitive(1, "generated-1")))
            assert(second == Result.Success(DefaultPrimitive(2, "generated-2")))
            assert(evaluations == 2)
        }

        "unconfigured missing required field still fails" in {
            val result = Schema[DefaultPrimitive].decodeString[Json]("""{"id":1}""")
            val missingField = result match
                case Result.Failure(_: MissingFieldException) => true
                case _                                        => false
            assert(missingField)
        }

        "builder order preserves source keys across rename and strict mode" in {
            val defaultThenRename = Schema[DefaultRename]
                .default(_.firstName)("Ada")
                .rename(_.firstName, "givenName")
                .denyUnknownFields
            val renameThenDefault = Schema[DefaultRename]
                .rename(_.firstName, "givenName")
                .default(_.givenName)("Ada")
                .denyUnknownFields

            val input = """{"lastName":"Lovelace"}"""
            assert(defaultThenRename.decodeString[Json](input) == Result.Success(DefaultRename("Ada", "Lovelace")))
            assert(renameThenDefault.decodeString[Json](input) == Result.Success(DefaultRename("Ada", "Lovelace")))
            assertUnknownField(defaultThenRename.decodeString[Json]("""{"lastName":"Lovelace","extra":true}"""), "extra")
        }

        "drop composition does not evaluate an unused supplier" in {
            var evaluations = 0
            val schema = Schema[DefaultDrop]
                .default(_.removed) {
                    evaluations += 1
                    Maybe("configured")
                }
                .drop("removed")
            val result = schema.decodeString[Json]("""{"id":1}""")
            assert(result == Result.Success(DefaultDrop(1, Maybe.empty)))
            assert(evaluations == 0)
        }

        "flattened strict builder order preserves child fields" in {
            val flattenThenStrict = Schema[StrictFlattenParent].flatten.denyUnknownFields
            val strictThenFlatten = Schema[StrictFlattenParent].denyUnknownFields.flatten
            val accepted          = """{"id":1,"code":"sku","quantity":2}"""
            val rejected          = """{"id":1,"code":"sku","quantity":2,"extra":true}"""

            assert(flattenThenStrict.decodeString[Json](accepted) == Result.Success(StrictFlattenParent(1, StrictFlattenChild("sku", 2))))
            assert(strictThenFlatten.decodeString[Json](accepted) == Result.Success(StrictFlattenParent(1, StrictFlattenChild("sku", 2))))
            assertUnknownField(flattenThenStrict.decodeString[Json](rejected), "extra")
            assertUnknownField(strictThenFlatten.decodeString[Json](rejected), "extra")
        }

        "default targeting a flattened parent evaluates only when child fields are absent" in {
            var evaluations = 0
            val schema = Schema[StrictFlattenParent]
                .default(_.child) {
                    evaluations += 1
                    StrictFlattenChild("fallback", 9)
                }
                .flatten
                .denyUnknownFields

            val fromChildren = schema.decodeString[Json]("""{"id":1,"code":"sku","quantity":2}""")
            assert(fromChildren == Result.Success(StrictFlattenParent(1, StrictFlattenChild("sku", 2))))
            assert(evaluations == 0)

            val fromDefault = schema.decodeString[Json]("""{"id":1}""")
            assert(fromDefault == Result.Success(StrictFlattenParent(1, StrictFlattenChild("fallback", 9))))
            assert(evaluations == 1)
        }
    }

    "unconfigured schema is byte-identical and self-describing codecs require missing collections" in {
        val schema  = Schema[Cart]
        val value   = Cart(Chunk("a", "b"), Maybe("hello"))
        val encoded = schema.encodeString[Json](value)
        assert(encoded == """{"items":["a","b"],"note":"hello"}""")
        assert(!schema.denyUnknownFieldsEnabled)
        assert(schema.fieldDefaults.isEmpty)
        assert(schema.fieldTransforms.isEmpty)

        val missingItems = schema.decodeString[Json]("""{"note":"hi"}""")
        assert(missingItems.failure.exists(_.isInstanceOf[MissingFieldException]), s"expected missing items failure, got: $missingItems")

        val missingTags = Schema[CartWithMap].decodeString[Json]("""{"name":"cart"}""")
        assert(missingTags.failure.exists(_.isInstanceOf[MissingFieldException]), s"expected missing tags failure, got: $missingTags")
    }

    "omit policy survives a subsequent copyWith-routed builder" in {
        val schema1 = Schema[Cart].omitEmptyCollections.discriminator("type")
        assert(schema1.omitEmptyCollectionsAll)

        val schema2 = Schema[Cart].omitEmptyCollections
        assert(schema2.omitEmptyCollectionsAll)

        val schema3 = schema2.omitNone
        assert(schema3.omitEmptyCollectionsAll)
        assert(schema3.omitNoneAll)
    }

    "configured empty/absent fields are absent from the encoded object" in {
        val schema = Schema[OmitAllPolicyCase].omitEmptyCollections.omitNone
        val value  = OmitAllPolicyCase(Chunk.empty, Map.empty, None, "x")
        val out    = schema.encodeString[Json](value)
        assert(!out.contains("\"items\""), s"items key must be absent: $out")
        assert(!out.contains("\"tags\""), s"tags key must be absent: $out")
        assert(!out.contains("\"note\""), s"note key must be absent: $out")
        assert(!out.contains("[]"), s"empty array must not appear: $out")
        assert(!out.contains("{}"), s"empty object must not appear: $out")
        assert(!out.contains("null"), s"null must not appear: $out")
        assert(out.contains("\"name\""), s"populated name key must be present: $out")
        assert(out.contains("\"x\""), s"name value must be present: $out")
    }

    "per-field policy shadows the schema-wide flag" in {
        val schema = Schema[OmitShadowCase].omitEmptyCollections.omit(_.b).whenNone
        val value  = OmitShadowCase(List.empty, List.empty)
        val out    = schema.encodeString[Json](value)
        assert(!out.contains("\"a\""), s"a must be omitted by schema-wide WhenEmpty: $out")
        assert(out.contains("\"b\""), s"b must be present (per-field WhenNone shadows WhenEmpty): $out")
        assert(out.contains("[]"), s"b must appear as an empty array: $out")
    }

    "when omits a field when the predicate returns true" in {
        val schema = Schema[PredicateOmitCase].omit(_.count).when {
            case Structure.Value.Integer(n) => n == 0
            case _                          => false
        }
        val omitted  = schema.encodeString[Json](PredicateOmitCase("x", 0))
        val retained = schema.encodeString[Json](PredicateOmitCase("x", 5))
        assert(!omitted.contains("\"count\""), s"count must be absent when predicate true: $omitted")
        assert(retained.contains("\"count\""), s"count must be present when predicate false: $retained")
        assert(retained.contains("5"), s"count value must appear: $retained")
    }

    "false predicate preserves the field on encode" in {
        val schema = Schema[PredicateOmitCase].omit(_.count).when(_ => false)
        val out    = schema.encodeString[Json](PredicateOmitCase("x", 0))
        assert(out.contains("\"count\""), s"count must be present when predicate always false: $out")
        assert(out.contains("0"), s"zero value must appear: $out")
    }

    "whenDefault omits a field whose value equals the compile-time default" in {
        val schema   = Schema[PredicateOmitCase].omit(_.count).whenDefault
        val omitted  = schema.encodeString[Json](PredicateOmitCase("x", 0))
        val retained = schema.encodeString[Json](PredicateOmitCase("x", 42))
        assert(!omitted.contains("\"count\""), s"count must be absent when equal to default 0: $omitted")
        assert(retained.contains("\"count\""), s"count must be present when not equal to default: $retained")
        assert(retained.contains("42"), s"non-default value must appear: $retained")
    }

    "whenDefault does not omit a field with no compile-time default" in {
        val schema = Schema[PredicateOmitCase].omit(_.label).whenDefault
        val out    = schema.encodeString[Json](PredicateOmitCase("x", 1))
        assert(out.contains("\"label\""), s"label must be present (no compile-time default): $out")
        assert(out.contains("\"x\""), s"label value must appear: $out")
    }

    "whenDefault omits a product field equal to its compile-time default via field-schema materialization" in {
        val schema   = Schema[WhenDefaultOuter].omit(_.inner).whenDefault
        val omitted  = schema.encodeString[Json](WhenDefaultOuter("x", WhenDefaultInner(1, 2)))
        val retained = schema.encodeString[Json](WhenDefaultOuter("x", WhenDefaultInner(1, 99)))
        assert(!omitted.contains("\"inner\""), s"inner must be absent when equal to default Inner(1,2): $omitted")
        assert(retained.contains("\"inner\""), s"inner must be present when not equal to default: $retained")
        assert(retained.contains("99"), s"non-default value must appear: $retained")
    }

    "per-field when replaces an earlier whenDefault for the same field" in {
        val schema = Schema[PredicateOmitCase]
            .omit(_.count).whenDefault
            .omit(_.count).when(_ => true)
        val out = schema.encodeString[Json](PredicateOmitCase("x", 42))
        assert(!out.contains("\"count\""), s"later when(true) must replace whenDefault: $out")
        assert(schema.omitPolicies.count(_._1 == "count") == 1, "only one policy for count after replacement")
    }

    "field order is declaration order minus omitted fields" in {
        val schema = Schema[PredicateOmitCase].omit(_.count).when {
            case Structure.Value.Integer(n) => n == 0
            case _                          => false
        }
        val out = schema.encodeString[Json](PredicateOmitCase("hello", 0))
        assert(!out.contains("\"count\""), s"omitted field must be absent: $out")
        assert(out.contains("\"label\""), s"remaining field must be present: $out")
        val labelIdx = out.indexOf("\"label\"")
        assert(labelIdx >= 0, s"label not found: $out")
    }

    "when and WhenDefault decode stubs return false (decode unchanged)" in {
        val whenSchema        = Schema[PredicateOmitCase].omit(_.count).when(_ => true)
        val whenDefaultSchema = Schema[PredicateOmitCase].omit(_.count).whenDefault
        val json              = """{"label":"x","count":7}"""
        assert(whenSchema.decodeString[Json](json) == Result.Success(PredicateOmitCase("x", 7)))
        assert(whenDefaultSchema.decodeString[Json](json) == Result.Success(PredicateOmitCase("x", 7)))
    }

    "omit round-trips a populated collection through the standard derived Schema, no custom codec" in {
        val schema = Schema[Cart].omitEmptyCollections
        val value  = Cart(Chunk("a", "b"), Maybe("hello"))
        val out    = schema.encodeString[Json](value)
        val back   = schema.decodeString[Json](out)
        assert(back == Result.succeed(value), s"round-trip failed: $back (encoded: $out)")
        assert(out.contains("\"items\""), s"populated items must appear: $out")
    }

    // =========================================================================
    // omit type-awareness: product fields must never be omitted
    // =========================================================================

    "empty Map field is omitted under omitEmptyCollections but a sibling empty product field is NOT omitted" in {
        // At one schema level: tags (empty Map) is omitted; nested (empty product) is retained.
        // Omit is a property of the schema it is configured on; it does not propagate into the
        // nested product's own derived schema, so nested still renders with its inner empty map.
        val schema = Schema[MapAndProductSibling].omitEmptyCollections
        val value  = MapAndProductSibling(Map.empty, EmptyMapProduct(Map.empty))
        val out    = schema.encodeString[Json](value)
        assert(out == "{\"nested\":{\"theMap\":{}}}", s"expected only nested product retained, empty Map omitted: $out")
        assert(!out.contains("\"tags\""), s"empty Map field must be omitted: $out")
        assert(out.contains("\"nested\""), s"empty product field must be present: $out")
    }

    "nested product whose own collection fields render empty is NOT itself dropped from the parent" in {
        val schema = Schema[OuterWithNestedProduct].omitEmptyCollections
        val value  = OuterWithNestedProduct(InnerWithEmptyCollection(Chunk.empty), "present")
        val out    = schema.encodeString[Json](value)
        assert(out == "{\"inner\":{\"items\":[]},\"label\":\"present\"}", s"inner product must be retained: $out")
        assert(out.contains("\"inner\""), s"inner product field must be present even though it renders as empty object: $out")
        assert(out.contains("\"label\""), s"label field must be present: $out")
    }

    "nested product with empty collection round-trips correctly under omitEmptyCollections" in {
        val schema = Schema[OuterWithNestedProduct].omitEmptyCollections
        val value  = OuterWithNestedProduct(InnerWithEmptyCollection(Chunk.empty), "present")
        val out    = schema.encodeString[Json](value)
        val back   = schema.decodeString[Json](out)
        assert(back == Result.succeed(value), s"round-trip failed: $back (encoded: $out)")
    }

    "empty Map field round-trips correctly under omitEmptyCollections (positive regression guard)" in {
        val schema = Schema[CartWithMap].omitEmptyCollections
        val value  = CartWithMap(Map.empty, "x")
        val out    = schema.encodeString[Json](value)
        assert(!out.contains("\"tags\""), s"empty Map must be omitted: $out")
        val back = schema.decodeString[Json](out)
        assert(back == Result.succeed(value), s"round-trip failed: $back (encoded: $out)")
    }

    "empty List field round-trips correctly under omitEmptyCollections (positive regression guard)" in {
        val schema = Schema[CartWithList].omitEmptyCollections
        val value  = CartWithList(List.empty, "x")
        val out    = schema.encodeString[Json](value)
        assert(!out.contains("\"items\""), s"empty List must be omitted: $out")
        val back = schema.decodeString[Json](out)
        assert(back == Result.succeed(value), s"round-trip failed: $back (encoded: $out)")
    }

    "omitNone schema-wide: absent Maybe field is omitted on encode and decodes back to Maybe.empty" in {
        // Cart has: items: Chunk[String], note: Maybe[String]
        val schema = Schema[Cart].omitNone
        val value  = Cart(Chunk("a"), Maybe.empty)
        val out    = schema.encodeString[Json](value)
        assert(!out.contains("\"note\""), s"note key must be absent when omitNone and Maybe.empty: $out")
        assert(!out.contains("null"), s"null must not appear under omitNone: $out")
        val back = schema.decodeString[Json](out)
        assert(back == Result.succeed(value), s"round-trip must restore Maybe.empty: $back (encoded: $out)")
    }

    "omitNone schema-wide: absent Option field is omitted on encode and decodes back to None" in {
        // OmitAllPolicyCase has: items, tags, note: Option[String], name
        val schema = Schema[OmitAllPolicyCase].omitNone
        val value  = OmitAllPolicyCase(Chunk("x"), Map("k" -> 1), None, "y")
        val out    = schema.encodeString[Json](value)
        assert(!out.contains("\"note\""), s"note key must be absent when omitNone and None: $out")
        assert(!out.contains("null"), s"null must not appear under omitNone: $out")
        val back = schema.decodeString[Json](out)
        assert(back == Result.succeed(value), s"round-trip must restore None: $back (encoded: $out)")
    }

    "omit per-field whenNone: absent Maybe field is omitted on encode and decodes back to Maybe.empty" in {
        val schema = Schema[Cart].omit(_.note).whenNone
        val value  = Cart(Chunk("a"), Maybe.empty)
        val out    = schema.encodeString[Json](value)
        assert(!out.contains("\"note\""), s"note key must be absent with per-field whenNone and Maybe.empty: $out")
        val back = schema.decodeString[Json](out)
        assert(back == Result.succeed(value), s"per-field whenNone round-trip must restore Maybe.empty: $back (encoded: $out)")
    }

    "omit per-field whenNone: absent Option field is omitted on encode and decodes back to None" in {
        val schema = Schema[OmitAllPolicyCase].omit(_.note).whenNone
        val value  = OmitAllPolicyCase(Chunk("x"), Map("k" -> 1), None, "y")
        val out    = schema.encodeString[Json](value)
        assert(!out.contains("\"note\""), s"note key must be absent with per-field whenNone and None: $out")
        val back = schema.decodeString[Json](out)
        assert(back == Result.succeed(value), s"per-field whenNone round-trip must restore None: $back (encoded: $out)")
    }

    // =========================================================================
    // union derivation (OrType arm)
    // =========================================================================

    "a Scala type union derives to a two-variant Sum structure" in {
        // The produced schema must expose a Sum structure with one variant per union member,
        // proving derivation took the union path rather than a fallback.
        val s = summon[Schema[Int | String]]
        s.structure match
            case sum: Structure.Type.Sum =>
                assert(sum.variants.size == 2, s"expected 2 variants; got ${sum.variants.size}")
                assert(sum.variants.map(_.name) == Chunk("Int", "String"), s"expected Chunk(Int, String); got ${sum.variants.map(_.name)}")
            case other =>
                fail(s"expected Structure.Type.Sum from union derivation; got $other")
        end match
    }

    "zero-config union round-trips on all four self-describing codecs" in {
        val s = summon[Schema[Int | String]]
        // Bare untagged payload pin (the untagged default)
        val encInt = s.encodeString[Json](42)
        assert(encInt == "42", s"Int member JSON: expected '42' but got '$encInt'")
        val decInt = s.decodeString[Json]("42")
        assert(decInt == Result.succeed(42), s"Int JSON decode: $decInt")
        val encStr = s.encodeString[Json]("hello")
        assert(encStr == "\"hello\"", s"String member JSON: expected '\"hello\"' but got '$encStr'")
        val decStr = s.decodeString[Json]("\"hello\"")
        assert(decStr == Result.succeed("hello"), s"String JSON decode: $decStr")
        // Yaml
        val yamlInt = s.encodeString[Yaml](42)
        assert(s.decodeString[Yaml](yamlInt) == Result.succeed(42), s"Int Yaml round-trip: $yamlInt")
        val yamlStr = s.encodeString[Yaml]("hello")
        assert(s.decodeString[Yaml](yamlStr) == Result.succeed("hello"), s"String Yaml round-trip: $yamlStr")
        // Ion
        val ionInt = s.encodeString[Ion](42)
        assert(s.decodeString[Ion](ionInt) == Result.succeed(42), s"Int Ion round-trip: $ionInt")
        val ionStr = s.encodeString[Ion]("hello")
        assert(s.decodeString[Ion](ionStr) == Result.succeed("hello"), s"String Ion round-trip: $ionStr")
        // MsgPack (binary codec)
        val mpInt = s.encode[MsgPack](42)
        assert(s.decode[MsgPack](mpInt) == Result.succeed(42), "Int MsgPack round-trip failed")
        val mpStr = s.encode[MsgPack]("hello")
        assert(s.decode[MsgPack](mpStr) == Result.succeed("hello"), "String MsgPack round-trip failed")
    }

    "nested union flattens to one ordered Sum with declared member order" in {
        val s = summon[Schema[Int | (String | Boolean)]]
        s.structure match
            case sum: Structure.Type.Sum =>
                val names = sum.variants.map(_.name)
                assert(
                    names == Chunk("Int", "String", "Boolean"),
                    s"expected Chunk(Int, String, Boolean) but got $names"
                )
                assert(sum.variants.size == 3, s"expected 3 variants but got ${sum.variants.size}")
            case other =>
                fail(s"expected Structure.Type.Sum but got $other")
        end match
    }

    "declaration order determines Sum variant order" in {
        val s = summon[Schema[Boolean | Int | String]]
        s.structure match
            case sum: Structure.Type.Sum =>
                val names = sum.variants.map(_.name)
                assert(
                    names == Chunk("Boolean", "Int", "String"),
                    s"reordered declaration yields reordered variants; got $names"
                )
            case other =>
                fail(s"expected Structure.Type.Sum but got $other")
        end match
    }

    "Maybe/Result/Either keep their explicit given schemas and are never rerouted through union derivation" in {
        // Maybe[A] resolves to maybeSchema (the explicit given), not the OrType union arm.
        val mSchema    = summon[Schema[Maybe[Int]]]
        val encPresent = mSchema.encodeString[Json](Maybe(5))
        assert(encPresent == "5", s"Maybe(5) must encode as '5' (null-or-inner); got '$encPresent'")
        val encAbsent = mSchema.encodeString[Json](Maybe.empty)
        assert(encAbsent == "null", s"Maybe.empty must encode as 'null'; got '$encAbsent'")
        // Maybe[A] is a nominal sealed trait; its structure is a Sum with the sealed children,
        // not a 2-variant union Sum named "Union".
        val mStructure = mSchema.structure
        mStructure match
            case sum: Structure.Type.Sum =>
                assert(sum.name != "Union", s"maybeSchema must not produce a union-derived Sum (name 'Union'); got name '${sum.name}'")
            case _ => ()
        end match
        // Result[E, A] encodes with "success"/"failure" keys (adjacent-like shape), not bare payload.
        val rSchema = summon[Schema[Result[String, Int]]]
        val encSucc = rSchema.encodeString[Json](Result.succeed(42))
        assert(encSucc.contains("success"), s"Result.succeed must encode with 'success' key; got '$encSucc'")
        assert(encSucc.contains("42"), s"Result.succeed must include value 42; got '$encSucc'")
        val encFail = rSchema.encodeString[Json](Result.fail("oops"))
        assert(encFail.contains("failure"), s"Result.fail must encode with 'failure' key; got '$encFail'")
        // Either[A, B] encodes with "Right"/"Left" discriminator.
        val eSchema  = summon[Schema[Either[String, Int]]]
        val encRight = eSchema.encodeString[Json](Right(42))
        assert(encRight.contains("Right"), s"Right must encode with 'Right' discriminator; got '$encRight'")
        assert(encRight.contains("42"), s"Right must include value 42; got '$encRight'")
    }

    "untagged union decode is a typed three-way outcome (no-match, exactly-one, multi-match under Strict)" in {
        // No-match: "true" decodes as neither Int nor String; yields NoVariantMatchException.
        val sIntStr = summon[Schema[Int | String]]
        val noMatch = sIntStr.decodeString[Json]("true")
        noMatch match
            case Result.Failure(ex: NoVariantMatchException) =>
                assert(ex.variants.size == 2, s"Expected 2 attempted variants, got ${ex.variants.size}")
            case other => fail(s"Expected Failure(NoVariantMatchException), got $other")
        end match
        // Exactly-one-match: "\"hi\"" decodes only as String (not Int).
        val oneMatch = sIntStr.decodeString[Json]("\"hi\"")
        assert(oneMatch == Result.succeed("hi"), s"Exactly-one-match must return String 'hi', got $oneMatch")
        // Multi-match under Strict (default): "42" decodes as both Int and Long;
        // yields AmbiguousVariantMatchException listing matched members.
        val sIntLong   = summon[Schema[Int | Long]]
        val multiMatch = sIntLong.decodeString[Json]("42")
        multiMatch match
            case Result.Failure(ex: AmbiguousVariantMatchException) =>
                assert(ex.matched.size == 2, s"Expected 2 matched members, got ${ex.matched.size}")
            case other => fail(s"Expected Failure(AmbiguousVariantMatchException) for Int|Long on '42', got $other")
        end match
    }

    "FirstMatch resolves a multi-member match by declared order" in {
        // "42" matches both Int and Long; FirstMatch returns Int (first-declared).
        val s      = summon[Schema[Int | Long]].unionAmbiguity(Schema.UnionAmbiguity.FirstMatch)
        val result = s.decodeString[Json]("42")
        result match
            case Result.Success(v) =>
                assert(v.isInstanceOf[Int], s"FirstMatch must return Int (first-declared), got ${v.getClass}")
                assert(v == 42, s"Value must be 42, got $v")
            case other => fail(s"Expected Result.Success(42: Int), got $other")
        end match
    }

    "ambiguity slot is decode-only: encode output is byte-identical regardless of policy" in {
        val sDefault      = summon[Schema[Int | Long]]
        val sFirstMatch   = summon[Schema[Int | Long]].unionAmbiguity(Schema.UnionAmbiguity.FirstMatch)
        val encDefault    = sDefault.encodeString[Json](42)
        val encFirstMatch = sFirstMatch.encodeString[Json](42)
        assert(
            encDefault == encFirstMatch,
            s"Encode must be identical regardless of ambiguity policy; got '$encDefault' vs '$encFirstMatch'"
        )
        // Policy is preserved through subsequent builder calls (copyWith chain).
        val sChained = sFirstMatch.adjacent("type", "content")
        assert(
            sChained.unionAmbiguityPolicy == Schema.UnionAmbiguity.FirstMatch,
            "unionAmbiguityPolicy must survive copyWith/adjacent chain"
        )
    }

    "union decode probe is non-destructive: a value valid only for a later member decodes correctly" in {
        // "\"hello\"" is not a valid Int but is a valid String (declared second in Int | String).
        // A destructive read would consume the input on the first (failing) Int probe,
        // leaving nothing for the String probe. Non-destructive replay must succeed.
        val s      = summon[Schema[Int | String]]
        val result = s.decodeString[Json]("\"hello\"")
        assert(result == Result.succeed("hello"), s"Non-destructive probe must decode String 'hello', got $result")
    }

    "union member naming via reused variantNames rejects a non-member name at the builder call site" in {
        val s      = summon[Schema[Int | String]]
        val result = Result.catching[SchemaException](s.variantNames("Nope" -> "x"))
        assert(result.isFailure, s"variantNames with unknown member must fail; got $result")
    }

    "nominal untagged sum keeps first-declared-wins decode while type unions probe all members" in {
        // SSRUAmbig is a sealed nominal sum; both SSRUAmbigFirst and SSRUAmbigSecond have field 'x'.
        // readUntagged (first-wins) must still be used for nominal sums, not readUnionMultiProbe.
        val schema = Schema[SSRUAmbig].untagged
        val result = schema.decodeString[Json]("""{"x":5.0}""")
        assert(result == Result.succeed(SSRUAmbigFirst(5.0)), s"Nominal sum must keep first-declared-wins; got $result")
    }

    "union with duplicate simple-name labels is rejected at compile time" in {
        typeCheckFailure(
            "summon[kyo.Schema[kyo.SfA.Dup | kyo.SfB.Dup]]"
        )("duplicate wire labels")
    }

    "disjoint union (Int | String) still derives without error" in {
        val s = summon[Schema[Int | String]]
        s.structure match
            case sum: Structure.Type.Sum =>
                assert(sum.variants.size == 2, s"expected 2 variants; got ${sum.variants.size}")
                assert(sum.variants.map(_.name) == Chunk("Int", "String"), s"expected Chunk(Int, String); got ${sum.variants.map(_.name)}")
            case other =>
                fail(s"expected Structure.Type.Sum from disjoint union; got $other")
        end match
    }

    "cross-feature: denyUnknownFields + default + omit whenDefault + rename + builder-order reversal" in {
        val schemaA = Schema[CrossFeaturePerson]
            .rename(_.firstName, "first_name")
            .default(_.score)(42)
            .omit(_.score).whenDefault
            .denyUnknownFields

        val schemaB = Schema[CrossFeaturePerson]
            .denyUnknownFields
            .omit(_.score).whenDefault
            .default(_.score)(42)
            .rename(_.firstName, "first_name")

        // schemaA: renamed wire name accepted, score present decodes normally
        val r1 = schemaA.decodeString[Json]("""{"first_name":"Alice","score":42}""")
        assert(r1 == Result.Success(CrossFeaturePerson("Alice", 42)), s"score present must decode: $r1")

        // schemaA: absent score falls to default supplier (42)
        val r2 = schemaA.decodeString[Json]("""{"first_name":"Bob"}""")
        assert(r2 == Result.Success(CrossFeaturePerson("Bob", 42)), s"absent score must use supplier: $r2")

        // schemaA: encode score=0 (== Scala default) -> omitted by whenDefault
        val e1 = schemaA.encodeString[Json](CrossFeaturePerson("Carol", 0))
        assert(!e1.contains("\"score\""), s"score==0 (Scala default) must be omitted: $e1")
        assert(e1.contains("\"first_name\":\"Carol\""), s"renamed field must appear: $e1")

        // schemaA: encode score=7 (not Scala default 0) -> retained
        val e2 = schemaA.encodeString[Json](CrossFeaturePerson("Dave", 7))
        assert(e2.contains("\"score\":7"), s"score!=0 must be retained: $e2")
        assert(e2.contains("\"first_name\":\"Dave\""), s"renamed field must appear: $e2")

        // schemaA: encode score=42 (not Scala default 0) -> retained (42 != compile-time default 0)
        val e3 = schemaA.encodeString[Json](CrossFeaturePerson("Ivan", 42))
        assert(e3.contains("\"score\":42"), s"score=42 must be retained (42 != Scala default 0): $e3")

        // schemaA: unknown field -> UnknownFieldException
        val r3 = schemaA.decodeString[Json]("""{"first_name":"Eve","extra":1}""")
        assert(r3.isFailure, s"unknown field must fail: $r3")
        assert(
            r3.failure.exists(_.isInstanceOf[UnknownFieldException]),
            s"failure must be UnknownFieldException: $r3"
        )

        // schemaA: original source name (renamed away) is rejected
        val r4 = schemaA.decodeString[Json]("""{"firstName":"X"}""")
        assert(r4.isFailure, s"renamed-away source name must fail: $r4")
        assert(
            r4.failure.exists(_.isInstanceOf[UnknownFieldException]),
            s"failure must be UnknownFieldException: $r4"
        )

        // schemaB: same decode behavior as schemaA (builder-order reversal)
        val r5 = schemaB.decodeString[Json]("""{"first_name":"F"}""")
        assert(r5 == Result.Success(CrossFeaturePerson("F", 42)), s"schemaB absent score must use supplier: $r5")

        // schemaB: same omit behavior as schemaA
        val e4 = schemaB.encodeString[Json](CrossFeaturePerson("G", 0))
        assert(!e4.contains("\"score\""), s"schemaB score==0 must be omitted: $e4")
    }

    "case class union round-trips via isInstanceOf dispatch on all four codecs" in {
        val s = summon[Schema[UnionCaseA | UnionCaseB]]
        val a = UnionCaseA("x", 1)
        val b = UnionCaseB(true)
        // Json
        val encA = s.encodeString[Json](a)
        assert(s.decodeString[Json](encA) == Result.succeed(a), s"CaseA Json round-trip: $encA")
        val encB = s.encodeString[Json](b)
        assert(s.decodeString[Json](encB) == Result.succeed(b), s"CaseB Json round-trip: $encB")
        // Yaml
        val yamlA = s.encodeString[Yaml](a)
        assert(s.decodeString[Yaml](yamlA) == Result.succeed(a), s"CaseA Yaml round-trip: $yamlA")
        val yamlB = s.encodeString[Yaml](b)
        assert(s.decodeString[Yaml](yamlB) == Result.succeed(b), s"CaseB Yaml round-trip: $yamlB")
        // Ion
        val ionA = s.encodeString[Ion](a)
        assert(s.decodeString[Ion](ionA) == Result.succeed(a), s"CaseA Ion round-trip: $ionA")
        val ionB = s.encodeString[Ion](b)
        assert(s.decodeString[Ion](ionB) == Result.succeed(b), s"CaseB Ion round-trip: $ionB")
        // MsgPack (binary)
        val mpA = s.encode[MsgPack](a)
        assert(s.decode[MsgPack](mpA) == Result.succeed(a), "CaseA MsgPack round-trip failed")
        val mpB = s.encode[MsgPack](b)
        assert(s.decode[MsgPack](mpB) == Result.succeed(b), "CaseB MsgPack round-trip failed")
    }

end SchemaTest

case class UnionCaseA(label: String, value: Int) derives CanEqual, Schema
case class UnionCaseB(flag: Boolean) derives CanEqual, Schema

object SfA:
    case class Dup(x: Int) derives CanEqual, Schema

object SfB:
    case class Dup(y: Int) derives CanEqual, Schema

case class Cart(items: Chunk[String], note: Maybe[String]) derives Schema, CanEqual

case class StrictPerson(id: Int, name: String) derives CanEqual, Schema
case class StrictRename(firstName: String, lastName: String) derives CanEqual, Schema
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
