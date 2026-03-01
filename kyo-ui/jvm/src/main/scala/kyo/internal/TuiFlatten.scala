package kyo.internal

import kyo.*
import kyo.Maybe.*
import kyo.UI.AST
import kyo.UI.AST.*

/** Walk UI AST, resolve signals inline, resolve styles → TuiLayout flat arrays.
  *
  * No intermediate snapshot tree. Signals are resolved during traversal and collected for awaitAny. Uses AllowUnsafe + Sync.Unsafe.run to
  * avoid computation allocations.
  */
private[kyo] object TuiFlatten:

    /** Read current signal value, bypassing computation allocation. Safe because Sync.Unsafe.run on signal.current produces a value with
      * only Abort[Nothing] pending (which can never suspend).
      */
    private inline def readSignal[A](s: Signal[A])(using Frame, AllowUnsafe): A =
        Sync.Unsafe.evalOrThrow(s.current)

    /** Flatten the UI tree into the layout's flat arrays. Resets layout and signals before starting. */
    def flatten(ui: UI, layout: TuiLayout, signals: TuiSignalCollector, parentW: Int, parentH: Int)(
        using
        Frame,
        AllowUnsafe
    ): Unit =
        layout.reset()
        signals.reset()
        flattenNode(ui, layout, signals, -1, parentW, parentH)
    end flatten

    private def flattenNode(
        ui: UI,
        layout: TuiLayout,
        signals: TuiSignalCollector,
        parentIdx: Int,
        parentW: Int,
        parentH: Int
    )(using Frame, AllowUnsafe): Unit =
        ui match
            case AST.Text(value) =>
                val idx = layout.alloc()
                TuiLayout.linkChild(layout, parentIdx, idx)
                TuiStyle.setDefaults(layout, idx)
                layout.text(idx) = Present(value)
                layout.nodeType(idx) = TuiLayout.NodeText.toByte

            case elem: Element =>
                val idx = layout.alloc()
                TuiLayout.linkChild(layout, parentIdx, idx)
                resolveElement(elem, layout, signals, idx, parentW, parentH)
                elem.children.foreach(child =>
                    flattenNode(child, layout, signals, idx, parentW, parentH)
                )

            case rt: ReactiveText =>
                signals.add(rt.signal)
                val value = readSignal(rt.signal)
                val idx   = layout.alloc()
                TuiLayout.linkChild(layout, parentIdx, idx)
                TuiStyle.setDefaults(layout, idx)
                layout.text(idx) = Present(value)
                layout.nodeType(idx) = TuiLayout.NodeText.toByte

            case rn: ReactiveNode =>
                signals.add(rn.signal)
                val resolved = readSignal(rn.signal)
                flattenNode(resolved, layout, signals, parentIdx, parentW, parentH)

            case fi: ForeachIndexed[?] @unchecked =>
                signals.add(fi.signal)
                val items   = readSignal(fi.signal)
                val render  = fi.render.asInstanceOf[(Int, Any) => UI]
                val indexed = items.toIndexed
                @scala.annotation.tailrec
                def loop(i: Int): Unit =
                    if i < indexed.size then
                        flattenNode(render(i, indexed(i)), layout, signals, parentIdx, parentW, parentH)
                        loop(i + 1)
                loop(0)

            case fk: ForeachKeyed[?] @unchecked =>
                signals.add(fk.signal)
                val items   = readSignal(fk.signal)
                val render  = fk.render.asInstanceOf[(Int, Any) => UI]
                val indexed = items.toIndexed
                @scala.annotation.tailrec
                def loop(i: Int): Unit =
                    if i < indexed.size then
                        flattenNode(render(i, indexed(i)), layout, signals, parentIdx, parentW, parentH)
                        loop(i + 1)
                loop(0)

            case AST.Fragment(children) =>
                children.foreach(child =>
                    flattenNode(child, layout, signals, parentIdx, parentW, parentH)
                )
    end flattenNode

    private def resolveElement(
        elem: Element,
        layout: TuiLayout,
        signals: TuiSignalCollector,
        idx: Int,
        parentW: Int,
        parentH: Int
    )(using Frame, AllowUnsafe): Unit =
        TuiStyle.resolve(elem.common.uiStyle, layout, idx, parentW, parentH)
        layout.element(idx) = Present(elem.asInstanceOf[AnyRef])
        layout.nodeType(idx) = elementNodeType(elem).toByte
        if TuiLayout.isInlineNode(layout.nodeType(idx)) then
            layout.lFlags(idx) = layout.lFlags(idx) | (1 << TuiLayout.DirBit)
        resolveHidden(elem.common.hidden, layout, signals, idx)
        resolveDisabledIfInteractive(elem, layout, signals, idx)
    end resolveElement

    private def resolveHidden(
        hidden: Maybe[Boolean | Signal[Boolean]],
        layout: TuiLayout,
        signals: TuiSignalCollector,
        idx: Int
    )(using Frame, AllowUnsafe): Unit =
        if !hidden.isEmpty then
            hidden.get match
                case b: Boolean =>
                    if b then layout.lFlags(idx) = layout.lFlags(idx) | (1 << TuiLayout.HiddenBit)
                case s: Signal[?] =>
                    signals.add(s)
                    val b = readSignal(s.asInstanceOf[Signal[Boolean]])
                    if b then layout.lFlags(idx) = layout.lFlags(idx) | (1 << TuiLayout.HiddenBit)

    private def resolveDisabledIfInteractive(
        elem: Element,
        layout: TuiLayout,
        signals: TuiSignalCollector,
        idx: Int
    )(using Frame, AllowUnsafe): Unit =
        val disabled: Maybe[Boolean | Signal[Boolean]] = elem match
            case b: Button   => b.disabled
            case i: Input    => i.disabled
            case t: Textarea => t.disabled
            case s: Select   => s.disabled
            case _           => Absent
        resolveDisabled(disabled, layout, signals, idx)
    end resolveDisabledIfInteractive

    private def resolveDisabled(
        disabled: Maybe[Boolean | Signal[Boolean]],
        layout: TuiLayout,
        signals: TuiSignalCollector,
        idx: Int
    )(using Frame, AllowUnsafe): Unit =
        if !disabled.isEmpty then
            disabled.get match
                case b: Boolean =>
                    if b then layout.lFlags(idx) = layout.lFlags(idx) | (1 << TuiLayout.DisabledBit)
                case s: Signal[?] =>
                    signals.add(s)
                    val b = readSignal(s.asInstanceOf[Signal[Boolean]])
                    if b then layout.lFlags(idx) = layout.lFlags(idx) | (1 << TuiLayout.DisabledBit)

    private def elementNodeType(elem: Element): Int =
        elem match
            case _: Div          => TuiLayout.NodeDiv
            case _: AST.SpanNode => TuiLayout.NodeSpan
            case _: P            => TuiLayout.NodeP
            case _: Button       => TuiLayout.NodeButton
            case _: Input        => TuiLayout.NodeInput
            case _: Textarea     => TuiLayout.NodeTextarea
            case _: Select       => TuiLayout.NodeSelect
            case _: Option       => TuiLayout.NodeOption
            case _: Anchor       => TuiLayout.NodeAnchor
            case _: Form         => TuiLayout.NodeForm
            case _: Label        => TuiLayout.NodeLabel
            case _: H1           => TuiLayout.NodeH1
            case _: H2           => TuiLayout.NodeH2
            case _: H3           => TuiLayout.NodeH3
            case _: H4           => TuiLayout.NodeH4
            case _: H5           => TuiLayout.NodeH5
            case _: H6           => TuiLayout.NodeH6
            case _: Ul           => TuiLayout.NodeUl
            case _: Ol           => TuiLayout.NodeOl
            case _: Li           => TuiLayout.NodeLi
            case _: Table        => TuiLayout.NodeTable
            case _: Tr           => TuiLayout.NodeTr
            case _: Td           => TuiLayout.NodeTd
            case _: Th           => TuiLayout.NodeTh
            case _: Hr           => TuiLayout.NodeHr
            case _: Br           => TuiLayout.NodeBr
            case _: Pre          => TuiLayout.NodePre
            case _: Code         => TuiLayout.NodeCode
            case _: Nav          => TuiLayout.NodeNav
            case _: Header       => TuiLayout.NodeHeader
            case _: Footer       => TuiLayout.NodeFooter
            case _: Section      => TuiLayout.NodeSection
            case _: Main         => TuiLayout.NodeMain
            case _: Img          => TuiLayout.NodeImg

end TuiFlatten
