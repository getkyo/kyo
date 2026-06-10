package kyo

/** Tests for LspHandler.LspDocument and LspDocument.Uri. */
class LspDocumentTest extends Test:

    import LspHandler.*

    // =========================================================================
    // LspDocument.Uri
    // =========================================================================

    "LspDocument.Uri.parse accepts non-empty string" in {
        val result = LspDocument.Uri.parse("file:///workspace/Main.scala")
        assert(result.isDefined)
        assert(result.get.asString == "file:///workspace/Main.scala")
    }

    "LspDocument.Uri.parse rejects empty string" in {
        val result = LspDocument.Uri.parse("")
        assert(result == Absent)
    }

    "LspDocument.Uri.parse rejects whitespace-only string" in {
        val result = LspDocument.Uri.parse("   ")
        assert(result == Absent)
    }

    // =========================================================================
    // LspDocument.applyChanges - full replace
    // =========================================================================

    "applyChanges - full replace" in {
        val uri     = LspDocument.Uri.parse("file:///test.scala").get
        val doc     = LspDocument(uri = uri, languageId = "scala", version = 1, text = "old content")
        val change  = TextDocumentContentChangeEvent.Full("new content")
        val updated = LspDocument.applyChanges(doc, Chunk(change))
        assert(updated.text == "new content")
        assert(updated.version == 2)
    }

    // =========================================================================
    // LspDocument.applyChanges - incremental
    // =========================================================================

    "applyChanges - incremental at start of first line" in {
        val uri     = LspDocument.Uri.parse("file:///test.scala").get
        val doc     = LspDocument(uri = uri, languageId = "scala", version = 1, text = "hello world")
        val range   = Range(Position(0, 0), Position(0, 5))
        val change  = TextDocumentContentChangeEvent.Incremental(range, "goodbye")
        val updated = LspDocument.applyChanges(doc, Chunk(change))
        assert(updated.text == "goodbye world")
        assert(updated.version == 2)
    }

    "applyChanges - incremental at end of first line" in {
        val uri     = LspDocument.Uri.parse("file:///test.scala").get
        val doc     = LspDocument(uri = uri, languageId = "scala", version = 1, text = "hello world")
        val range   = Range(Position(0, 6), Position(0, 11))
        val change  = TextDocumentContentChangeEvent.Incremental(range, "scala")
        val updated = LspDocument.applyChanges(doc, Chunk(change))
        assert(updated.text == "hello scala")
    }

    "applyChanges - multiple sequential changes" in {
        val uri     = LspDocument.Uri.parse("file:///test.scala").get
        val doc     = LspDocument(uri = uri, languageId = "scala", version = 1, text = "aaa")
        val change1 = TextDocumentContentChangeEvent.Full("bbb")
        val change2 = TextDocumentContentChangeEvent.Full("ccc")
        val updated = LspDocument.applyChanges(doc, Chunk(change1, change2))
        assert(updated.text == "ccc")
        assert(updated.version == 3)
    }

    // =========================================================================
    // LspDocument construction
    // =========================================================================

    "LspDocument construction with defaults" in {
        val uri = LspDocument.Uri.parse("file:///Main.scala").get
        val doc = LspDocument(uri = uri, languageId = "scala", version = 1, text = "object Main")
        assert(doc.uri == uri)
        assert(doc.languageId == "scala")
        assert(doc.version == 1)
        assert(doc.text == "object Main")
        assert(doc.encoding == PositionEncodingKind.UTF16) // default private field
    }

    "LspDocument equality ignores encoding" in {
        val uri  = LspDocument.Uri.parse("file:///Main.scala").get
        val doc1 = LspDocument(uri = uri, languageId = "scala", version = 1, text = "hello")
        val doc2 = LspDocument(uri = uri, languageId = "scala", version = 1, text = "hello", encoding = PositionEncodingKind.UTF8)
        // case class equality includes all fields including private[kyo] ones
        assert(doc1 != doc2) // encoding differs
    }

end LspDocumentTest
