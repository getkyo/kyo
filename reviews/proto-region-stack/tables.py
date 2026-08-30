#!/usr/bin/env python3
"""Compile the proto-against-kernel time and allocation tables from one JMH log.

Both classes must come from the same session, so the two columns share forks, host conditions and
JIT state. The log is the whole `Jmh/run ... -prof gc .*Proto.*Bench.*` output.

  tables.py <jmh-log>

Status is assigned from the ratio, except that a time row whose delta is inside the combined error
is called parity whatever the ratio says, and a row where both sides are under 1 B/op or 1 us/op is
called out as sitting at the harness floor rather than given a ratio.
"""

import collections
import re
import sys

KERNEL = "kernel.bench.ProtoKernelBench"


def parse(path):
    time, alloc = collections.defaultdict(dict), collections.defaultdict(dict)
    for line in open(path, encoding="utf-8"):
        m = re.search(r"^\[info\] k\.(\S+?)\.(\w+)\s+avgt\s+\d+\s+([\d.]+)\s+±\s+([\d.]+)", line)
        if m and ":" not in m.group(2):
            side = "kernel" if m.group(1) == KERNEL else "proto"
            time[m.group(2)][side] = (float(m.group(3)), float(m.group(4)))
        m = re.search(r"^\[info\] k\.(\S+?)\.(\w+):gc\.alloc\.rate\.norm\s+avgt\s+\d+\s+([\d.]+)", line)
        if m:
            side = "kernel" if m.group(1) == KERNEL else "proto"
            alloc[m.group(2)][side] = float(m.group(3))
    return time, alloc


def status(kv, pv, ke=0.0, pe=0.0, floor=0.0, slower="slower", more="less"):
    if kv < floor and pv < floor:
        return "⚪", "both at the harness floor"
    if kv == 0:
        return "🔴", "+%.3f" % pv
    ratio = pv / kv
    if ke or pe:
        if abs(pv - kv) <= (ke + pe):
            return "⚪", "parity (inside error)"
    if ratio <= 0.90:
        return "🟢", ("%.2fx faster" % (1 / ratio)) if slower == "slower" else ("%.0f%% less" % ((1 - ratio) * 100))
    if ratio < 1.10:
        return "⚪", "parity"
    if ratio < 2.0:
        return "🟡", "%.2fx %s" % (ratio, slower)
    return "🔴", "%.2fx %s" % (ratio, slower)


def table(title, unit, data, fmt, floor, slower):
    rows = []
    for name, sides in data.items():
        if len(sides) != 2:
            continue
        k, p = sides["kernel"], sides["proto"]
        kv, ke = k if isinstance(k, tuple) else (k, 0.0)
        pv, pe = p if isinstance(p, tuple) else (p, 0.0)
        icon, verdict = status(kv, pv, ke, pe, floor, slower)
        rows.append((pv / kv if kv else 9e9, name, kv, ke, pv, pe, icon, verdict))
    rows.sort()
    print("\n## %s\n" % title)
    print("| | row | kyo.kernel %s | kyo.proto.kernel %s | |" % (unit, unit))
    print("|---|---|---|---|---|")
    for _, name, kv, ke, pv, pe, icon, verdict in rows:
        print("| %s | `%s` | %s | %s | %s |" % (icon, name, fmt(kv, ke), fmt(pv, pe), verdict))
    counts = collections.Counter(r[6] for r in rows)
    print("\n🟢 %d · ⚪ %d · 🟡 %d · 🔴 %d  (%d paired)" %
          (counts["🟢"], counts["⚪"], counts["🟡"], counts["🔴"], len(rows)))


if __name__ == "__main__":
    time, alloc = parse(sys.argv[1])
    table("Time", "us/op", time, lambda v, e: "%.3f ± %.3f" % (v, e), 0.02, "slower")
    table("Allocation", "B/op", alloc, lambda v, e: "%.3f" % v if v < 1000 else "{:,.0f}".format(v), 1.0, "more")
