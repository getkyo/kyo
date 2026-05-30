# Phase 20c Decisions

## Return-keyword refactor strategy

The plan pseudocode used `return codeToSymbol(...)` inside a while loop, which violates the no-return convention. The refactor uses a `var result = -1` sentinel: the loop body sets `result` on match and the loop condition `len <= maxBits && result < 0` terminates early. After the loop, `if result >= 0 then result else throw`. This matches the idiomatic Kyo pattern and avoids `return` without introducing a recursive helper.

Symbol -1 is safe as a sentinel because all symbol indices are non-negative (they are positions in the input lengths array).

## RFC 1951 example test-byte construction

Lengths `[3,3,3,3,3,2,4,4]` produce canonical codes (MSB-first):
- sym 5 (F), len 2: 00
- sym 0 (A), len 3: 010
- sym 1 (B), len 3: 011
- sym 2 (C), len 3: 100
- sym 3 (D), len 3: 101
- sym 4 (E), len 3: 110
- sym 6 (G), len 4: 1110
- sym 7 (H), len 4: 1111

`codeToSymbol` array built by `fromCodeLengths`: `[5, 0, 1, 2, 3, 4, 6, 7]`.

`decodeOne` accumulates bits via `code = (code << 1) | readBit()`. The first readBit becomes the MSB after subsequent shifts. So to reproduce canonical code `00` (=0) for F, we need bits 0,0 at stream positions 0,1. For canonical code `010` (=2) for A, we need bits 0,1,0 at positions 2,3,4.

Packed into byte 0 (LSB-first): bit0=0, bit1=0, bit2=0, bit3=1, bit4=0 = 0b00001000 = 0x08.

Trace for F: len=1: code=0, count=0, 0-0<0 false; len=2: code=0, count=1, 0-1<0 true, result=codeToSymbol[0+(0-0)]=codeToSymbol[0]=5. Correct.

Trace for A: len=1: code=0, count=0, false; len=2: code=1, count=1, 1-1<0 false, index=1, first=2; len=3: code=2, count=5, 2-5<2 true, result=codeToSymbol[1+(2-2)]=codeToSymbol[1]=0. Correct.

## Invalid code test construction

Tree from `[2, 2]`: `codeToSymbol=[0,1]`, `bitLengthCounts=[0,0,2]`, maxBits=2. Stream byte 0x03 = bits 1,1. Trace: len=1: code=1, count=0, 1-0<0 false; len=2: code=3, count=2, 3-2<0 false (1>=0); len=3>maxBits=2, loop exits with result=-1, throws InflateException.

## Deviations from plan

- Visibility changed from `private[scala2]` to `private[kyo]` as directed.
- `decodeOne` refactored to remove `return` using sentinel pattern.
- Test catch branch uses `succeed` (not `()`) to satisfy the `Assertion` return type required by the `run` wrapper.
- Test uses `try/catch/end try` pattern consistent with VarintTest.scala conventions.
