package kyo.proto.kernel.internal

import org.scalatest.freespec.AnyFreeSpec

class DebuggerBytecodeTest extends AnyFreeSpec:

    "the gate leaves no inline accessor in Debugger's bytecode" in {
        val classes   = List(Class.forName("kyo.proto.kernel.internal.Debugger$"), Class.forName("kyo.proto.kernel.internal.Debugger"))
        val accessors = classes.flatMap(_.getDeclaredMethods.toList.map(_.getName)).filter(_.startsWith("inline$"))
        assert(accessors.isEmpty, accessors.mkString(", "))
    }

end DebuggerBytecodeTest
