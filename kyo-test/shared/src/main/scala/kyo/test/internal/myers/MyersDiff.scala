package kyo.test.internal.myers

import kyo.*
import kyo.Ansi.*
import kyo.test.ConsoleUtils

sealed trait Action[A]

object Action:

    final case class Delete[A](s: A) extends Action[A]:
        override def toString: String = s"-$s"

    final case class Insert[A](s: A) extends Action[A]:
        override def toString: String = s"+$s"

    final case class Keep[A](s: A) extends Action[A]:
        override def toString: String = s.toString
end Action

final case class DiffResult[A](actions: Chunk[Action[A]]):
    def applyChanges(original: String): String =
        actions
            .foldLeft((new StringBuilder(original), 0)) { case ((s, index), action) =>
                action match
                    case Action.Delete(_) => (s.deleteCharAt(index), index)
                    case Action.Keep(_)   => (s, index + 1)
                    case Action.Insert(i) => (s.insert(index, i), index + 1)
            }
            ._1
            .result()

    def invert: DiffResult[A] =
        DiffResult(
            actions.map {
                case Action.Delete(s)  => Action.Insert(s)
                case Action.Insert(s)  => Action.Delete(s)
                case k: Action.Keep[?] => k
            }
        )

    def isIdentical: Boolean = actions.forall {
        case Action.Keep(_) => true
        case _              => false
    }

    def renderLine: String =
        actions.map {
            case Action.Delete(s) => s"-${s.toString.underline}".red
            case Action.Insert(s) => s"+${s.toString.underline}".green
            case Action.Keep(s)   => s"$s".grey
        }
            .mkString("")

    override def toString: String =
        actions.map {
            case Action.Delete(s) => s"-$s".red
            case Action.Insert(s) => s"+$s".green
            case Action.Keep(s)   => s" $s".grey
        }
            .mkString("\n")
end DiffResult

object MyersDiff:
    def diffWords(original: String, modified: String): DiffResult[String] =
        diff(
            Chunk.Indexed.from(original.split("\\b")),
            Chunk.Indexed.from(modified.split("\\b"))
        )

    def diffChars(original: String, modified: String): DiffResult[String] =
        diff(
            Chunk.Indexed.from(original.toList.map(_.toString)),
            Chunk.Indexed.from(modified.toList.map(_.toString))
        )

    def diff(original: String, modified: String): DiffResult[String] =
        diff(
            Chunk.Indexed.from(original.split("\n")),
            Chunk.Indexed.from(modified.split("\n"))
        )

    def diff[A](original: Chunk[A], modified: Chunk[A]): DiffResult[A] =

        var varOriginal = original
        var varModified = modified
        var longestCommonSubstring: Chunk[A] =
            getLongestCommonSubsequence(original, modified)

        var actions: Chunk[Action[A]] = Chunk.empty

        while longestCommonSubstring.nonEmpty do
            val headOfLongestCommonSubstring = longestCommonSubstring(0)
            longestCommonSubstring = longestCommonSubstring.drop(1)

            var headOfOriginal = varOriginal(0)
            var loop           = true

            while loop do
                headOfOriginal = varOriginal(0)
                varOriginal = varOriginal.drop(1)
                if headOfOriginal != headOfLongestCommonSubstring then
                    actions = actions :+ Action.Delete(headOfOriginal)

                loop = varOriginal.nonEmpty && headOfOriginal != headOfLongestCommonSubstring
            end while

            var headOfModified = varModified(0)
            loop = true

            while loop do
                headOfModified = varModified(0)
                varModified = varModified.drop(1)
                if headOfModified != headOfLongestCommonSubstring then
                    actions = actions :+ Action.Insert(headOfModified)

                loop = varModified.nonEmpty && headOfModified != headOfLongestCommonSubstring
            end while

            actions = actions :+ Action.Keep(headOfLongestCommonSubstring)
        end while

        while varOriginal.nonEmpty do
            val headOfOriginal = varOriginal(0)
            varOriginal = varOriginal.drop(1)
            actions = actions :+ Action.Delete(headOfOriginal)
        end while

        while varModified.nonEmpty do
            val headOfModified = varModified(0)
            varModified = varModified.drop(1)
            actions = actions :+ Action.Insert(headOfModified)
        end while

        DiffResult(actions)
    end diff

    def getLongestCommonSubsequence[A](original: Chunk[A], modified: Chunk[A]): Chunk[A] =
        if original == null || original.isEmpty || modified == null || modified.isEmpty then Chunk.empty
        else if original == modified then original
        else

            val myersMatrix: Array[Array[Int]] = initializeMyersMatrix(original, modified)
            val longestCommonSubsequence       = ChunkBuilder.init[A]

            var originalPosition = original.length
            var modifiedPosition = modified.length

            var loop = true

            while loop do
                if myersMatrix(originalPosition)(modifiedPosition) == myersMatrix(originalPosition - 1)(modifiedPosition) then
                    originalPosition -= 1
                else if
                    myersMatrix(originalPosition)(modifiedPosition) == myersMatrix(originalPosition)(modifiedPosition - 1)
                then
                    modifiedPosition -= 1
                else
                    longestCommonSubsequence += original(originalPosition - 1)
                    originalPosition -= 1
                    modifiedPosition -= 1
                end if

                loop = originalPosition > 0 && modifiedPosition > 0
            end while

            longestCommonSubsequence.result().reverse

    private def initializeMyersMatrix[A](original: Chunk[A], modified: Chunk[A]): Array[Array[Int]] =
        val originalLength = original.length
        val modifiedLength = modified.length

        val myersMatrix = Array.fill[Int](originalLength + 1, modifiedLength + 1)(0)

        for i <- 0 until originalLength do
            for j <- 0 until modifiedLength do
                if original(i) == modified(j) then
                    myersMatrix(i + 1)(j + 1) = myersMatrix(i)(j) + 1
                else
                    if myersMatrix(i)(j + 1) >= myersMatrix(i + 1)(j) then
                        myersMatrix(i + 1)(j + 1) = myersMatrix(i)(j + 1)
                    else
                        myersMatrix(i + 1)(j + 1) = myersMatrix(i + 1)(j)
        end for

        myersMatrix
    end initializeMyersMatrix
end MyersDiff
