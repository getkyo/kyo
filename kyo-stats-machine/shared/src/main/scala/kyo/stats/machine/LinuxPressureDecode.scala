package kyo.stats.machine

import kyo.*

/** Total decode of a PSI file's `some`/`full` lines into two PsiReadings (avg10/60/300 percent, total ns). */
private[machine] object LinuxPressureDecode:

    def parse(bytes: Span[Byte], len: Int): Maybe[(Machine.PsiReading, Machine.PsiReading)] =
        val text = Text.fromSpan(bytes, len)
        val some = line(text, "some")
        val full = line(text, "full")
        if some.isEmpty && full.isEmpty then Absent
        else Present((some.getOrElse(Machine.PsiReading.empty), full.getOrElse(Machine.PsiReading.empty)))
    end parse

    private def line(text: Text, prefix: String): Maybe[Machine.PsiReading] =
        text.lineFields(prefix + " ").map { fields =>
            Machine.PsiReading(
                avg10 = text.psiField(fields, "avg10=").flatMap(d => d.toDoubleOption.fold(Absent)(Present(_))),
                avg60 = text.psiField(fields, "avg60=").flatMap(d => d.toDoubleOption.fold(Absent)(Present(_))),
                avg300 = text.psiField(fields, "avg300=").flatMap(d => d.toDoubleOption.fold(Absent)(Present(_))),
                total = text.psiField(fields, "total=").flatMap(t => t.toLongOption.map(_ * 1000L).fold(Absent)(Present(_)))
            )
        }

end LinuxPressureDecode
