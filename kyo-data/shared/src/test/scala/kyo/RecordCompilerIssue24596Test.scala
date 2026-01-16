package kyo

/** Test cases to reproduce and minimize Scala compiler issue #24596
  *
  * This test file contains test cases that help identify issues with Records in Scala 3.8.0-RC2+. The tests are designed to:
  *   1. Reproduce the compiler issue
  *   2. Help minimize the problem
  *   3. Verify that fixes don't break TagTest/TagMacroTest
  *
  * Related: https://github.com/scala/scala3/issues/24596
  */
class RecordCompilerIssue24596Test extends Test:

    "compiler issue #24596 - Record field access" - {

        "basic field access with type constraints" in {
            val record = "name" ~ "Alice" & "age" ~ 30

            // These should compile and work correctly
            assert(record.name == "Alice")
            assert(record.age == 30)
        }

        "field access with tag subtyping" in {
            val record = "value" ~ 42

            // Test that tag subtyping works correctly
            val field = Record.fieldsOf(record).head
            assert(field.tag =:= Tag[Int])
            assert(field.tag <:< Tag[AnyVal])
        }

        "duplicate field names with different types" in {
            val record = "value" ~ "string" & "value" ~ 42

            // Both should be accessible with type annotations
            assert((record.value: String) == "string")
            assert((record.value: Int) == 42)
        }

        "nested record field access" in {
            val inner = "id" ~ 1 & "name" ~ "test"
            val outer = "inner" ~ inner & "count" ~ 2

            assert(outer.inner.id == 1)
            assert(outer.inner.name == "test")
            assert(outer.count == 2)
        }

        "record with generic types" in {
            val record = "list" ~ List(1, 2, 3) & "map" ~ Map("a" -> 1)

            assert(record.list.head == 1)
            assert(record.map.apply("a") == 1)
        }

        "record compact operation" in {
            val full      = "name" ~ "Alice" & "age" ~ 30 & "city" ~ "Paris"
            val compacted = full.compact

            // Compacted record should still have all fields
            assert(compacted.name == "Alice")
            assert(compacted.age == 30)
            assert(compacted.city == "Paris")
        }

        "record equality with tag subtyping" in {
            val record1 = "name" ~ "Alice" & "age" ~ 30
            val record2 = "name" ~ "Alice" & "age" ~ 30

            // Records with same fields should be equal
            assert(record1 == record2)
            assert(record1.hashCode == record2.hashCode)
        }

        "record field lookup with Map operations" in {
            val record = "name" ~ "Alice" & "age" ~ 30
            val map    = record.toMap

            // Map should contain fields
            assert(map.size == 2)

            // Field lookup should work
            val nameField = map.keys.find(_.name == "name")
            assert(nameField.isDefined)
        }

        "record with complex nested structures" in {
            case class Address(street: String, city: String)
            val address = Address("123 Main", "NYC")
            val record  = "person" ~ "Alice" & "address" ~ address

            assert(record.person == "Alice")
            assert(record.address.street == "123 Main")
            assert(record.address.city == "NYC")
        }

        "record type intersection" in {
            val record1 = "name" ~ "Alice"
            val record2 = "age" ~ 30
            val merged  = record1 & record2

            assert(merged.name == "Alice")
            assert(merged.age == 30)
        }

        "record with variance and subtyping" in {
            val full: Record["name" ~ String & "age" ~ Int & "city" ~ String] =
                "name" ~ "Alice" & "age" ~ 30 & "city" ~ "Paris"

            // Should be able to upcast to fewer fields
            val nameAge: Record["name" ~ String & "age" ~ Int] = full
            assert(nameAge.name == "Alice")
            assert(nameAge.age == 30)

            val nameOnly: Record["name" ~ String] = full
            assert(nameOnly.name == "Alice")
        }

        "record selectDynamic edge cases" in {
            // Test edge cases that might trigger compiler issues
            val record =
                "size" ~ 3 &
                    "fields" ~ List("x", "y") &
                    "toMap" ~ Map(1 -> "x") &
                    "getField" ~ true &
                    "&" ~ "and" &
                    "" ~ "empty"

            assert((record.size: Int) == 3)
            assert((record.fields: List[String]) == List("x", "y"))
            assert((record.getField: Boolean) == true)
            assert(record.getField["&", String] == "and")
            assert((record.getField["", String]: String) == "empty")
        }

        "record with type parameters" in {
            case class Box[T](value: T)
            val box    = Box(42)
            val record = Record.fromProduct(box)

            assert(record.value == 42)
        }

        "record field access with implicit resolution" in {
            val record = "name" ~ "Alice" & "age" ~ 30

            // Test that implicit resolution works correctly
            // Direct field access should work
            assert(record.name == "Alice")
            assert(record.age == 30)
        }

        "record with sealed trait hierarchies" in {
            sealed trait Animal
            case class Dog(name: String) extends Animal
            case class Cat(name: String) extends Animal

            val dog    = Dog("Rex")
            val record = Record.fromProduct(dog)

            assert(record.name == "Rex")
        }

        "record field access performance" in {
            val record = "a" ~ 1 & "b" ~ 2 & "c" ~ 3 & "d" ~ 4 & "e" ~ 5

            // Multiple field accesses should work efficiently
            assert(record.a == 1)
            assert(record.b == 2)
            assert(record.c == 3)
            assert(record.d == 4)
            assert(record.e == 5)
        }

        "record with option fields" in {
            val record1 = "name" ~ "Alice" & "email" ~ Some("alice@example.com")
            val record2 = "name" ~ "Bob" & "email" ~ None

            assert(record1.email.contains("alice@example.com"))
            assert(record2.email.isEmpty)
        }

        "record with collection fields" in {
            val record = "items" ~ List(1, 2, 3) & "count" ~ 3

            assert(record.items.length == 3)
            assert(record.items.head == 1)
            assert(record.count == 3)
        }

        "record type safety - should not compile invalid access" in {
            val record = "name" ~ "Alice"

            // This should compile (we can't test compilation failures in runtime tests,
            // but this documents expected behavior)
            assert(record.name == "Alice")
        }
    }

    "compiler issue #24596 - Tag compatibility" - {

        "tag equality in record fields" in {
            val record = "value" ~ 42
            val fields = Record.fieldsOf(record)

            // Tag operations should work correctly
            val field = fields.head
            assert(field.tag =:= Tag[Int])
            assert(field.tag =!= Tag[String])
        }

        "tag subtyping in record context" in {
            val record = "number" ~ 42

            // Tag subtyping should work
            val field = Record.fieldsOf(record).head
            assert(field.tag <:< Tag[AnyVal])
            assert(field.tag <:< Tag[Any])
        }

        "tag with generic types in records" in {
            val record = "list" ~ List(1, 2, 3)
            val field  = Record.fieldsOf(record).head

            // Tag should correctly represent List[Int]
            assert(field.tag =:= Tag[List[Int]])
        }

        "tag consistency across record operations" in {
            val record1 = "name" ~ "Alice"
            val record2 = "name" ~ "Bob"

            val field1 = Record.fieldsOf(record1).head
            val field2 = Record.fieldsOf(record2).head

            // Tags should be equal for same types
            assert(field1.tag =:= field2.tag)
        }
    }

    "compiler issue #24596 - Edge cases and minimization" - {

        "minimal reproduction case 1 - simple field access" in {
            val r = "x" ~ 1
            assert(r.x == 1)
        }

        "minimal reproduction case 2 - field with tag" in {
            val r = "x" ~ 1
            val f = Record.fieldsOf(r).head
            assert(f.tag =:= Tag[Int])
        }

        "minimal reproduction case 3 - duplicate fields" in {
            val r = "x" ~ "str" & "x" ~ 42
            assert((r.x: String) == "str")
            assert((r.x: Int) == 42)
        }

        "minimal reproduction case 4 - nested records" in {
            val inner = "i" ~ 1
            val outer = "inner" ~ inner
            assert(outer.inner.i == 1)
        }

        "minimal reproduction case 5 - record equality" in {
            val r1 = "x" ~ 1
            val r2 = "x" ~ 1
            assert(r1 == r2)
        }

        "minimal reproduction case 6 - toMap operation" in {
            val r = "x" ~ 1
            val m = r.toMap
            assert(m.size == 1)
        }

        "minimal reproduction case 7 - compact operation" in {
            val r = "x" ~ 1
            val c = r.compact
            assert(c.x == 1)
        }

        "minimal reproduction case 8 - getField method" in {
            val r = "x" ~ 1
            val v = r.getField["x", Int]
            assert(v == 1)
        }
    }

end RecordCompilerIssue24596Test
