package demo

import kyo.*
import kyo.Style.*
import kyo.UI.*
import kyo.UI.Ast.HtmlContent

/** Inventory grid with independently sortable rows and columns, served as a server-push app.
  *
  * One `SignalRef[Inventory]` holds both the column order and the row order. Header cells are drag sources in the
  * horizontal `inventory-columns` collection, row wrappers are drag sources in the vertical `inventory-rows`
  * collection, and ordinary cells re-render from the current column order, so a column drag reorders every row's
  * cells. The two collections are incompatible: dropping a row on the column strip is rejected without mutation.
  *
  * Run via `sbt 'kyo-uiJVM/Test/runMain demo.InventoryGridDemo'` (optional port as the first argument). Drag rows
  * vertically, drag column headers horizontally, and tick the checkbox on several rows to move them together in
  * visible order. The SKU column is locked: it can neither move nor anchor a move.
  *
  * Demonstrates: two sortable collections over one state ref, a single total [[applyMove]] reducer built on
  * [[kyo.Sortable.moveBy]] and [[kyo.Sortable.expandSelection]] shared by pointer, keyboard, and programmatic
  * movement, declarative locking through the `locked` set, `Drag.Source.sortable`/`Drag.Target.sortable` payloads,
  * and `foreachKeyed` rendering for both axes.
  */
object InventoryGridDemo extends KyoApp:

    case class Column(id: String, title: String) derives CanEqual
    case class Row(id: String, cells: Map[String, String]) derives CanEqual
    case class Inventory(columns: Chunk[Column], rows: Chunk[Row]) derives CanEqual

    /** Collection names carried by [[Drag.Location]] for the two sortable axes. */
    val ColumnsCollection = "inventory-columns"
    val RowsCollection    = "inventory-rows"

    /** The locked column: it can neither move nor anchor a move. */
    val LockedColumn = "sku"

    private val seed = Inventory(
        columns = Chunk(
            Column("sku", "SKU"),
            Column("name", "Name"),
            Column("qty", "Quantity"),
            Column("price", "Price")
        ),
        rows = Chunk(
            Row("r1", Map("sku" -> "A-100", "name" -> "Anvil", "qty" -> "3", "price" -> "49.00")),
            Row("r2", Map("sku" -> "B-200", "name" -> "Rope", "qty" -> "12", "price" -> "9.50")),
            Row("r3", Map("sku" -> "C-300", "name" -> "Magnet", "qty" -> "7", "price" -> "19.95"))
        )
    )

    /** The one total move reducer shared by pointer drags, keyboard moves, and programmatic movement.
      *
      * Rows reorder within `inventory-rows` ([[kyo.Sortable.expandSelection]] moves a row selection together in
      * visible order), columns reorder within `inventory-columns` with the SKU column locked through the
      * [[kyo.Sortable.moveBy]] `locked` set, and the two collections never mix. Invalid moves return a typed
      * rejection and the inventory is untouched.
      */
    def applyMove(inventory: Inventory, selectedRows: Set[String], move: Drag.Move): Result[Drag.Rejection, Inventory] =
        (move.source.collection, move.destination.collection) match
            case (RowsCollection, RowsCollection) =>
                val effective = Sortable.expandSelection(inventory.rows.map(_.id), selectedRows, move.keys)
                Sortable.moveBy(inventory.rows, inventory.rows, move.copy(keys = effective))(_.id)
                    .map((updated, _) => inventory.copy(rows = updated))
            case (ColumnsCollection, ColumnsCollection) =>
                Sortable.moveBy(inventory.columns, inventory.columns, move, locked = Set(LockedColumn))(_.id)
                    .map((updated, _) => inventory.copy(columns = updated))
            case (RowsCollection, ColumnsCollection) | (ColumnsCollection, RowsCollection) =>
                Result.fail(Drag.Rejection.Application("Rows and columns cannot be mixed."))
            case (source, destination) =>
                val unknown = if source == RowsCollection || source == ColumnsCollection then destination else source
                Result.fail(Drag.Rejection.Application(s"Unknown collection: $unknown"))
        end match
    end applyMove

    private val pageStyle   = Style.padding(24.px).fontFamily(FontFamily.SansSerif).gap(16.px)
    private val tableStyle  = Style.bg(Color.white).rounded(8.px).shadow(0.px, 1.px, 3.px, 0.px, Color.rgba(0, 0, 0, 0.18))
    private val headerStyle = Style.bg(Color.slate).color(Color.white).bold.padding(8.px, 12.px)
    private val lockedStyle = headerStyle.italic
    private val cellStyle   = Style.padding(8.px, 12.px)

    private def headerCell(c: Column): HtmlContent =
        if c.id == LockedColumn then th(s"${c.title} (locked)").id(s"col-${c.id}").style(lockedStyle)
        else
            th.id(s"col-${c.id}")
                .style(headerStyle)
                .dragSource(Drag.Source.sortable(c.id, Present(c.title)))(c.title)

    private def rowCells(r: Row, columns: Chunk[Column], selection: SignalRef[Set[String]]): HtmlContent =
        tr.id(s"row-${r.id}")
            .dragSource(Drag.Source.sortable(r.id, Present(r.cells.getOrElse("name", r.id))))(
                td.style(cellStyle)(
                    checkbox.id(s"select-${r.id}")
                        .onChange(on => selection.updateAndGet(s => if on then s + r.id else s - r.id).unit)
                        .checked(selection.map(_.contains(r.id)))
                ),
                fragment(columns.map(c => td.style(cellStyle)(r.cells.getOrElse(c.id, "")): HtmlContent)*)
            )

    /** The reusable sortable grid view over the two state refs; `gridUI` and the drag scenario tests share it. */
    def gridView(state: SignalRef[Inventory], selection: SignalRef[Set[String]])(using Frame): UI =
        val commit: Drag.Move => Drag.Decision < Async = move =>
            for
                sel      <- selection.get
                current  <- state.get
                decision <- Drag.Decision.fromResult(applyMove(current, sel, move))(next => state.set(next))
            yield decision
        div.onSortMove(commit)(
            UI.table.id("inventory").style(tableStyle)(
                tr.dropTarget(Drag.Target.sortable(ColumnsCollection, Present("Columns"), Drag.Orientation.Horizontal))(
                    th.style(headerStyle)("Select"),
                    state.map(_.columns).foreachKeyed(_.id)(headerCell)
                ),
                tbody.dropTarget(Drag.Target.sortable(RowsCollection, Present("Rows")))(
                    state.render(inv => fragment(inv.rows.map(r => rowCells(r, inv.columns, selection): HtmlContent)*))
                )
            )
        )
    end gridView

    private def gridUI: UI < Async =
        for
            state     <- Signal.initRef(seed)
            selection <- Signal.initRef(Set.empty[String])
        yield UI.main.style(pageStyle)(
            h1("Inventory"),
            p("Drag rows vertically and column headers horizontally. The SKU column is locked."),
            gridView(state, selection)
        )

    run {
        val port = args.headOption.flatMap(_.toIntOption).getOrElse(0)
        for
            handlers <- UI.runHandlers("/")(gridUI)
            server   <- HttpServer.init(port, "localhost")(handlers*)
            _        <- Console.printLine(s"Inventory grid running on http://localhost:${server.port}/")
            _        <- server.await
        yield ()
        end for
    }
end InventoryGridDemo
