# URGENT STEERING

DO NOT add timeouts to kqueueWait. The blocking wait is BY DESIGN. Kyo's scheduler compensates.

The kqueueRegister return check fix (check if registration failed and complete promise immediately) was the correct approach. Keep that.

Run the full Native test suite now and report. If bidirectional concurrent exchange still fails, accept it as a known limitation and move on.

DO NOT modify kyo_tcp.c or PosixBindings.scala.
