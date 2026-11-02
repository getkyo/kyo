package app

import kyo.*

object Main:
    def main(args: Array[String]): Unit =
        val result: Int = Var.run(1)(Counter.bump(41)).eval
        if result != 42 then throw new AssertionError(s"expected 42, got $result")
        java.nio.file.Files.writeString(java.nio.file.Path.of("target", "result.txt"), result.toString)
end Main
