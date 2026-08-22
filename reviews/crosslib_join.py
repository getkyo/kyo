#!/usr/bin/env python3
"""Join JMH result JSONs from multiple boards into cross-library markdown tables.

Usage: join_boards.py LABEL=path.json [LABEL=path.json ...]

The first label is the baseline (kernel2); the second is treated as the sibling
kernel (excluded from the emoji verdict, which compares the baseline against the
external libraries only). Rows containing "Alt" are excluded (recorded
alternatives). A zioblocks cell more than 100x below every other measured cell
on its row is JIT-eliminated dead code, labelled instead of ratioed, and
excluded from the verdict.
"""
import json
import sys
from collections import OrderedDict


def load(path):
    rows = {}
    with open(path) as f:
        data = json.load(f)
    for e in data:
        name = e["benchmark"].split(".")[-1]
        if "Alt" in name:
            continue
        time = e["primaryMetric"]["score"]
        alloc = None
        sec = e.get("secondaryMetrics", {})
        if "gc.alloc.rate.norm" in sec:
            alloc = sec["gc.alloc.rate.norm"]["score"]
        rows[name] = (time, alloc)
    return rows


def fmt_time(v):
    if v >= 1000:
        return f"{v:,.0f}"
    if v >= 100:
        return f"{v:.0f}"
    if v >= 1:
        return f"{v:.2f}"
    return f"{v:.4f}"


def fmt_alloc(v):
    if v < 0.5:
        return "0"
    return f"{v:,.0f}"


def main():
    boards = OrderedDict()
    for arg in sys.argv[1:]:
        label, path = arg.split("=", 1)
        boards[label] = load(path)
    labels = list(boards.keys())
    base, sibling = labels[0], labels[1]
    external = labels[2:]

    all_rows = []
    for lb in labels:
        for name in boards[lb]:
            if name not in all_rows:
                all_rows.append(name)
    all_rows.sort()

    # zioblocks JIT-elimination detection, computed on the time metric
    eliminated = set()
    if "zioblocks" in boards:
        for name, (t, _) in boards["zioblocks"].items():
            others = [boards[lb][name][0] for lb in labels
                      if lb != "zioblocks" and name in boards[lb]]
            if others and t > 0 and min(others) / t > 100:
                eliminated.add(name)

    for metric, fmt, title in ((0, fmt_time, "Time (us/op)"), (1, fmt_alloc, "Allocation (gc.alloc.rate.norm, B/op)")):
        print(f"\n## {title}\n")
        print("| row | " + " | ".join(labels) + " |")
        print("|---|" + "---|" * len(labels))
        for name in all_rows:
            base_v = boards[base].get(name, (None, None))[metric]
            ext_vals = [boards[lb][name][metric] for lb in external
                        if name in boards[lb]
                        and boards[lb][name][metric] is not None
                        and not (lb == "zioblocks" and name in eliminated)]
            cells = []
            for lb in labels:
                entry = boards[lb].get(name)
                v = entry[metric] if entry else None
                if v is None:
                    cells.append("-")
                    continue
                if lb == "zioblocks" and name in eliminated:
                    cells.append(f"{fmt(v)} (JIT-eliminated)" if metric == 0 else fmt(v))
                    continue
                cell = fmt(v)
                ratio_ok = base_v is not None and (base_v > 0.01 if metric == 0 else base_v > 1)
                if lb == base:
                    if metric == 0 and ext_vals and base_v is not None and base_v > 0:
                        best = min(ext_vals)
                        cell = ("\U0001f534 " if best < base_v * 0.95 else "\U0001f7e2 ") + cell
                elif ratio_ok:
                    cell += f" ({v / base_v:.2f}x)"
                cells.append(cell)
            print(f"| {name} | " + " | ".join(cells) + " |")


if __name__ == "__main__":
    main()
