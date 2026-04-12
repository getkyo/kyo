package kyo

import scala.scalajs.js
import scala.scalajs.js.annotation.*

@JSExportTopLevel("Text")
class JsText(@JSName("$text") val underlying: Text) extends js.Object:
    import kyo.JsFacadeGivens.given
    def charAt(index: Int) =
        Text.charAt(underlying)(index)

    def compact() =
        new JsText(Text.compact(underlying))

    def compareToIgnoreCase(other: JsText) =
        Text.compareToIgnoreCase(underlying)(other.underlying)

    def contains(substr: JsText) =
        Text.contains(underlying)(substr.underlying)

    def count(p: Text.Predicate) =
        Text.count(underlying)(p)

    def drop(n: Int) =
        new JsText(Text.drop(underlying)(n))

    def dropRight(n: Int) =
        new JsText(Text.dropRight(underlying)(n))

    def dropUntilNext(p: Text.Predicate) =
        new JsText(Text.dropUntilNext(underlying)(p))

    def dropWhile(p: Text.Predicate) =
        new JsText(Text.dropWhile(underlying)(p))

    def endsWith(suffix: JsText) =
        Text.endsWith(underlying)(suffix.underlying)

    def exists(p: Text.Predicate) =
        Text.exists(underlying)(p)

    def filter(p: Text.Predicate) =
        new JsText(Text.filter(underlying)(p))

    def filterNot(p: Text.Predicate) =
        new JsText(Text.filterNot(underlying)(p))

    def forall(p: Text.Predicate) =
        Text.forall(underlying)(p)

    def head() =
        new JsMaybe(Text.head(underlying))

    def indexOf(substr: JsText) =
        Text.indexOf(underlying)(substr.underlying)

    def indexWhere(p: Text.Predicate) =
        Text.indexWhere(underlying)(p)

    def is(other: JsText) =
        Text.is(underlying)(other.underlying)

    def isEmpty() =
        Text.isEmpty(underlying)

    def lastIndexOf(substr: JsText) =
        Text.lastIndexOf(underlying)(substr.underlying)

    def lastIndexWhere(p: Text.Predicate) =
        Text.lastIndexWhere(underlying)(p)

    def length() =
        Text.length(underlying)

    def plus(other: JsText) =
        new JsText(Text.`+`(underlying)(other.underlying))

    def reverse() =
        new JsText(Text.reverse(underlying))

    def show() =
        Text.show(underlying)

    def size() =
        Text.size(underlying)

    def span(p: Text.Predicate) =
        Text.span(underlying)(p)

    def split(separator: Char) =
        new JsChunk(Text.split(underlying)(separator))

    def startsWith(prefix: JsText) =
        Text.startsWith(underlying)(prefix.underlying)

    def stripPrefix(prefix: JsText) =
        new JsText(Text.stripPrefix(underlying)(prefix.underlying))

    def stripSuffix(suffix: JsText) =
        new JsText(Text.stripSuffix(underlying)(suffix.underlying))

    def substring(from: Int, until: Int) =
        new JsText(Text.substring(underlying)(from, until))

    def tail() =
        new JsText(Text.tail(underlying))

    def take(n: Int) =
        new JsText(Text.take(underlying)(n))

    def takeRight(n: Int) =
        new JsText(Text.takeRight(underlying)(n))

    def takeUntilNext(p: Text.Predicate) =
        new JsText(Text.takeUntilNext(underlying)(p))

    def takeWhile(p: Text.Predicate) =
        new JsText(Text.takeWhile(underlying)(p))

    def toChunk() =
        new JsChunk(Text.toChunk(underlying))

    def trim() =
        new JsText(Text.trim(underlying))


end JsText

object JsText:
    import kyo.JsFacadeGivens.given

    @JSExportStatic
    def empty() =
        new JsText(Text.empty)


end JsText