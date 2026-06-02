package kyo.internal

import kyo.*

class LspDocumentRegistryTest extends Test:

    private val defaultEncRef: AtomicRef[LspHandler.PositionEncodingKind] =
        AtomicRef.Unsafe.init[LspHandler.PositionEncodingKind](LspHandler.PositionEncodingKind.UTF16)(
            using AllowUnsafe.embrace.danger
        ).safe

    private def mkRegistry(enc: LspHandler.PositionEncodingKind = LspHandler.PositionEncodingKind.UTF16): LspDocumentRegistryImpl =
        val ref = AtomicRef.Unsafe.init[LspHandler.PositionEncodingKind](enc)(using AllowUnsafe.embrace.danger).safe
        new LspDocumentRegistryImpl(ref)

    private def uri(s: String): LspHandler.LspDocument.Uri = LspHandler.LspDocument.Uri.fromWire(s)

    private def item(u: String, text: String = "hello"): LspHandler.TextDocumentItem =
        LspHandler.TextDocumentItem(uri(u), "plaintext", 1, text)

    "LspDocumentRegistryTest" - {

        "insert stamps session encoding on document" in run {
            val reg = mkRegistry(LspHandler.PositionEncodingKind.UTF8)
            reg.insert(item("file:///a.txt")).flatMap { _ =>
                reg.get(uri("file:///a.txt")).map {
                    case Present(doc) =>
                        assert(doc.encoding == LspHandler.PositionEncodingKind.UTF8)
                    case Absent =>
                        fail("Expected document")
                }
            }
        }

        "insert and get round-trip" in run {
            val reg = mkRegistry()
            reg.insert(item("file:///b.txt", "world")).flatMap { _ =>
                reg.get(uri("file:///b.txt")).map {
                    case Present(doc) =>
                        assert(doc.text == "world")
                        assert(doc.version == 1)
                    case Absent =>
                        fail("Expected document")
                }
            }
        }

        "applyChanges for unknown URI is a no-op" in run {
            val reg = mkRegistry()
            reg.applyChanges(
                uri("file:///unknown.txt"),
                2,
                Chunk(LspHandler.TextDocumentContentChangeEvent.Full("new content"))
            ).flatMap { _ =>
                reg.get(uri("file:///unknown.txt")).map {
                    case Absent     => assertionSuccess
                    case Present(_) => fail("Should have been a no-op for unknown URI")
                }
            }
        }

        "duplicate didOpen (same URI) replaces document" in run {
            val reg = mkRegistry()
            reg.insert(item("file:///c.txt", "original")).flatMap { _ =>
                reg.insert(item("file:///c.txt", "re-opened")).flatMap { _ =>
                    reg.get(uri("file:///c.txt")).map {
                        case Present(doc) =>
                            assert(doc.text == "re-opened")
                        case Absent =>
                            fail("Expected document after re-open")
                    }
                }
            }
        }

        "applyChanges updates text and version" in run {
            val reg = mkRegistry()
            reg.insert(item("file:///d.txt", "hello world")).flatMap { _ =>
                reg.applyChanges(
                    uri("file:///d.txt"),
                    2,
                    Chunk(LspHandler.TextDocumentContentChangeEvent.Full("goodbye"))
                ).flatMap { _ =>
                    reg.get(uri("file:///d.txt")).map {
                        case Present(doc) =>
                            assert(doc.text == "goodbye")
                            assert(doc.version == 2)
                        case Absent =>
                            fail("Expected document")
                    }
                }
            }
        }

        "remove for unknown URI is a no-op" in run {
            val reg = mkRegistry()
            reg.remove(uri("file:///nonexistent.txt")).flatMap { _ =>
                reg.get(uri("file:///nonexistent.txt")).map {
                    case Absent     => assertionSuccess
                    case Present(_) => fail("Should be absent")
                }
            }
        }

        "remove existing document succeeds" in run {
            val reg = mkRegistry()
            reg.insert(item("file:///e.txt")).flatMap { _ =>
                reg.remove(uri("file:///e.txt")).flatMap { _ =>
                    reg.get(uri("file:///e.txt")).map {
                        case Absent     => assertionSuccess
                        case Present(_) => fail("Should be removed")
                    }
                }
            }
        }

        "isOpen reflects insertion and removal" in run {
            val reg = mkRegistry()
            reg.insert(item("file:///f.txt")).flatMap { _ =>
                reg.isOpen(uri("file:///f.txt")).flatMap { open =>
                    assert(open)
                    reg.remove(uri("file:///f.txt")).flatMap { _ =>
                        reg.isOpen(uri("file:///f.txt")).map { closed =>
                            assert(!closed)
                        }
                    }
                }
            }
        }

        "listOpenUris returns all inserted URIs" in run {
            val reg = mkRegistry()
            reg.insert(item("file:///g.txt")).flatMap { _ =>
                reg.insert(item("file:///h.txt")).flatMap { _ =>
                    reg.listOpenUris.map { uris =>
                        assert(uris.size == 2)
                        assert(uris.contains(uri("file:///g.txt")))
                        assert(uris.contains(uri("file:///h.txt")))
                    }
                }
            }
        }

        "version returns Absent for unknown URI" in run {
            val reg = mkRegistry()
            reg.version(uri("file:///missing.txt")).map { v =>
                assert(v == Absent)
            }
        }

        "insertNotebookCell stamps session encoding" in run {
            val reg = mkRegistry(LspHandler.PositionEncodingKind.UTF8)
            reg.insertNotebookCell(uri("file:///cell1.py"), "python", "print('hello')", 1).flatMap { _ =>
                reg.get(uri("file:///cell1.py")).map {
                    case Present(doc) =>
                        assert(doc.encoding == LspHandler.PositionEncodingKind.UTF8)
                        assert(doc.languageId == "python")
                    case Absent =>
                        fail("Expected cell document")
                }
            }
        }

    }

end LspDocumentRegistryTest
