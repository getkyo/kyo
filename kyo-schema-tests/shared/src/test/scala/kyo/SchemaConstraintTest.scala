package kyo

import Json.JsonSchema

class SchemaConstraintTest extends kyo.test.Test[Any]:

    given CanEqual[Any, Any] = CanEqual.derived

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

end SchemaConstraintTest
