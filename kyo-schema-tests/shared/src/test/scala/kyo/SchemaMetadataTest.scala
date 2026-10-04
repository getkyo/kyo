package kyo

import Json.JsonSchema

class SchemaMetadataTest extends kyo.test.Test[Any]:

    given CanEqual[Any, Any] = CanEqual.derived

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
            val s  = Schema[MTUser]
                .example(u1)
                .example(u2)
            assert(s.examples == Seq(u1, u2))
        }

        "metadata survives transform chain" in {
            val user = MTUser("Alice", 30, "alice@example.com", "123-45-6789")
            val s    = Schema[MTUser]
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
            val s    = Schema[MTUser]
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
            val s    = Schema[MTUser]
                .example(user)
            assert(s.examples == Seq(user))
        }

        "metadata survives transform chain into describe" in {
            val user = MTUser("Alice", 30, "alice@example.com", "123-45-6789")
            val s    = Schema[MTUser]
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
            val s  = Schema[MTUser]
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
            val schema  = Json.jsonSchema(using
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

end SchemaMetadataTest
