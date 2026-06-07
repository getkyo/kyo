package kyo.test.internal

import kyo.Render
import org.scalatest.NonImplicitAssertions
import org.scalatest.freespec.AsyncFreeSpec
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

// ScalaTest bootstrap: kyo-test-api cannot depend on kyo-test-runner (circular); only ScalaTest is available here.
class DiffTest extends AsyncFreeSpec with NonImplicitAssertions:

    implicit override val executionContext: ExecutionContext = scala.concurrent.ExecutionContext.global

    // A simple case class for testing
    case class User(name: String, age: Int)
    given Render[User] = Render.from(u => s"User(${u.name}, ${u.age})")

    // A class with no Render instance (beyond the low-priority toString fallback)
    class Opaque(val x: Int):
        override def toString: String = s"Opaque($x)"

    "Diff" - {

        "render: uses Render instance when available" in {
            val actual   = User("Bob", 14)
            val expected = User("Alice", 30)
            val result   = Diff.render(actual, expected)
            assert(result.contains("Bob"), s"Expected 'Bob' in:\n$result")
            assert(result.contains("Alice"), s"Expected 'Alice' in:\n$result")
            assert(result.contains("14"), s"Expected '14' in:\n$result")
            assert(result.contains("30"), s"Expected '30' in:\n$result")
            Future.successful(succeed)
        }

        "render: falls back to plain when no explicit Render" in {
            val actual   = new Opaque(1)
            val expected = new Opaque(2)
            val result   = Diff.render(actual, expected)
            // Low-priority Render uses toString; result should show both values
            assert(result.contains("Opaque(1)"), s"Expected 'Opaque(1)' in:\n$result")
            assert(result.contains("Opaque(2)"), s"Expected 'Opaque(2)' in:\n$result")
            Future.successful(succeed)
        }

        "caseClassDiff: field-aligned for products" in {
            val actual   = User("Bob", 14)
            val expected = User("Alice", 30)
            val result   = Diff.caseClassDiff(actual, expected)
            // Must show actual and expected lines
            assert(result.contains("actual"), s"Expected 'actual' in:\n$result")
            assert(result.contains("expected"), s"Expected 'expected' in:\n$result")
            // Must show field names
            assert(result.contains("name"), s"Expected 'name' in:\n$result")
            assert(result.contains("age"), s"Expected 'age' in:\n$result")
            // Must show values
            assert(result.contains("Bob"), s"Expected 'Bob' in:\n$result")
            assert(result.contains("Alice"), s"Expected 'Alice' in:\n$result")
            assert(result.contains("14"), s"Expected '14' in:\n$result")
            assert(result.contains("30"), s"Expected '30' in:\n$result")
            // Must have underlines marking mismatched fields
            assert(result.contains("~"), s"Expected '~' underlines in:\n$result")
            Future.successful(succeed)
        }

        "collectionDiff: element-aligned for Lists" in {
            val actual   = List(1, 2, 3)
            val expected = List(1, 5, 3)
            val result   = Diff.collectionDiff(actual, expected)
            // Must show both lists
            assert(result.contains("actual"), s"Expected 'actual' in:\n$result")
            assert(result.contains("expected"), s"Expected 'expected' in:\n$result")
            // Must contain the differing element
            assert(result.contains("2"), s"Expected '2' in:\n$result")
            assert(result.contains("5"), s"Expected '5' in:\n$result")
            // Must have underlines marking the mismatched position
            assert(result.contains("~"), s"Expected '~' underlines in:\n$result")
            Future.successful(succeed)
        }

        "stringDiff: unified-diff for multi-line" in {
            val actual   = "line1\nline2\nline3"
            val expected = "line1\nchanged\nline3"
            val result   = Diff.stringDiff(actual, expected)
            // Must indicate removed and added lines
            assert(
                result.contains("- line2") || result.contains("-line2"),
                s"Expected removed line2 indicator in:\n$result"
            )
            assert(
                result.contains("+ changed") || result.contains("+changed"),
                s"Expected added changed indicator in:\n$result"
            )
            // Must contain unchanged line
            assert(result.contains("line1"), s"Expected 'line1' in:\n$result")
            assert(result.contains("line3"), s"Expected 'line3' in:\n$result")
            Future.successful(succeed)
        }

        "stringDiff: line-diff round-trip with one changed line" in {
            val actual   = "alpha\nbeta\ngamma"
            val expected = "alpha\nDELTA\ngamma"
            val result   = Diff.stringDiff(actual, expected)
            assert(result.contains("- beta"), s"Expected '- beta' in:\n$result")
            assert(result.contains("+ DELTA"), s"Expected '+ DELTA' in:\n$result")
            assert(!result.contains("- alpha"), s"Unchanged line alpha must not be marked in:\n$result")
            Future.successful(succeed)
        }

        "collectionDiff: output starts with Chunk prefix" in {
            val result = Diff.collectionDiff(List(1, 2, 3), List(1, 9, 3))
            assert(result.startsWith("  actual:   Chunk("), s"Expected output to start with '  actual:   Chunk(' but got:\n$result")
            assert(result.contains("  expected: Chunk("), s"Expected output to contain '  expected: Chunk(' but got:\n$result")
            Future.successful(succeed)
        }

    }

end DiffTest
