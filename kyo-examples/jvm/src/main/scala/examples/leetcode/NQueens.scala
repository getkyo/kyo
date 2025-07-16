package examples.leetcode

import kyo.*
import scala.math.abs

opaque type Board = Vector[Int]
object Board:
    val empty: Board = Vector.empty

    extension (b: Board)
        def safe(row: Int, col: Int): Boolean =
            (0 until row).forall: i =>
                b(i) != col && abs(i - row) != abs(b(i) - col)

        def queens(n: Int, row: Int = 0): Board < Choice =
            if row == n then b
            else
                Choice.evalWith(0 until n): col =>
                    Choice.dropIf(!b.safe(row, col)).andThen:
                        (b :+ col).queens(n, row + 1)

        def show: String =
            val n   = b.length
            val top = "╭" + ("─┬" * (n - 1)) + "─╮"
            val bot = "╰" + ("─┴" * (n - 1)) + "─╯"
            val rows = (0 until n).map { r =>
                b.indices.map(c => if b(r) == c then "♛" else "·").mkString("│", "│", "│")
            }
            (top +: rows :+ bot).mkString("\n")
        end show
    end extension
end Board

@main def main =
    Choice.runStream(Board.empty.queens(8))
        .tap(board => println(board.show))
        .fold(0)((count, _) => count + 1)
        .map(count => println(s"Total solutions: $count"))
        .eval
end main
