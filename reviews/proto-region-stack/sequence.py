#!/usr/bin/env python3
"""The live review's edit sequence, built rather than described.

`sequence.json` holds one entry per edit: the file, the exact text replaced, the exact text that
replaces it, and the sentence said as it goes in. Applying them in order to the baseline
reproduces the shipped files byte for byte, which `--verify` checks by digest.

The point of it existing as data: an earlier version of this package described ten edits in prose
and never built them, so the walk could not be performed from it, and applying reached for a bulk
replace instead. A described sequence is a claim; this is the thing itself.

  sequence.py --verify        apply every edit to the baseline and compare digests
  sequence.py --list          one line per edit
  sequence.py --show 5        the sentence, and the exact old and new text of edit 5
"""

import hashlib
import json
import os
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
SPEC = json.load(open(os.path.join(HERE, "sequence.json"), encoding="utf-8"))


def baseline(path):
    return subprocess.check_output(
        ["git", "show", "%s:%s" % (SPEC["base"], path)],
        cwd=os.path.join(HERE, "..", ".."),
    ).decode("utf-8")


def apply_all():
    files = {}
    for edit in SPEC["edits"]:
        path = edit["file"]
        if edit["kind"] == "create":
            files[path] = edit["new"]
            continue
        if path not in files:
            files[path] = baseline(path)
        count = files[path].count(edit["old"])
        if count != 1:
            raise SystemExit(
                "edit %d: its old text occurs %d times in %s, and an edit must match exactly once"
                % (edit["id"], count, path)
            )
        files[path] = files[path].replace(edit["old"], edit["new"])
    return files


def verify():
    files = apply_all()
    bad = 0
    for path, want in sorted(SPEC["digests"].items()):
        got = hashlib.sha256(files[path].encode("utf-8")).hexdigest()
        if got == want:
            print("OK     %s" % path)
        else:
            print("STALE  %s: the sequence produces a different file than the tip" % path)
            bad = 1
    if not bad:
        print("OK     %d edits reproduce all %d files from %s" % (len(SPEC["edits"]), len(SPEC["digests"]), SPEC["base"]))
    return bad


def listing():
    for edit in SPEC["edits"]:
        old = 0 if edit["kind"] == "create" else len(edit["old"].split("\n"))
        print("%2d  %-60s %-15s %4d -> %4d" % (
            edit["id"], edit["label"], os.path.basename(edit["file"]), old, len(edit["new"].split("\n"))))


def show(which):
    for edit in SPEC["edits"]:
        if edit["id"] != which:
            continue
        print("edit %d: %s" % (edit["id"], edit["label"]))
        print("file: %s" % edit["file"])
        print("say:  %s" % edit["sentence"])
        if edit["kind"] == "create":
            print("\n--- the whole file ---\n%s" % edit["new"])
        else:
            print("\n--- replaced ---\n%s\n\n--- with ---\n%s" % (edit["old"], edit["new"]))
        return 0
    raise SystemExit("no edit %d" % which)


if __name__ == "__main__":
    args = sys.argv[1:]
    if args[:1] == ["--verify"]:
        sys.exit(verify())
    elif args[:1] == ["--list"]:
        listing()
    elif args[:1] == ["--show"]:
        sys.exit(show(int(args[1])))
    else:
        raise SystemExit(__doc__)
