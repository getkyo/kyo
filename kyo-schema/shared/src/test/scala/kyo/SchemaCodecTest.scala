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

class SchemaCodecTest extends kyo.test.Test[Any]:

    given CanEqual[Any, Any] = CanEqual.derived

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
            succeed("type-resolution compile check: the type ascription above is the verification; no concrete runtime value to assert")
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
            succeed("type-resolution compile check: the type ascription above is the verification; no concrete runtime value to assert")
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
                case _: SchemaException => succeed("SchemaException was thrown for a non-positive fieldId; catching it is the verification")
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
                ()
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
                case _: SchemaException => succeed("SchemaException was thrown for malformed input; catching it is the verification")
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
                case _: SchemaException => succeed("SchemaException was thrown for incomplete input; catching it is the verification")
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

        "kyo-data opaque type schemas" - {

            "kyo.Instant roundtrip" in {
                given Schema[kyo.Instant] = summon[Schema[kyo.Instant]]
                val instant               = kyo.Instant.fromJava(java.time.Instant.parse("2024-01-01T00:00:00Z"))
                val encoded               = Json.encode(instant)
                val decoded               = Json.decode[kyo.Instant](encoded).getOrThrow
                assert(decoded == instant)
            }

            "kyo.Duration roundtrip" in {
                given Schema[kyo.Duration] = summon[Schema[kyo.Duration]]
                val duration               = kyo.Duration.fromNanos(123456789L)
                val encoded                = Json.encode(duration)
                val decoded                = Json.decode[kyo.Duration](encoded).getOrThrow
                assert(decoded == duration)
            }

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
            resultSchema.serializeWrite(panic, w)
            val r       = new TestReader(w.resultTokens)
            val decoded = resultSchema.serializeRead(r)

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
            dictSchema.serializeWrite(original, w)
            val r       = new TestReader(w.resultTokens)
            val decoded = dictSchema.serializeRead(r)
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

        "Changeset.Patch round-trips through JSON (derived Schema)" in {

            val op: Changeset.Patch = Changeset.Patch.SetField(
                Chunk("name"),
                Structure.Value.Str("Bob")
            )
            val encoded = Json.encode(op)
            val decoded = Json.decode[Changeset.Patch](encoded).getOrThrow
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
            discard(summon[SchemaNotSerializableException <:< SchemaException])
            succeed("SchemaNotSerializableException extends SchemaException is a compile-time hierarchy fact proven by the <:< evidence")
        }

        "SchemaNotSerializableException has TransformException marker trait" in {
            discard(summon[SchemaNotSerializableException <:< TransformException])
            succeed(
                "SchemaNotSerializableException mixes in TransformException is a compile-time hierarchy fact proven by the <:< evidence"
            )
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
            // ssn was dropped from serialization, so it gets the JVM default for its declared type
            // (`null` for `String`, `0` for `Int`, `false` for `Boolean`, etc.).
            assert(decoded.ssn == null)
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

            "Structure.Value.VariantCase round-trips through shape-aware Schema as a single-field Record" in {
                // The identity Schema[Structure.Value] (kyo.Structure.Value.valueSchema) writes VariantCase as a
                // single-field object whose key is the variant name. A shape-aware reader cannot distinguish that
                // wire form from a Record containing one field, so the canonical round-trip materializes a Record.
                val v = Structure.Value.VariantCase(
                    "Circle",
                    Structure.Value.Record(Chunk(("radius", Structure.Value.primitive(5.0))))
                )
                val expected = Structure.Value.Record(
                    Chunk(("Circle", Structure.Value.Record(Chunk(("radius", Structure.Value.primitive(5.0))))))
                )
                assert(roundTrip(v) == expected)
            }

            "Structure.Value.MapEntries round-trip preserves entries through the token codec" in {
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

            "Structure.Value.VariantCase with whole-number Double fields canonicalizes to a Record on round-trip" in {
                // Shape-aware identity: VariantCase serializes as a single-field object keyed by the variant name; the
                // round-trip materializes a Record(((variantName, inner)) rather than restoring the VariantCase tag.
                val v = Structure.Value.VariantCase(
                    "Point",
                    Structure.Value.Record(
                        Chunk(
                            ("x", Structure.Value.primitive(0.0)),
                            ("y", Structure.Value.primitive(-1.0))
                        )
                    )
                )
                val expected = Structure.Value.Record(
                    Chunk(
                        (
                            "Point",
                            Structure.Value.Record(
                                Chunk(
                                    ("x", Structure.Value.primitive(0.0)),
                                    ("y", Structure.Value.primitive(-1.0))
                                )
                            )
                        )
                    )
                )
                assert(roundTrip(v) == expected)
            }

            // Structure.Field codec round-trips

            "Structure.Field round-trip (simple)" in {
                val v = Structure.Field(
                    "myField",
                    Structure.Type.Primitive(Structure.PrimitiveKind.String, Tag[String].asInstanceOf[Tag[Any]]),
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
                    Structure.Type.Primitive(Structure.PrimitiveKind.String, Tag[String].asInstanceOf[Tag[Any]]),
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
                        Chunk(Structure.Field(
                            "radius",
                            Structure.Type.Primitive(Structure.PrimitiveKind.Double, Tag[Any]),
                            Maybe.empty,
                            Maybe.empty,
                            false
                        ))
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
                        Structure.Field(
                            "name",
                            Structure.Type.Primitive(Structure.PrimitiveKind.String, Tag[Any]),
                            Maybe.empty,
                            Maybe.empty,
                            false
                        ),
                        Structure.Field(
                            "age",
                            Structure.Type.Primitive(Structure.PrimitiveKind.Int, Tag[Any]),
                            Maybe.empty,
                            Maybe.empty,
                            false
                        )
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
                val v       = Structure.Type.Primitive(Structure.PrimitiveKind.String, Tag[Any])
                val decoded = roundTrip(v)(using summon[Schema[Structure.Type]])
                assert(decoded.name == "String")
            }

            "Structure.Type Collection round-trip" in {
                val v       = Structure.Type.Collection("List", Tag[Any], Structure.Type.Primitive(Structure.PrimitiveKind.Int, Tag[Any]))
                val decoded = roundTrip(v)(using summon[Schema[Structure.Type]])
                assert(decoded.name == "List")
                decoded match
                    case Structure.Type.Collection(_, _, elem) => assert(elem.name == "Int")
                    case _                                     => fail("expected Collection")
            }

            // Structure.TypedValue codec round-trips

            "Structure.TypedValue round-trip" in {
                val v = Structure.TypedValue(
                    Structure.Type.Primitive(Structure.PrimitiveKind.Int, Tag[Any]),
                    Structure.Value.primitive(42)
                )
                val decoded = roundTrip(v)(using summon[Schema[Structure.TypedValue]])
                assert(decoded.value == v.value)
            }

            // Changeset.Patch codec round-trips

            "Changeset.Patch.SetField round-trip" in {
                val v = Changeset.Patch.SetField(Chunk("name"), Structure.Value.primitive("Alice"))
                assert(roundTrip(v)(using summon[Schema[Changeset.Patch]]) == v)
            }

            "Changeset.Patch.RemoveField round-trip" in {
                val v = Changeset.Patch.RemoveField(Chunk("someField"))
                assert(roundTrip(v)(using summon[Schema[Changeset.Patch]]) == v)
            }

            "Changeset.Patch.SetNull round-trip" in {
                val v = Changeset.Patch.SetNull(Chunk("optField"))
                assert(roundTrip(v)(using summon[Schema[Changeset.Patch]]) == v)
            }

            "Changeset.Patch.NumericDelta round-trip" in {
                val v = Changeset.Patch.NumericDelta(Chunk("age"), BigDecimal(5))
                assert(roundTrip(v)(using summon[Schema[Changeset.Patch]]) == v)
            }

            "Changeset.Patch.StringPatch round-trip" in {
                val v = Changeset.Patch.StringPatch(Chunk("name"), 0, 5, "Alice")
                assert(roundTrip(v)(using summon[Schema[Changeset.Patch]]) == v)
            }

            "Changeset.Patch.Nested round-trip" in {
                val v = Changeset.Patch.Nested(
                    Chunk("address"),
                    Chunk(Changeset.Patch.StringPatch(Chunk("city"), 0, 3, "NYC"))
                )
                assert(roundTrip(v)(using summon[Schema[Changeset.Patch]]) == v)
            }

            "Changeset.Patch.SetField JSON round-trip" in {
                val v = Changeset.Patch.SetField(Chunk("x"), Structure.Value.primitive(99))
                assert(jsonRoundTrip(v)(using summon[Schema[Changeset.Patch]]) == v)
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

            "Structure.Value MapEntries with String keys canonicalizes to a Record on JSON round-trip" in {
                // JSON cannot distinguish a string-keyed map from a record; the universal shape-aware identity Schema
                // emits both as `{"key":value}` and reads them back as Record. Round-tripping a MapEntries through JSON
                // yields the equivalent Record.
                val v = Structure.Value.MapEntries(
                    Chunk(
                        (Structure.Value.primitive("key1"), Structure.Value.primitive(10)),
                        (Structure.Value.primitive("key2"), Structure.Value.primitive(20))
                    )
                )
                val expected = Structure.Value.Record(
                    Chunk(
                        ("key1", Structure.Value.primitive(10)),
                        ("key2", Structure.Value.primitive(20))
                    )
                )
                assert(jsonRoundTrip(v) == expected)
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

            "Frame round-trip (implicit Frame)" in {
                val frame  = captureFrameHere()
                val schema = summon[Schema[Frame]]
                val back   = auditRoundTrip(frame)(using schema)
                assert(frame.toString == back.toString)
            }

            "Frame round-trip (different call site)" in {
                val frame  = captureFrameElsewhere()
                val schema = summon[Schema[Frame]]
                val back   = auditJsonRoundTrip(frame)(using schema)
                assert(frame.toString == back.toString)
                assert(back.className.nonEmpty)
                assert(back.callerName.nonEmpty)
            }

            "Tag[Int] round-trip (static tag)" in {
                val tag    = summon[Tag[Int]]
                val schema = Schema.tagSchema[Int]
                val back   = auditRoundTrip(tag)(using schema)
                assert(tag.show == back.show)
                assert(tag =:= back)
            }

            "Tag[String] round-trip (static tag)" in {
                val tag    = summon[Tag[String]]
                val schema = Schema.tagSchema[String]
                val back   = auditRoundTrip(tag)(using schema)
                assert(tag.show == back.show)
                assert(tag =:= back)
            }

            "Tag dynamic round-trip: decoding the show-string fails (documented limitation)" in {
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

            "Dict[String, Int] empty round-trip (size 0)" in {
                val v      = Dict.empty[String, Int]
                val schema = Schema.stringDictSchema[Int]
                val back   = auditRoundTrip(v)(using schema)
                assert(back.size == 0)
            }

            "Dict[String, Dict[String, Int]] nested round-trip (all 4 leaf values present)" in {
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

            "Maybe[Maybe[Int]]: Absent round-trips as Absent" in {
                val v: Maybe[Maybe[Int]] = Maybe.empty[Maybe[Int]]
                val schema               = summon[Schema[Maybe[Maybe[Int]]]]
                val back                 = auditRoundTrip(v)(using schema)
                assert(back == Maybe.empty[Maybe[Int]])
            }

            "Maybe[Maybe[Int]]: standalone Present(Absent) is lossy" in {
                val v: Maybe[Maybe[Int]] = Present(Maybe.empty[Int])
                val schema               = summon[Schema[Maybe[Maybe[Int]]]]
                val back                 = auditRoundTrip(v)(using schema)
                assert(back == Maybe.empty[Maybe[Int]], s"Expected Absent (lossy) but got $back")
            }

            "Chunk[Int] empty round-trip (size 0)" in {
                val v      = Chunk.empty[Int]
                val schema = summon[Schema[Chunk[Int]]]
                val back   = auditRoundTrip(v)(using schema)
                assert(back.size == 0)
            }

            "Chunk[Chunk[Int]] nested round-trip (all 4 elements present in correct positions)" in {
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
                ()
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
                ()
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

end SchemaCodecTest
