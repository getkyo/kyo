package kyo.test.runner.internal

/** Hang-diagnostics thread dump. On the JVM this renders every live thread's name, state, and stack via
  * `Thread.getAllStackTraces`, so a STUCK leaf's dump shows exactly where each thread is parked.
  */
private[runner] object ThreadDump:
    def render(): String =
        val sb = new StringBuilder
        Thread.getAllStackTraces.forEach { (t, st) =>
            sb.append("\n--- " + t.getName + " (" + t.getState + ") ---\n")
            st.foreach(f => sb.append("  at " + f + "\n"))
        }
        sb.toString
    end render
end ThreadDump
