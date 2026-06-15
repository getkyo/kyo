package kyo.test.internal

import kyo.Chunk
import kyo.Maybe
import kyo.Render

/** Structural diff renderer for assertEquals and similar.
  *
  * For Products (case classes), shows field-aligned diff with `~~~~~` underlines marking the mismatched fields. For Iterables, shows
  * element-aligned diff. For multi-line strings, shows unified-diff style. Otherwise falls back to plain `actual / expected`.
  */
object Diff:

    private def renderValue(v: Any): String =
        v.asInstanceOf[AnyRef] match
            case s: String =>
                // Quote strings; bound + flatten so a huge or multi-line value cannot break the diagram.
                "\"" + Recorder.render(s) + "\""
            case other => Recorder.render(other) // null renders as "null" via String.valueOf
    end renderValue

    private def valuesEqual(a: Any, b: Any): Boolean =
        // Unsafe: widening Any to AnyRef for reference equality / equals dispatch; safe for all A
        val ar = a.asInstanceOf[AnyRef]
        // Unsafe: widening Any to AnyRef for reference equality / equals dispatch; safe for all A
        val br = b.asInstanceOf[AnyRef]
        if (ar eq null) && (br eq null) then true
        else if (ar eq null) || (br eq null) then false
        else ar.equals(br)
    end valuesEqual

    /** Top-level entry point; dispatches based on value types.
      *
      * The `Render[A]` instance is used for the plain-value fallback path and is available to callers (e.g. `assertEquals`) that supply a
      * specific instance. The low-priority `Render[Any]` fallback in `LowPriorityRenders` ensures callers without an explicit instance
      * still compile.
      */
    def render[A](actual: A, expected: A)(using r: Render[A]): String =
        val rendered =
            (actual, expected) match
                case (a: Product, e: Product) if a.productPrefix == e.productPrefix =>
                    caseClassDiff(a, e)
                case (a: Iterable[?], e: Iterable[?]) =>
                    collectionDiff(a, e)
                case (a: String, e: String) if a.contains('\n') || e.contains('\n') =>
                    stringDiff(a, e)
                case _ =>
                    s"  actual:   ${Recorder.render(r.asString(actual))}\n  expected: ${Recorder.render(r.asString(expected))}"
        // Total bound: covers the intentionally multi-line stringDiff and large field/element values.
        Recorder.boundedString(rendered, Recorder.MaxDiagram)
    end render

    /** Field-aligned diff for case classes (Products). Underlines fields whose values differ. */
    def caseClassDiff(actual: Product, expected: Product): String =
        val prefix  = actual.productPrefix
        val fields  = actual.productElementNames.toList
        val actVals = actual.productIterator.toList
        val expVals = expected.productIterator.toList

        if fields.isEmpty then
            s"  actual:   ${renderValue(actual)}\n  expected: ${renderValue(expected)}"
        else
            // Build rendered value pairs: (fieldName, renderedActual, renderedExpected, differs)
            val renderedPairs = fields.zip(actVals.zip(expVals)).map {
                case (field, (av, ev)) =>
                    val differs = !valuesEqual(av, ev)
                    (field, renderValue(av), renderValue(ev), differs)
            }

            def formatRow(pairs: List[(String, String, String, Boolean)], useExpected: Boolean): String =
                val parts = pairs.map {
                    case (field, av, ev, _) =>
                        val v = if useExpected then ev else av
                        s"$field = $v"
                }
                s"$prefix(${parts.mkString(", ")})"
            end formatRow

            val actualLine   = "  actual:   " + formatRow(renderedPairs, useExpected = false)
            val expectedLine = "  expected: " + formatRow(renderedPairs, useExpected = true)

            val headerPad = "  expected: " + prefix + "("
            val underline = buildFieldUnderline(renderedPairs, headerPad)

            if underline.trim.isEmpty then
                s"$actualLine\n$expectedLine"
            else
                s"$actualLine\n$expectedLine\n$underline"
            end if
        end if
    end caseClassDiff

    private def buildFieldUnderline(
        pairs: List[(String, String, String, Boolean)],
        headerPad: String
    ): String =
        val buf = new StringBuilder()
        buf.append(" " * headerPad.length)
        var first = true
        pairs.foreach {
            case (field, av, ev, differs) =>
                val segment = s"$field = $ev"
                if first then first = false
                else buf.append("  ") // for the ", " separator
                if differs then buf.append("~" * segment.length)
                else buf.append(" " * segment.length)
        }
        buf.toString()
    end buildFieldUnderline

    /** Element-aligned diff for Iterables. */
    def collectionDiff(actual: Iterable[?], expected: Iterable[?]): String =
        val actList = Chunk.from(actual)
        val expList = Chunk.from(expected)
        val maxLen  = math.max(actList.length, expList.length)

        val diffPositions = (0 until maxLen).filter { i =>
            // Unsafe: null sentinel cast to Any for absent collection positions; valuesEqual handles null safely
            val a: Any = if i < actList.length then actList(i) else null.asInstanceOf[Any]
            // Unsafe: null sentinel cast to Any for absent collection positions; valuesEqual handles null safely
            val e: Any = if i < expList.length then expList(i) else null.asInstanceOf[Any]
            !valuesEqual(a, e)
        }.toSet

        def renderList(items: Chunk[?]): String =
            val rendered = (0 until maxLen).map { i =>
                if i < items.length then renderValue(items(i))
                else "<missing>"
            }
            "Chunk(" + rendered.mkString(", ") + ")"
        end renderList

        val actualStr   = renderList(actList)
        val expectedStr = renderList(expList)

        val prefix       = "  actual:   Chunk("
        val underlineBuf = new StringBuilder(" " * prefix.length)

        val actRendered = (0 until maxLen).map { i =>
            if i < actList.length then renderValue(actList(i)) else "<missing>"
        }.toList

        actRendered.zipWithIndex.foreach { case (r, idx) =>
            if idx > 0 then underlineBuf.append("  ") // ", " separator
            if diffPositions.contains(idx) then underlineBuf.append("~" * r.length)
            else underlineBuf.append(" " * r.length)
        }

        val underline = underlineBuf.toString().stripTrailing()
        val actLine   = s"  actual:   $actualStr"
        val expLine   = s"  expected: $expectedStr"

        if underline.trim.isEmpty then s"$actLine\n$expLine"
        else s"$actLine\n$expLine\n$underline"
    end collectionDiff

    /** Unified-diff style for multi-line strings. */
    def stringDiff(actual: String, expected: String): String =
        val actLines = Chunk.from(actual.linesIterator)
        val expLines = Chunk.from(expected.linesIterator)
        val maxLen   = math.max(actLines.length, expLines.length)

        val buf = new StringBuilder()
        buf.append("  actual vs expected (line diff):\n")

        (0 until maxLen).foreach { i =>
            val aIn = i < actLines.length
            val eIn = i < expLines.length
            if aIn && eIn then
                val al = actLines(i)
                val el = expLines(i)
                if al == el then buf.append(s"    $al\n")
                else
                    buf.append(s"  - $al\n")
                    buf.append(s"  + $el\n")
                end if
            else if aIn then buf.append(s"  - ${actLines(i)}\n")
            else if eIn then buf.append(s"  + ${expLines(i)}\n")
            end if
        }

        buf.toString().stripTrailing()
    end stringDiff

end Diff
