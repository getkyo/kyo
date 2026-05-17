package kyo.grpc

import org.scalactic.Equality
import org.scalamock.matchers.Matcher
import org.scalamock.matchers.MatcherBase
import scala.reflect.ClassTag

class ArgEquals[T](arg: T, clue: Option[String])(using classTag: ClassTag[T], equality: Equality[T]) extends Matcher[T]:
    override def toString: String = "argEquals[" + classTag.runtimeClass.getSimpleName + "]" + clue.map(c => s" - $c").getOrElse("")
    override def safeEquals(obj: T): Boolean = equality.areEqual(arg, obj)

def argEquals[T: {ClassTag, Equality}](clue: String)(arg: T): MatcherBase = ArgEquals(arg, Some(clue))

def argEquals[T: {ClassTag, Equality}](arg: T): MatcherBase = ArgEquals(arg, None)
