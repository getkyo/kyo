package kyo

class LspDocumentPathTest extends Test:

    "a Windows network path retains its host in the file URI" in {
        assume(internal.Platform.isWindows, "Windows UNC path syntax")
        val path = Path.of(java.nio.file.Path.of("\\\\server\\share\\My Project\\Main.scala"))
        LspHandler.LspDocument.Uri.file(path).map { uri =>
            assert(uri.asString == "file://server/share/My%20Project/Main.scala")
            assert(uri.file == Absent)
        }
    }
end LspDocumentPathTest
