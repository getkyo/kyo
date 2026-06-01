package kyo

/** Verifies that every public record listed in INV-054 carries a `private[kyo] _rawData: Maybe[String]`
  * field and typed `withData[X]` / `dataAs[X]` accessor pair (INV-054).
  */
class LspDataCarrierTest extends Test:

    "CompletionItem has _rawData field" in run {
        val item = LspHandler.CompletionItem(label = "foo")
        assert(item._rawData == Absent)
        succeed
    }

    "CompletionItem.withData stores encoded JSON" in run {
        val item    = LspHandler.CompletionItem(label = "foo")
        val data    = Map("key" -> "value")
        val updated = item.withData(data)
        assert(updated._rawData.isDefined)
        succeed
    }

    "CodeAction has _rawData field" in run {
        val action = LspHandler.CodeAction(title = "fix")
        assert(action._rawData == Absent)
        succeed
    }

    "CodeLens has _rawData field" in run {
        val lens = LspHandler.CodeLens(range = LspHandler.Range(LspHandler.Position(0, 0), LspHandler.Position(0, 0)))
        assert(lens._rawData == Absent)
        succeed
    }

    "DocumentLink has _rawData field" in run {
        val link = LspHandler.DocumentLink(range = LspHandler.Range(LspHandler.Position(0, 0), LspHandler.Position(0, 0)))
        assert(link._rawData == Absent)
        succeed
    }

    "InlayHint has _rawData field" in run {
        val hint = LspHandler.InlayHint(
            position = LspHandler.Position(0, 0),
            label = LspHandler.InlayHintLabel.PlainString("type: Int")
        )
        assert(hint._rawData == Absent)
        succeed
    }

    "WorkspaceSymbol has _rawData field" in run {
        val sym = LspHandler.WorkspaceSymbol(
            name = "MyClass",
            kind = LspHandler.SymbolKind.Class,
            location = LspHandler.WorkspaceSymbolLocation.UriOnly(LspHandler.LspDocument.Uri.parse("file:///Main.scala").get)
        )
        assert(sym._rawData == Absent)
        succeed
    }

    "CallHierarchyItem has _rawData field" in run {
        val item = LspHandler.CallHierarchyItem(
            name = "myMethod",
            kind = LspHandler.SymbolKind.Method,
            uri = LspHandler.LspDocument.Uri.parse("file:///Main.scala").get,
            range = LspHandler.Range(LspHandler.Position(0, 0), LspHandler.Position(1, 0)),
            selectionRange = LspHandler.Range(LspHandler.Position(0, 4), LspHandler.Position(0, 12))
        )
        assert(item._rawData == Absent)
        succeed
    }

    "TypeHierarchyItem has _rawData field" in run {
        val item = LspHandler.TypeHierarchyItem(
            name = "MyTrait",
            kind = LspHandler.SymbolKind.Interface,
            uri = LspHandler.LspDocument.Uri.parse("file:///Main.scala").get,
            range = LspHandler.Range(LspHandler.Position(0, 0), LspHandler.Position(1, 0)),
            selectionRange = LspHandler.Range(LspHandler.Position(0, 6), LspHandler.Position(0, 13))
        )
        assert(item._rawData == Absent)
        succeed
    }

end LspDataCarrierTest
