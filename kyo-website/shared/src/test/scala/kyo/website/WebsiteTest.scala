package kyo.website

import kyo.*

abstract class WebsiteTest extends kyo.test.Test[Any]:

    /** The id of the HTML reactive range enclosing the first occurrence of `anchor`, or `Absent` if the anchor is missing or sits outside
      * any range.
      *
      * kyo-ui renders an HTML reactive region as a logical comment range, `<!--kyo-rs:ID-->body<!--kyo-re:ID-->`, instead of a wrapper
      * element, because a wrapper cannot sit inside parents such as `<table>` or `<ul>`. A test cannot look for the SVG boundary attribute
      * `data-kyo-reactive` to detect a reactive HTML region: `UI.baseCss` carries a rule naming that attribute, so every full page contains
      * the string whether or not a region is present.
      */
    protected def enclosingReactiveRange(html: String, anchor: String): Maybe[String] =
        val startMarker = "<!--kyo-rs:"
        val at          = html.indexOf(anchor)
        if at < 0 then Absent
        else
            val open = html.lastIndexOf(startMarker, at)
            if open < 0 then Absent
            else
                val idEnd = html.indexOf("-->", open)
                if idEnd < 0 then Absent
                else
                    val id = html.substring(open + startMarker.length, idEnd)
                    if html.indexOf(s"<!--kyo-re:$id-->", at) > at then Present(id) else Absent
                end if
            end if
        end if
    end enclosingReactiveRange

end WebsiteTest
