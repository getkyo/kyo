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
  * [[kyo.Sortable.move]] shared by pointer, keyboard, and programmatic movement, application-level locking through a
  * typed rejection, and `foreachKeyed` rendering for both axes.
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

    private val textPlain        = Drag.MediaType.parse("text/plain").get
    private val textPlainPattern = Drag.MediaTypePattern.exact(textPlain)

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

    private def reject(reason: String): Result[Drag.Rejection, Inventory] =
        Result.fail(Drag.Rejection.Application(reason))

    /** The one total move reducer shared by pointer drags, keyboard moves, and programmatic movement.
      *
      * Rows reorder within `inventory-rows` (a selection of rows moves together in visible order), columns reorder
      * within `inventory-columns`, and the two collections never mix. The locked SKU column can neither move nor
      * anchor a move. Invalid moves return a typed rejection and the inventory is untouched.
      */
    def applyMove(inventory: Inventory, selectedRows: Set[String], move: Drag.Move): Result[Drag.Rejection, Inventory] =
        val source      = move.source.collection
        val destination = move.destination.collection
        (source, destination) match
            case (RowsCollection, RowsCollection) =>
                val visible = inventory.rows.map(_.id)
                val effective =
                    if move.keys.nonEmpty && move.keys.forall(selectedRows.contains) then visible.filter(selectedRows.contains)
                    else move.keys
                val rowsById = inventory.rows.map(r => r.id -> r).toMap
                Sortable.move(visible, visible, move.copy(keys = effective)).map { (updated, _) =>
                    inventory.copy(rows = updated.map(rowsById))
                }
            case (ColumnsCollection, ColumnsCollection) =>
                if move.keys.contains(LockedColumn) || move.anchor.contains(LockedColumn) then
                    reject("The SKU column is locked.")
                else
                    val visible     = inventory.columns.map(_.id)
                    val columnsById = inventory.columns.map(c => c.id -> c).toMap
                    Sortable.move(visible, visible, move).map { (updated, _) =>
                        inventory.copy(columns = updated.map(columnsById))
                    }
            case (RowsCollection, ColumnsCollection) | (ColumnsCollection, RowsCollection) =>
                reject("Rows and columns cannot be mixed.")
            case _ =>
                reject(s"Unknown collection: ${if source == RowsCollection || source == ColumnsCollection then destination else source}")
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
                .dragSource(Drag.Source(
                    c.id,
                    Chunk(Drag.Item.Text(Map(textPlain -> c.title))),
                    label = Present(c.title)
                ))(c.title)

    private def rowCells(r: Row, columns: Chunk[Column], selection: SignalRef[Set[String]]): HtmlContent =
        tr.id(s"row-${r.id}")
            .dragSource(Drag.Source(
                r.id,
                Chunk(Drag.Item.Text(Map(textPlain -> r.cells.getOrElse("name", r.id)))),
                label = Present(r.cells.getOrElse("name", r.id))
            ))(
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
                sel     <- selection.get
                current <- state.get
                decided: (Drag.Decision < Async) = applyMove(current, sel, move) match
                    case Result.Success(next)     => state.set(next).andThen(Drag.Decision.Accept)
                    case Result.Failure(rejected) => Drag.Decision.Reject(rejected)
                    case _: Result.Panic          => Drag.Decision.Reject(Drag.Rejection.Application("Inventory move failed."))
                decision <- decided
            yield decision
        div.onSortMove(commit)(
            UI.table.id("inventory").style(tableStyle)(
                tr.dropTarget(Drag.Target(ColumnsCollection, Drag.Accept.types(textPlainPattern), Present("Columns")))(
                    th.style(headerStyle)("Select"),
                    state.map(_.columns).foreachKeyed(_.id)(headerCell)
                ),
                tbody.dropTarget(Drag.Target(RowsCollection, Drag.Accept.types(textPlainPattern), Present("Rows")))(
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
