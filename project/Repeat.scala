import sbt._

object Repeat {
    private def executeCommand(command: String, state: State): (Boolean, State) = {
        var success = true
        val newState = Command.process(
            command,
            state,
            error => {
                println(s"Error executing command: $error")
                success = false
            }
        )
        (success, newState)
    }

    private case class ExecutionResult(success: Boolean, state: State, iterations: Int)

    private def executeRepeatedly(
        command: String,
        state: State,
        maxIterations: Option[Int]
    ): ExecutionResult = {
        var currentState = state
        var i            = 0
        var success      = true

        while (maxIterations.fold(true)(max => i < max) && success) {
            val iterationMsg = maxIterations match {
                case Some(max) => s"Execution ${i + 1} of $max"
                case None      => s"Execution ${i + 1}"
            }
            println(iterationMsg)
            val (commandSuccess, newState) = executeCommand(command, currentState)
            success = commandSuccess
            currentState = newState
            i += 1
        }

        ExecutionResult(success, currentState, i)
    }

    def command = Command.args("repeat", "<command> | <number-of-times> <command>") { (state, args) =>
        args match {
            case command :: Nil =>
                val result = executeRepeatedly(command, state, None)
                println(s"Command failed after ${result.iterations - 1} successful executions")
                result.state
            case count :: command :: Nil =>
                try {
                    val times = count.toInt
                    if (times <= 0) {
                        println("Number of repetitions must be positive")
                        state
                    } else {
                        val result = executeRepeatedly(command, state, Some(times))
                        if (!result.success) {
                            println(s"Command failed on iteration ${result.iterations} - stopping execution")
                        }
                        result.state
                    }
                } catch {
                    case _: NumberFormatException =>
                        println("Usage: repeat <command> | repeat <number-of-times> <command>")
                        state
                }
            case _ =>
                println("Usage: repeat <command> | repeat <number-of-times> <command>")
                state
        }
    }
}
