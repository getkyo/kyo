package kyo.ffi.codegen

class TastyExtractorSmokeTest extends kyo.test.Test[Any]:

    "TastyExtractor" - {
        "returns an empty list when given no TASTy files" in {
            val specs = new TastyExtractor().inspect(Nil, Nil)
            assert(specs.isEmpty)
        }

        "is idempotent across successive invocations" in {
            val extractor = new TastyExtractor()
            assert(extractor.inspect(Nil, Nil).isEmpty)
            assert(extractor.inspect(Nil, Nil).isEmpty)
        }
    }

    "ExtractorError.toString" - {
        "includes line info when line > 0" in {
            assert(ExtractorError("Foo.scala", 42, "boom").toString == "Foo.scala:42: boom")
        }
        "omits line info when line = 0" in {
            assert(ExtractorError("Foo.scala", 0, "boom").toString == "Foo.scala: boom")
        }
    }

    "FfiExtractionError.getMessage aggregates errors" in {
        val err = FfiExtractionError(List(
            ExtractorError("A.scala", 1, "first"),
            ExtractorError("B.scala", 0, "second")
        ))
        assert(err.getMessage.contains("A.scala:1: first"))
        assert(err.getMessage.contains("B.scala: second"))
    }
end TastyExtractorSmokeTest
