package kyo.internal

import kyo.*
import scala.quoted.*

/** Macro implementations backing the interpolators in [[EventInterpolators]]. Kept in a
  * SEPARATE file: a Scala 3 macro cannot be called from the same source file that defines it.
  * Each `*Impl` validates the literal text at MACRO-EXPANSION time (compile time) by calling
  * the SAME plain predicate the paired runtime constructor uses (`Event.Metadata.Key.isValid`,
  * `Event.StreamId.isValid`, `Event.StreamName.isValid`, `Event.Type.isValid`,
  * `Event.Id.isValid`, `JournalId.validate`), reports a compile error on an invalid literal or
  * a non-empty `args` (an interpolated/dynamic part), and otherwise embeds the validated
  * literal directly via each type's `fromUnchecked` coercion: no runtime re-validation, no
  * `Frame`, no `Abort`.
  */
private[kyo] object EventInterpolatorMacros:

    private def literalOf(sc: Expr[StringContext], args: Expr[Seq[Any]], macroName: String, ctorName: String)(using
        Quotes
    ): String =
        args match
            case Varargs(argExprs) if argExprs.nonEmpty =>
                quotes.reflect.report.errorAndAbort(
                    s"$macroName\"...\" does not accept interpolated arguments; use the runtime constructor $ctorName(value) for a dynamic value"
                )
            case _ =>
                sc.valueOrAbort.parts.head
    end literalOf

    def keyImpl(sc: Expr[StringContext], args: Expr[Seq[Any]])(using Quotes): Expr[Event.Metadata.Key] =
        val literal = literalOf(sc, args, "key", "Event.Metadata.Key")
        if Event.Metadata.Key.isValid(literal) then '{ Event.Metadata.Key.fromUnchecked(${ Expr(literal) }) }
        else quotes.reflect.report.errorAndAbort(s"invalid Event.Metadata.Key literal: \"$literal\"")
    end keyImpl

    def streamIdImpl(sc: Expr[StringContext], args: Expr[Seq[Any]])(using Quotes): Expr[Event.StreamId] =
        val literal = literalOf(sc, args, "streamId", "Event.StreamId")
        if Event.StreamId.isValid(literal) then '{ Event.StreamId.fromUnchecked(${ Expr(literal) }) }
        else quotes.reflect.report.errorAndAbort(s"invalid Event.StreamId literal: \"$literal\"")
    end streamIdImpl

    def streamNameImpl(sc: Expr[StringContext], args: Expr[Seq[Any]])(using Quotes): Expr[Event.StreamName] =
        val literal = literalOf(sc, args, "streamName", "Event.StreamName")
        if Event.StreamName.isValid(literal) then '{ Event.StreamName.fromUnchecked(${ Expr(literal) }) }
        else quotes.reflect.report.errorAndAbort(s"invalid Event.StreamName literal: \"$literal\"")
    end streamNameImpl

    def eventTypeImpl(sc: Expr[StringContext], args: Expr[Seq[Any]])(using Quotes): Expr[Event.Type] =
        val literal = literalOf(sc, args, "eventType", "Event.Type")
        if Event.Type.isValid(literal) then '{ Event.Type.fromUnchecked(${ Expr(literal) }) }
        else quotes.reflect.report.errorAndAbort(s"invalid Event.Type literal: \"$literal\"")
    end eventTypeImpl

    def eventIdImpl(sc: Expr[StringContext], args: Expr[Seq[Any]])(using Quotes): Expr[Event.Id] =
        val literal = literalOf(sc, args, "eventId", "Event.Id")
        if Event.Id.isValid(literal) then '{ Event.Id.fromUnchecked(${ Expr(literal) }) }
        else quotes.reflect.report.errorAndAbort(s"invalid Event.Id literal: \"$literal\"")
    end eventIdImpl

    def journalIdImpl(sc: Expr[StringContext], args: Expr[Seq[Any]])(using Quotes): Expr[JournalId] =
        val literal = literalOf(sc, args, "journalId", "JournalId")
        JournalId.validate(literal)(using Frame.internal) match
            case Result.Success(_) => '{ JournalId.fromUnchecked(${ Expr(literal) }) }
            case Result.Failure(e) => quotes.reflect.report.errorAndAbort(s"invalid JournalId literal: \"$literal\" (${e.getMessage()})")
            case Result.Panic(e)   => throw e
        end match
    end journalIdImpl

end EventInterpolatorMacros
