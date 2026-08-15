package kyobench

import kyo.kernel.proto.*

object ForCompDeep25:

    def step(i: Int): Int < Any = i + 1

    def flow(a0: Int): Int < Any =
        step(a0).map(a1 => step(a1).map(a2 => step(a2).map(a3 => step(a3).map(a4 => step(a4).map(a5 => step(a5).map(a6 => step(a6).map(a7 => step(a7).map(a8 => step(a8).map(a9 => step(a9).map(a10 => step(a10).map(a11 => step(a11).map(a12 => step(a12).map(a13 => step(a13).map(a14 => step(a14).map(a15 => step(a15).map(a16 => step(a16).map(a17 => step(a17).map(a18 => step(a18).map(a19 => step(a19).map(a20 => step(a20).map(a21 => step(a21).map(a22 => step(a22).map(a23 => step(a23).map(a24 => step(a24).map(a25 => a25)))))))))))))))))))))))))

end ForCompDeep25
