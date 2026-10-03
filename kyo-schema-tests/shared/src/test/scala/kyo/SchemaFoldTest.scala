package kyo

import Schema.*

class SchemaFoldTest extends kyo.test.Test[Any]:

    given CanEqual[Any, Any] = CanEqual.derived

    "fold" - {

        val alice = MTPerson("Alice", 30)
        val bob   = MTPerson("Bob", 25)
        val team1 = MTSmallTeam(alice, 5)

        // === fold (typed polymorphic) ===

        "fold over fields" in {
            val m      = Schema[MTPerson]
            val result = m.fold(alice)(Map.empty[String, String]) {
                [N <: String, V] => (acc: Map[String, String], field: Field[N, V], value: V) =>
                    acc + (field.name -> value.toString)
            }
            assert(result == Map("name" -> "Alice", "age" -> "30"))
        }

        "fold field name" in {
            val m     = Schema[MTPerson]
            val names = m.fold(alice)(List.empty[String]) {
                [N <: String, V] => (acc: List[String], field: Field[N, V], _: V) =>
                    acc :+ field.name
            }
            assert(names == List("name", "age"))
        }

        "fold accumulates" in {
            val m     = Schema[MTPerson]
            val count = m.fold(alice)(0) {
                [N <: String, V] => (acc: Int, _: Field[N, V], _: V) =>
                    acc + 1
            }
            assert(count == 2)
        }

        "fold with three fields" in {
            val m      = Schema[MTThreeField]
            val result = m.fold(MTThreeField(1, "two", true))(Map.empty[String, String]) {
                [N <: String, V] => (acc: Map[String, String], field: Field[N, V], value: V) =>
                    acc + (field.name -> value.toString)
            }
            assert(result == Map("x" -> "1", "y" -> "two", "z" -> "true"))
        }

        "fold init passthrough" in {
            // Even though fold iterates, verify it starts from init
            val m      = Schema[MTPerson]
            val result = m.fold(alice)(42) {
                [N <: String, V] => (acc: Int, _: Field[N, V], _: V) =>
                    acc + 1
            }
            assert(result == 44) // 42 + 1 (name) + 1 (age)
        }

        "fold nested type" in {
            val m      = Schema[MTSmallTeam]
            val result = m.fold(team1)(Map.empty[String, String]) {
                [N <: String, V] => (acc: Map[String, String], field: Field[N, V], value: V) =>
                    acc + (field.name -> value.toString)
            }
            assert(result("lead") == alice.toString)
            assert(result("size") == "5")
        }

        // === fold with schema transformations (rename/add) ===

        "fold after rename" in {
            val m      = Schema[MTPerson].rename("name", "fullName")
            val result = m.fold(alice)(Map.empty[String, String]) {
                [N <: String, V] => (acc: Map[String, String], field: Field[N, V], value: V) =>
                    acc + (field.name -> value.toString)
            }
            assert(result.contains("fullName"))
            assert(result("fullName") == "Alice")
            assert(result("age") == "30")
            assert(!result.contains("name"))
        }

        "fold after add" in {
            val m      = Schema[MTPerson].add("greeting")((p: MTPerson) => s"Hello ${p.name}")
            val result = m.fold(alice)(Map.empty[String, String]) {
                [N <: String, V] => (acc: Map[String, String], field: Field[N, V], value: V) =>
                    acc + (field.name -> value.toString)
            }
            assert(result("greeting") == "Hello Alice")
            assert(result("name") == "Alice")
        }

        // === fold field metadata ===

        "fold field name accessible" in {
            val m     = Schema[MTPerson]
            val names = m.fold(alice)(List.empty[String]) {
                [N <: String, V] => (acc: List[String], field: Field[N, V], _: V) =>
                    acc :+ field.name
            }
            assert(names == List("name", "age"))
        }

        "fold field tag accessible" in {
            val m    = Schema[MTPerson]
            val tags = m.fold(alice)(List.empty[Tag[Any]]) {
                [N <: String, V] => (acc: List[Tag[Any]], field: Field[N, V], _: V) =>
                    acc :+ field.tag.erased
            }
            assert(tags.size == 2)
        }

        "fold field tag for string" in {
            val m    = Schema[MTPerson]
            val tags = m.fold(alice)(Map.empty[String, Tag[Any]]) {
                [N <: String, V] => (acc: Map[String, Tag[Any]], field: Field[N, V], _: V) =>
                    acc + (field.name -> field.tag.erased)
            }
            assert(tags("name") =:= Tag[String])
        }

        "fold field tag for int" in {
            val m    = Schema[MTPerson]
            val tags = m.fold(alice)(Map.empty[String, Tag[Any]]) {
                [N <: String, V] => (acc: Map[String, Tag[Any]], field: Field[N, V], _: V) =>
                    acc + (field.name -> field.tag.erased)
            }
            assert(tags("age") =:= Tag[Int])
        }

        "fold field default present" in {
            val m        = Schema[MTDebugConfig]
            val config   = MTDebugConfig("localhost")
            val defaults = m.fold(config)(Map.empty[String, Boolean]) {
                [N <: String, V] => (acc: Map[String, Boolean], field: Field[N, V], _: V) =>
                    acc + (field.name -> field.default.nonEmpty)
            }
            assert(defaults("port") == true)
        }

        "fold field default absent" in {
            val m        = Schema[MTPerson]
            val defaults = m.fold(alice)(Map.empty[String, Boolean]) {
                [N <: String, V] => (acc: Map[String, Boolean], field: Field[N, V], _: V) =>
                    acc + (field.name -> field.default.nonEmpty)
            }
            assert(defaults("name") == false)
            assert(defaults("age") == false)
        }

        "fold produces field map" in {
            val m      = Schema[MTPerson]
            val tagMap = m.fold(alice)(Map.empty[String, Tag[Any]]) {
                [N <: String, V] => (acc: Map[String, Tag[Any]], field: Field[N, V], _: V) =>
                    acc + (field.name -> field.tag.erased)
            }
            assert(tagMap.size == 2)
            assert(tagMap.contains("name"))
            assert(tagMap.contains("age"))
            assert(tagMap("name") =:= Tag[String])
            assert(tagMap("age") =:= Tag[Int])
        }

        "fold field metadata complete" in {
            val m      = Schema[MTPerson]
            val fields = m.fold(alice)(List.empty[Field[?, ?]]) {
                [N <: String, V] => (acc: List[Field[?, ?]], field: Field[N, V], _: V) =>
                    acc :+ field
            }
            assert(fields.forall(f => f.name != null && f.name.nonEmpty))
            assert(fields.size == 2)
        }

        "fold with nested type tag" in {
            val m    = Schema[MTSmallTeam]
            val tags = m.fold(team1)(Map.empty[String, Tag[Any]]) {
                [N <: String, V] => (acc: Map[String, Tag[Any]], field: Field[N, V], _: V) =>
                    acc + (field.name -> field.tag.erased)
            }
            assert(tags("lead") =:= Tag[MTPerson])
            assert(tags("size") =:= Tag[Int])
        }

        "fold field count matches" in {
            val m     = Schema[MTThreeField]
            val v     = MTThreeField(1, "two", true)
            val count = m.fold(v)(0) {
                [N <: String, V] => (acc: Int, _: Field[N, V], _: V) =>
                    acc + 1
            }
            assert(count == 3)
        }

        // === fold type-safe access ===

        "fold type-safe access" in {
            val m      = Schema[MTPerson]
            val result = m.fold(alice)(List.empty[String]) {
                [N <: String, V] => (acc: List[String], field: Field[N, V], value: V) =>
                    acc :+ s"${field.name}=${value}"
            }
            assert(result == List("name=Alice", "age=30"))
        }

        "fold type-safe field name" in {
            val m     = Schema[MTPerson]
            val names = m.fold(alice)(List.empty[String]) {
                [N <: String, V] => (acc: List[String], field: Field[N, V], _: V) =>
                    acc :+ field.name
            }
            assert(names == List("name", "age"))
        }

        "fold type-safe field tag" in {
            val m    = Schema[MTPerson]
            val tags = m.fold(alice)(Map.empty[String, Tag[Any]]) {
                [N <: String, V] => (acc: Map[String, Tag[Any]], field: Field[N, V], _: V) =>
                    acc + (field.name -> field.tag.erased)
            }
            assert(tags("name") =:= Tag[String])
            assert(tags("age") =:= Tag[Int])
        }

        "fold type-safe accumulates" in {
            val m     = Schema[MTThreeField]
            val count = m.fold(MTThreeField(1, "two", true))(0) {
                [N <: String, V] => (acc: Int, _: Field[N, V], _: V) =>
                    acc + 1
            }
            assert(count == 3)
        }

        "fold type-safe produces typed map" in {
            val m      = Schema[MTPerson]
            val result = m.fold(alice)(Map.empty[String, String]) {
                [N <: String, V] => (acc: Map[String, String], field: Field[N, V], value: V) =>
                    acc + (field.name -> value.toString)
            }
            assert(result == Map("name" -> "Alice", "age" -> "30"))
        }

        // === boundary shapes: empty and large case classes ===

        "empty case class fold" in {
            val m     = Schema[MTEmpty]
            val count = m.fold(MTEmpty())(0) {
                [N <: String, V] => (acc: Int, _: Field[N, V], _: V) =>
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
            val m      = Schema[MTLarge]
            val large  = MTLarge(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)
            val result = m.fold(large)(Map.empty[String, String]) {
                [N <: String, V] => (acc: Map[String, String], field: Field[N, V], value: V) =>
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
                [N <: String, V] => (acc: String, field: Field[N, V], v: V) =>
                    s"$acc ${field.name}=$v"
            }
            assert(personLog == "[user] name=Alice age=30")

            // fold on MTProduct
            val productLog = Schema[MTProduct].fold(MTProduct("Widget", 9.99, "W001"))("[product]") {
                [N <: String, V] => (acc: String, field: Field[N, V], v: V) =>
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
            val nullCheck             = Schema[MTAccount].fold(account)(List.empty[String]) {
                [N <: String, V] => (acc: List[String], field: Field[N, V], value: V) =>
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
            val issues                = Schema[MTAccount].fold(bad)(List.empty[String]) {
                [N <: String, V] => (acc: List[String], field: Field[N, V], value: V) =>
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
            val issues                = Schema[MTAccount].fold(bad)(List.empty[String]) {
                [N <: String, V] => (acc: List[String], field: Field[N, V], value: V) =>
                    value match
                        case null                   => acc :+ s"${field.name} is null"
                        case s: String if s.isEmpty => acc :+ s"${field.name} is empty"
                        case _                      => acc
            }
            assert(issues == List("name is empty"))
        }
    }

end SchemaFoldTest
