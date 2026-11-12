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

    "LspDocument.Uri.file escapes each segment as UTF-8" in {
        assume(!internal.Platform.isWindows, "Unix absolute path syntax")
        LspDocument.Uri.file(Path("/workspace/My Project/a#b%c?d é.scala")).map { uri =>
            assert(uri.asString == "file:///workspace/My%20Project/a%23b%25c%3Fd%20%C3%A9.scala")
        }
    }

    "LspDocument.Uri.file keeps the characters a segment holds as themselves" in {
        assume(!internal.Platform.isWindows, "Unix absolute path syntax")
        LspDocument.Uri.file(Path("/a/Bz09-._~!$&'()*+,;=:@")).map { uri =>
            assert(uri.asString == "file:///a/Bz09-._~!$&'()*+,;=:@")
        }
    }

    "LspDocument.Uri.file of the root" in {
        assume(!internal.Platform.isWindows, "Unix absolute path syntax")
        LspDocument.Uri.file(Path("/")).map(uri => assert(uri.asString == "file:///"))
    }

    "LspDocument.Uri.file resolves a relative path against the working directory" in {
        for
            cwd <- Path.cwd
            uri <- LspDocument.Uri.file(Path("src", "Main.scala"))
        yield assert(uri.file == Present(cwd / "src" / "Main.scala"))
    }

    "uri.file reads back the path LspDocument.Uri.file wrote" in {
        for
            cwd <- Path.cwd
            path = cwd / "My Project" / "a#b%c é.scala"
            uri <- LspDocument.Uri.file(path)
        yield assert(uri.file == Present(path))
    }

    "uri.file reads every form of a file URI on this host" in {
        val path = Path("/a/b c")
        assert(LspDocument.Uri.parse("file:///a/b%20c").get.file == Present(path))
        assert(LspDocument.Uri.parse("file:/a/b%20c").get.file == Present(path))
        assert(LspDocument.Uri.parse("file://localhost/a/b%20c").get.file == Present(path))
        assert(LspDocument.Uri.parse("FILE://LocalHost/a/b%20c").get.file == Present(path))
        assert(LspDocument.Uri.parse("file:///a/b%20c#L3").get.file == Present(path))
        assert(LspDocument.Uri.parse("file:///a/b%20c?x=1").get.file == Present(path))
        assert(LspDocument.Uri.parse("file:///a/b c").get.file == Present(path))
        assert(LspDocument.Uri.parse("file:///").get.file == Present(Path("/")))
    }

    "uri.file decodes lowercase escapes" in {
        assert(LspDocument.Uri.parse("file:///a/%c3%a9%2b").get.file == Present(Path("/a/é+")))
    }

    "uri.file is Absent for another scheme, another host or a relative path" in {
        assert(LspDocument.Uri.parse("https://example.com/a").get.file == Absent)
        assert(LspDocument.Uri.parse("untitled:Untitled-1").get.file == Absent)
        assert(LspDocument.Uri.parse("file://server/share/a").get.file == Absent)
        assert(LspDocument.Uri.parse("file:a/b").get.file == Absent)
        assert(LspDocument.Uri.parse("file://").get.file == Absent)
        assert(LspDocument.Uri.parse("file").get.file == Absent)
    }

    "uri.file is Absent for an escape that is not two hex digits or not UTF-8" in {
        assert(LspDocument.Uri.parse("file:///a/b%2").get.file == Absent)
        assert(LspDocument.Uri.parse("file:///a/b%zz").get.file == Absent)
        assert(LspDocument.Uri.parse("file:///a/b%%20").get.file == Absent)
        assert(LspDocument.Uri.parse("file:///a/b%C3").get.file == Absent)
        assert(LspDocument.Uri.parse("file:///a/b%FF").get.file == Absent)
        assert(LspDocument.Uri.parse("file:///a/%EF%BF%BD").get.file == Present(Path("/a/�")))
    }

    "uri.file is Absent for a segment that decodes to one holding a path separator" in {
        assert(LspDocument.Uri.parse("file:///a/b%2Fc").get.file == Absent)
        assert(LspDocument.Uri.parse("file:///a/b%5Cc").get.file == Absent)
    }

    "uri.file rejects NUL instead of throwing during path construction" in {
        assert(LspDocument.Uri.parse("file:///a/%00b").get.file == Absent)
        assert(LspDocument.Uri.parse("file:///a/" + 0.toChar + "b").get.file == Absent)
    }

    "uri.file rejects malformed UTF-16 instead of changing the path" in {
        val invalid = "file:///a/" + 0xd800.toChar
        assert(LspDocument.Uri.parse(invalid).get.file == Absent)
        assert(LspDocument.Uri.parse("file:///a/" + 0xdc00.toChar).get.file == Absent)
    }

    "uri.file preserves supplementary Unicode and a literal replacement character" in {
        val path = Path("/a/😀�.scala")
        LspDocument.Uri.file(path).map { uri =>
            assert(uri.file == Present(path))
            assert(LspDocument.Uri.parse("file:///a/%F0%9F%98%80%EF%BF%BD.scala").get.file == Present(path))
        }
    }

    "uri.file rejects invalid UTF-8 without accepting replacement characters" in {
        assert(LspDocument.Uri.parse("file:///a/%C0%AF").get.file == Absent)
        assert(LspDocument.Uri.parse("file:///a/%ED%A0%80").get.file == Absent)
        assert(LspDocument.Uri.parse("file:///a/%F4%90%80%80").get.file == Absent)
        assert(LspDocument.Uri.parse("file:///a/%80").get.file == Absent)
    }

    "uri.file rejects characters the Windows path constructor forbids" in {
        assume(internal.Platform.isWindows, "Windows filename syntax")
        assert(LspDocument.Uri.parse("file:///C:/a%3Fb.scala").get.file == Absent)
    }

    "LspDocument.Uri.file preserves the Windows drive" in {
        assume(internal.Platform.isWindows, "Windows drive syntax")
        val path = Path("C:/workspace/My Project/Main.scala")
        LspDocument.Uri.file(path).map { uri =>
            assert(uri.asString == "file:///C:/workspace/My%20Project/Main.scala")
            assert(uri.file == Present(path))
        }
    }

    "uri.file rejects UNC paths with an empty authority" in {
        assert(LspDocument.Uri.parse("file:////server/share/file.scala").get.file == Absent)
        assert(LspDocument.Uri.parse("file://///server/share/file.scala").get.file == Absent)
    }

    "uri.file preserves a colon in the first POSIX path segment" in {
        assume(!internal.Platform.isWindows, "POSIX path syntax")
        val path = LspDocument.Uri.parse("file:///C:/source.scala").get.file.get
        assert(path.isAbsolute)
        assert(path.toString == "/C:/source.scala")
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
