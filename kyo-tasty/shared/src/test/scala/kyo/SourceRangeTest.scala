package kyo

import kyo.Json

/** Tests for the SourceRange ADT (construction, show, structural equality, Schema round-trip).
  *
  * Coverage:
  *   1. Constructs a span and reads back all five fields.
  *   2. show renders file:startLine:startColumn-endLine:endColumn.
  *   3. Structural equality across all five fields (equal and not-equal pair).
  *   4. Schema round-trip via Json preserves all fields.
  */
class SourceRangeTest extends kyo.test.Test[Any]:

    "constructs a span and reads back all five fields" in {
        val sr = Tasty.SourceRange("Foo.scala", 6, 9, 6, 15)
        assert(sr.sourceFile == "Foo.scala", s"sourceFile mismatch: ${sr.sourceFile}")
        assert(sr.startLine == 6, s"startLine mismatch: ${sr.startLine}")
        assert(sr.startColumn == 9, s"startColumn mismatch: ${sr.startColumn}")
        assert(sr.endLine == 6, s"endLine mismatch: ${sr.endLine}")
        assert(sr.endColumn == 15, s"endColumn mismatch: ${sr.endColumn}")
        succeed
    }

    "show renders file:startLine:startColumn-endLine:endColumn" in {
        val sr = Tasty.SourceRange("Foo.scala", 6, 9, 6, 15)
        assert(sr.show == "Foo.scala:6:9-6:15", s"show mismatch: ${sr.show}")
        succeed
    }

    "structural equality across all five fields (equal and not-equal)" in {
        val sr1 = Tasty.SourceRange("Foo.scala", 6, 9, 6, 15)
        val sr2 = Tasty.SourceRange("Foo.scala", 6, 9, 6, 15)
        val sr3 = Tasty.SourceRange("Foo.scala", 6, 9, 6, 16)
        assert(sr1 == sr2, "identical spans must be equal")
        assert(sr1 != sr3, "spans differing in endColumn must not be equal")
        succeed
    }

    "Schema round-trip via Json preserves all fields" in {
        val original = Tasty.SourceRange("Bar.scala", 10, 3, 12, 7)
        val encoded  = Json.encode(original)
        Json.decode[Tasty.SourceRange](encoded) match
            case Result.Success(sr) =>
                assert(sr == original, s"round-trip mismatch: $sr != $original")
            case Result.Failure(e) =>
                fail(s"Json.decode failed: $e")
            case Result.Panic(t) =>
                throw t
        end match
        succeed
    }

end SourceRangeTest
