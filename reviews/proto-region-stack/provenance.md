# Commits this package cites on purpose

`package-check.sh` reports any commit named in the package that is inside the range but is not the
tip, because that is how three rounds of numbers came to be dated to a tree that had already been
superseded. Sometimes such a citation is the point. Those are declared here, one per line with the
reason, so the exemption is a written justification rather than a silence.

- `a10624dfa4` : the bisect result. The whole of the suspension regression is introduced by this
  commit, the first of the fifteen, and every commit after it is neutral on those rows. Naming it is
  the finding; `evidence.md` cites it as the commit whose code was measured, not as the tip.

- `eabef556e0` : outside the range, an ancestor of the base. It is where the ported test corpus
  landed, and it is named to say how far back the control's suite has been red by construction.

## Benchmark classes named but not measuring the proto

- `ProtoKernelBench` : named throughout this package precisely to say that it measures `kyo.kernel`
  and that four rounds of evidence came from it wrongly. The check that reports such a class is the
  repair for that escape, so the class has to stay sayable; declaring it here is the difference
  between naming a finding and quietly carrying one.
