package kyo.proto

/** The kernel's `Loop` outcomes, re-typed as this package's currency: `kyo.Loop.continue` returns the kernel's `<`, and a bare
  * `Continue` is valid currency here, so the constructors are the kernel's behind a representation cast. `done` payloads are bare in
  * the kernel and stay bare.
  */
object Loop:

    export kyo.Loop.{Outcome, Outcome2, Continue, Continue2, done}

    inline def continue[A, O, S](inline v: A): Outcome[A, O] < Any =
        kyo.Loop.continue[A, O, S](v).asInstanceOf[Outcome[A, O] < Any]

    inline def continue[A, B, O](inline v1: A, inline v2: B): Outcome2[A, B, O] < Any =
        kyo.Loop.continue[A, B, O](v1, v2).asInstanceOf[Outcome2[A, B, O] < Any]

end Loop
