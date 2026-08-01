#!/usr/bin/env python3
"""ci-analyze.py - where did a kyo CI run's wall clock go?

ci-logs.sh answers "what failed". This answers "what was slow, and why". It reads
the RAW per-job logs (the REST jobs/<id>/logs endpoint keeps the per-line
timestamps ci-logs.sh strips) and reconstructs the run as a timeline: setup, the
three sbt phases, each module's test task, each suite, each test, and the
resource samples ci-monitor.sh interleaves into that same log.

The parse is anchored on markers the build itself emits, never on guesses:

    [testKyo] <phase> N projects: a, b, c    phase boundary + project list
    [testKyo] running: a/test; b/test        the ordered command list
    === SuiteName ===                        kyo-test suite start
    --- SuiteName: N passed, M failed (Xms)  kyo-test suite end + reported ms
    [info] SuiteName:                        ScalaTest suite start
    [info] - name (N milliseconds)           ScalaTest test + reported ms
        [PASS] name  (123ms)                 kyo-test test + reported ms
    [success] Total time: N s, completed T   sbt command boundary
    [ci-mon HH:MM:SS] availMB=.. load=..     ci-monitor.sh sample
    === [ci-test] HH:MM:SS attempt 1/3 ...   ci-test.sh retry / watchdog

Every view reports two numbers and the gap between them is the point:
  reported  what the test framework says the tests took
  span      what the clock says, so browser startup, container pulls, JVM forks
            and idle waiting all land in span - reported

Suites map to modules by indexing the repo's test sources, so per-module numbers
compare across jobs, platforms and runs. ci-monitor.sh samples are joined onto
every module block, so a slow module can be read against what the machine was
actually doing: load p50 near 1.0 on a 4-core runner means one core busy and
three idle, which is waiting, not compute.

Detail levels: pass -v repeatedly to widen any view (-v, -vv, -vvv).

Examples:
  ci-analyze.py timeline 30699720019                     run shape, all jobs
  ci-analyze.py timeline 30699720019 -vv --job 9136892   phases + module blocks
  ci-analyze.py modules  30699720019 --job 9136892 -v    per-module + resources
  ci-analyze.py suites   30699720019 --module kyo-ui -v  per-suite inside a module
  ci-analyze.py tests    30699720019 --job X --suite Foo per-test durations
  ci-analyze.py compare  30699720019 <jobA> <jobB> -v    status-aware A/B
  ci-analyze.py resources 30699720019 --job X -vv        the raw ci-mon series
  ci-analyze.py gaps     30699720019 --job X -v          dead time between suites
  ci-analyze.py idle     30699720019                     low-load stretches
  ci-analyze.py runs main 10                             recent runs

Env: REPO (owner/repo, default gh-detected), KYO_CI_CACHE (default
     ~/.cache/kyo-ci-analyze), KYO_REPO_ROOT (default: the repo holding this file).
"""

import argparse
import datetime as dt
import json
import os
import re
import subprocess
from collections import Counter, defaultdict

CACHE = os.environ.get('KYO_CI_CACHE', os.path.expanduser('~/.cache/kyo-ci-analyze'))
REPO_ROOT = os.environ.get(
    'KYO_REPO_ROOT', os.path.dirname(os.path.dirname(os.path.abspath(__file__))))


# ---------------------------------------------------------------- gh plumbing

def gh_repo():
    if os.environ.get('REPO'):
        return os.environ['REPO']
    out = subprocess.run(['gh', 'repo', 'view', '--json', 'nameWithOwner', '-q',
                          '.nameWithOwner'], capture_output=True, text=True)
    return out.stdout.strip() or 'getkyo/kyo'


def gh_api(path, paginate=False):
    cmd = ['gh', 'api', path] + (['--paginate'] if paginate else [])
    r = subprocess.run(cmd, capture_output=True, text=True)
    if r.returncode != 0:
        raise SystemExit(f"gh api {path} failed: {r.stderr.strip()}")
    return r.stdout


def gh_api_pages(path):
    """gh --paginate concatenates one JSON document per page; decode in sequence."""
    raw = gh_api(path, paginate=True)
    dec, i, out = json.JSONDecoder(), 0, []
    while i < len(raw):
        while i < len(raw) and raw[i].isspace():
            i += 1
        if i >= len(raw):
            break
        obj, i = dec.raw_decode(raw, i)
        out.append(obj)
    return out


def run_id_of(arg):
    m = re.search(r'/runs/(\d+)', str(arg))
    return m.group(1) if m else str(arg)


# ---------------------------------------------------------------- log parsing

TS = re.compile(r'^﻿?(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d+)Z ?(.*)$')
ANSI = re.compile(r'\x1b\[[0-9;]*[A-Za-z]')


def _ts(s):
    d, frac = s.split('.')
    return dt.datetime.fromisoformat(d + '.' + frac[:6]).replace(tzinfo=dt.timezone.utc)


def log_lines(path):
    """Yield (datetime, ansi-stripped body) for every line.

    A line the runner emitted without a timestamp (the continuation of a
    multi-line value) inherits the previous line's timestamp rather than being
    dropped, so nothing silently leaves the timeline.
    """
    last = None
    with open(path, encoding='utf-8', errors='replace') as f:
        for raw in f:
            raw = raw.rstrip('\n')
            m = TS.match(raw)
            if m:
                last = _ts(m.group(1))
                yield last, ANSI.sub('', m.group(2)).rstrip()
            elif last is not None:
                yield last, ANSI.sub('', raw).rstrip()


PHASE = re.compile(r'^\[testKyo\] (compiling main for|compiling test for|testing) (\d+) projects: (.*)$')
RUNNING = re.compile(r'^\[testKyo\] running: (.*)$')
TERM = re.compile(r'^\[(success|error)\] Total time: (\d+) s, completed (.*)$')
NESTED_TERM = re.compile(r'^\[info\] \[(success|error)\] Total time: \d+ s')
KYO_SUITE_START = re.compile(r'^=== ([\w.$]+) ===$')
KYO_SUITE_END = re.compile(r'^--- ([\w.$]+): (\d+) passed, (\d+) failed(?:, (\d+) cancelled)?\s+\(([0-9.]+)(ms|s|m|h)\)$')
KYO_TEST = re.compile(r'^\s*\[(PASS|FAIL|CANCELLED|IGNORED)\] (.*?)\s+\(([0-9.]+)(ms|s|m|h)\)\s*(.*)$')
ST_SUITE = re.compile(r'^\[info\] ([A-Z][\w.$]*):\s*$')
ST_TEST = re.compile(r'^\[info\] - (.*) \((\d+) (millisecond|second|minute|hour)s?\)$')
ST_RUN_DONE = re.compile(r'^\[info\] Run completed in (.*)\.$')
CIMON = re.compile(r'^\[ci-mon (\d{2}:\d{2}:\d{2})\] (.*)$')
CIMON_START = re.compile(r'^=== \[ci-mon\] \d{2}:\d{2}:\d{2} started (.*) ===$')
CIMON_DISK = re.compile(r'^\[ci-mon-disk\] (.*)$')
CITEST = re.compile(r'^=== \[ci-test\] (\d{2}:\d{2}:\d{2}) (.*) ===$')
KV = re.compile(r'(\w+)=(\[[^\]]*\]|\S+)')

UNIT_MS = {'millisecond': 1, 'second': 1000, 'minute': 60000, 'hour': 3600000,
           'ms': 1, 's': 1000, 'm': 60000, 'h': 3600000}

# every retry / watchdog / abort ci-test.sh and ci-monitor.sh can emit
RETRY = re.compile(
    r'^(attempt \d+/\d+|no test output|native test runner crashed|no output for \d+s|'
    r'tests FAILED|watchdog killed|FAILED: no test output|native pre-link|'
    r'native linking failed|DISK-WARN|DISK-CRIT|DISK-ABORT|kernel OOM)')


def parse_job(path):
    """Parse one raw job log into a structured record."""
    rec = dict(path=path, first=None, last=None, phases=[], cmds=[], terms=[],
               suites=[], tests=[], cimon=[], cimon_hdr={}, disk=[], citest=[],
               retries=[], env={})
    cur = None
    hdr_done = 0

    for t, b in log_lines(path):
        if rec['first'] is None:
            rec['first'] = t
        rec['last'] = t

        if hdr_done < 200:
            hdr_done += 1
            for key, rx in (('runner', r"^Current runner version: '(.*)'"),
                            ('region', r'^Azure Region: (.*)'),
                            ('worker', r'^Worker ID: (.*)'),
                            ('image', r'^Version: (\d{8}\.\S+)')):
                m = re.match(rx, b)
                if m and key not in rec['env']:
                    rec['env'][key] = m.group(1)

        m = CIMON.match(b)
        if m:
            d = dict(KV.findall(m.group(2)))
            d['t'] = t
            rec['cimon'].append(d)
            continue
        m = CIMON_START.match(b)
        if m:
            rec['cimon_hdr'] = dict(KV.findall(m.group(1)))
            continue
        m = CIMON_DISK.match(b)
        if m:
            rec['disk'].append((t, m.group(1)))
            continue
        m = CITEST.match(b)
        if m:
            rec['citest'].append((t, m.group(2)))
            if RETRY.match(m.group(2)):
                rec['retries'].append((t, m.group(2)))
            continue

        m = PHASE.match(b)
        if m:
            rec['phases'].append(dict(t=t, phase=m.group(1), n=int(m.group(2)),
                                      projects=[p.strip() for p in m.group(3).split(',')]))
            continue
        m = RUNNING.match(b)
        if m:
            rec['cmds'].append(dict(t=t, cmds=[c.strip() for c in m.group(1).split(';') if c.strip()]))
            continue
        if NESTED_TERM.match(b):
            continue
        m = TERM.match(b)
        if m:
            rec['terms'].append(dict(t=t, status=m.group(1), secs=int(m.group(2))))
            continue

        m = KYO_SUITE_START.match(b)
        if m:
            if cur:
                cur['end'] = t
                rec['suites'].append(cur)
            cur = dict(name=m.group(1), fw='kyo-test', start=t, end=None,
                       reported_ms=None, passed=0, failed=0, cancelled=0)
            continue
        m = KYO_SUITE_END.match(b)
        if m:
            ms = float(m.group(5)) * UNIT_MS[m.group(6)]
            fin = dict(name=m.group(1), fw='kyo-test', reported_ms=ms,
                       passed=int(m.group(2)), failed=int(m.group(3)),
                       cancelled=int(m.group(4) or 0))
            if cur and cur['name'] == m.group(1):
                cur.update(fin, end=t)
                rec['suites'].append(cur)
                cur = None
            else:
                rec['suites'].append(dict(fin, start=t, end=t))
            continue
        m = KYO_TEST.match(b)
        if m:
            rec['tests'].append((t, 'kyo-test', m.group(1), m.group(2),
                                 float(m.group(3)) * UNIT_MS[m.group(4)],
                                 cur['name'] if cur else None, m.group(5)))
            continue

        m = ST_SUITE.match(b)
        if m:
            if cur:
                cur['end'] = t
                rec['suites'].append(cur)
            cur = dict(name=m.group(1), fw='scalatest', start=t, end=None,
                       reported_ms=None, passed=0, failed=0, cancelled=0)
            continue
        m = ST_TEST.match(b)
        if m:
            rec['tests'].append((t, 'scalatest', 'PASS', m.group(1),
                                 int(m.group(2)) * UNIT_MS[m.group(3)],
                                 cur['name'] if cur else None, ''))
            continue
        if ST_RUN_DONE.match(b) and cur:
            cur['end'] = t
            rec['suites'].append(cur)
            cur = None

    if cur:
        cur['end'] = rec['last']
        rec['suites'].append(cur)
    for s in rec['suites']:
        s['wall_s'] = (s['end'] - s['start']).total_seconds() if s['end'] else 0.0
    return rec


def phase_groups(rec):
    """Collapse the enqueued cross-build batches into one row per phase kind.

    testKyo enqueues every batch of a phase before sbt drains them, so per-batch
    deltas are queueing noise; a phase runs from its first marker to the first
    marker of the next kind.
    """
    groups = []
    for ph in rec['phases']:
        if groups and groups[-1]['phase'] == ph['phase']:
            groups[-1]['n'] += ph['n']
        else:
            groups.append(dict(phase=ph['phase'], t=ph['t'], n=ph['n']))
    for i, g in enumerate(groups):
        g['end'] = groups[i + 1]['t'] if i + 1 < len(groups) else rec['last']
        g['span'] = (g['end'] - g['t']).total_seconds()
    return groups


# ---------------------------------------------------------------- suite -> module

DECL = re.compile(r'^\s*(?:final\s+|abstract\s+|sealed\s+|private\s+)*(?:class|object)\s+([A-Z][\w$]*)')


def build_suite_index(root=REPO_ROOT):
    idx, ambig = {}, defaultdict(set)
    for dirpath, dirnames, files in os.walk(root):
        dirnames[:] = [d for d in dirnames
                       if d not in ('target', '.git', 'node_modules', '.bloop', '.metals')]
        if f'{os.sep}src{os.sep}test{os.sep}' not in dirpath + os.sep:
            continue
        module = os.path.relpath(dirpath, root).split(os.sep)[0]
        if not module.startswith('kyo'):
            continue
        for fn in files:
            if not fn.endswith('.scala'):
                continue
            try:
                with open(os.path.join(dirpath, fn), encoding='utf-8', errors='replace') as f:
                    for line in f:
                        m = DECL.match(line)
                        if m:
                            name = m.group(1)
                            if name in idx and idx[name] != module:
                                ambig[name].update((idx[name], module))
                            idx.setdefault(name, module)
            except OSError:
                pass
    return idx, {k: sorted(v) for k, v in ambig.items()}


def suite_index(refresh=False):
    p = os.path.join(CACHE, 'suite-index.json')
    if not refresh and os.path.exists(p):
        with open(p) as f:
            d = json.load(f)
        return d['index'], d['ambiguous']
    idx, ambig = build_suite_index()
    os.makedirs(CACHE, exist_ok=True)
    with open(p, 'w') as f:
        json.dump(dict(index=idx, ambiguous=ambig), f)
    return idx, ambig


def attribute_modules(suites, idx, ambig):
    """Assign each suite a module, resolving ambiguous names by time locality.

    The same test class name can exist in two modules (FiberTest in kyo-core and
    kyo-compat). sbt runs one module's test task at a time, so an ambiguous suite
    belongs to whichever candidate its nearest unambiguous neighbours belong to.
    """
    resolved = [None if s['name'] in ambig else idx.get(s['name']) for s in suites]
    known = [(i, m) for i, m in enumerate(resolved) if m]
    for i, s in enumerate(suites):
        if resolved[i] is None and s['name'] in ambig:
            cands = set(ambig[s['name']])
            best, bestd = None, None
            for j, m in known:
                if m in cands and (bestd is None or abs(j - i) < bestd):
                    best, bestd = m, abs(j - i)
            resolved[i] = best or sorted(cands)[0]
    for s, m in zip(suites, resolved):
        s['module'] = m or '(unmapped)'
    return suites


def module_blocks(rec, idx, ambig, k=3):
    """Split the run into contiguous per-module blocks, with resources joined.

    Grouping every suite that shares a name-derived module would double-count:
    kyo-test's runner self-tests execute CHILD suites that echo their whole
    report, so a few foreign suite names reappear far from their own module's
    block. Blocks are built by scanning in time order and switching module only
    after k consecutive suites agree, which absorbs those nested one-offs.
    Blocks tile the timeline, so their spans sum to the test phase.
    """
    attribute_modules(rec['suites'], idx, ambig)
    ss = sorted(rec['suites'], key=lambda s: s['start'])
    blocks, i = [], 0
    while i < len(ss):
        mod, j = ss[i]['module'], i + 1
        while j < len(ss):
            if ss[j]['module'] == mod:
                j += 1
                continue
            nxt, run = ss[j]['module'], 1
            while j + run < len(ss) and ss[j + run]['module'] == nxt:
                run += 1
            if run >= k or j + run >= len(ss):
                break
            j += run
        blocks.append(dict(module=mod, start=ss[i]['start'],
                           end=max(s['end'] or s['start'] for s in ss[i:j]),
                           suites=ss[i:j]))
        i = j

    samples = []
    for s in rec['cimon']:
        try:
            samples.append((s['t'], float(s['load']), float(s['availMB'])))
        except (KeyError, ValueError):
            pass
    samples.sort()

    st_ms, st_n = defaultdict(float), defaultdict(int)
    for t, fw, status, name, ms, suite, note in rec['tests']:
        st_n[suite] += 1
        if fw == 'scalatest':
            st_ms[suite] += ms

    for b in blocks:
        b['span'] = (b['end'] - b['start']).total_seconds()
        b['reported'] = (sum((s.get('reported_ms') or 0) for s in b['suites'])
                         + sum(st_ms[s['name']] for s in b['suites'])) / 1000.0
        b['cancelled'] = sum(s.get('cancelled', 0) or 0 for s in b['suites'])
        b['failed'] = sum(s.get('failed', 0) or 0 for s in b['suites'])
        b['tests'] = sum(st_n[s['name']] for s in b['suites'])
        inb = [x for x in samples if b['start'] <= x[0] <= b['end']]
        b['load'] = [x[1] for x in inb]
        b['avail'] = [x[2] for x in inb]
    return blocks


def module_agg(blocks):
    agg = defaultdict(lambda: dict(reported=0.0, span=0.0, suites=0, tests=0,
                                   cancelled=0, failed=0, blocks=0,
                                   first=None, last=None, load=[], avail=[]))
    for b in blocks:
        e = agg[b['module']]
        for key in ('span', 'reported', 'cancelled', 'failed', 'tests'):
            e[key] += b[key]
        e['suites'] += len(b['suites'])
        e['blocks'] += 1
        e['load'] += b['load']
        e['avail'] += b['avail']
        if e['first'] is None or b['start'] < e['first']:
            e['first'] = b['start']
        if e['last'] is None or b['end'] > e['last']:
            e['last'] = b['end']
    return agg


# ---------------------------------------------------------------- cache / fetch

def cache_dir(run):
    return os.path.join(CACHE, str(run))


def fetch(run, force=False, quiet=False):
    run = run_id_of(run)
    d = cache_dir(run)
    os.makedirs(os.path.join(d, 'raw'), exist_ok=True)
    repo = gh_repo()
    jobs = []
    for page in gh_api_pages(f'repos/{repo}/actions/runs/{run}/jobs?per_page=100'):
        jobs.extend(page.get('jobs', []))
    with open(os.path.join(d, 'jobs.json'), 'w') as f:
        json.dump(jobs, f)
    got = 0
    for j in jobs:
        if j['status'] != 'completed':
            continue
        p = os.path.join(d, 'raw', f"{j['id']}.log")
        if os.path.exists(p) and not force and os.path.getsize(p) > 0:
            continue
        try:
            txt = gh_api(f"repos/{repo}/actions/jobs/{j['id']}/logs")
        except SystemExit:
            continue
        with open(p, 'w', encoding='utf-8') as f:
            f.write(txt)
        got += 1
    if not quiet:
        print(f"run {run}: {len(jobs)} jobs, {got} logs downloaded, cache {d}")
    return d, jobs


def load_run(run, refresh=False):
    run = run_id_of(run)
    d = cache_dir(run)
    jp = os.path.join(d, 'jobs.json')
    if refresh or not os.path.exists(jp):
        return fetch(run, quiet=True)
    with open(jp) as f:
        return d, json.load(f)


def select_jobs(d, jobs, args):
    """Jobs with a cached log, filtered by --job (id or name substring)."""
    want = getattr(args, 'job', None)
    for j in jobs:
        if want and str(want) != str(j['id']) and want.lower() not in short(j['name']).lower():
            continue
        p = os.path.join(d, 'raw', f"{j['id']}.log")
        if os.path.exists(p) and os.path.getsize(p) > 0:
            yield j, p


def short(name):
    m = re.match(r'^build \(([\w-]+),[^)]*\) / build \((\w+)\)$', name)
    if m:
        return f'{m.group(1)}/{m.group(2)}'
    m = re.match(r'^build \(([\w-]+),[^)]*\) / (\w+)$', name)
    if m:
        return f'{m.group(1)}/{m.group(2)}'
    return name


def dur(a, b):
    return None if not (a and b) else (_p(b) - _p(a)).total_seconds()


def _p(s):
    return dt.datetime.fromisoformat(s.replace('Z', '+00:00'))


def hms(sec):
    if sec is None:
        return '   --   '
    sec = int(sec)
    return f'{sec//3600}h{(sec%3600)//60:02d}m{sec%60:02d}s'


def pct(v, q):
    if not v:
        return float('nan')
    s = sorted(v)
    return s[min(len(s) - 1, int(len(s) * q))]


def rx_of(s):
    return re.compile(s, re.I) if s else None


def _load_note(loads, cores):
    if not loads:
        return ''
    p50 = pct(loads, .5)
    busy = p50 / cores * 100 if cores else 0
    return f'load p50 {p50:.2f} ({busy:.0f}% of {cores:.0f} cores)'


# ---------------------------------------------------------------- views

def cmd_fetch(a):
    fetch(a.run, force=a.force)


def cmd_runs(a):
    repo = gh_repo()
    runs = json.loads(gh_api(
        f'repos/{repo}/actions/workflows/ci.yml/runs?branch={a.branch}&per_page={a.n}'))['workflow_runs']
    print(f"{'run id':<13} {'created':<21} {'status':<11} {'concl':<9} {'elapsed':<9} sha       title")
    for r in runs:
        print(f"{r['id']:<13} {r['created_at']:<21} {r['status']:<11} "
              f"{(r['conclusion'] or ''):<9} {hms(dur(r['run_started_at'], r['updated_at'])):<9} "
              f"{r['head_sha'][:9]} {(r['display_title'] or '')[:58]}")


def cmd_timeline(a):
    d, jobs = load_run(a.run, a.refresh)
    idx, ambig = suite_index()
    print(f"=== run {run_id_of(a.run)} : job wall clock ===\n")
    rows = sorted(((j['name'], dur(j['started_at'], j['completed_at']),
                    j['conclusion'] or j['status'], j['id']) for j in jobs),
                  key=lambda r: -(r[1] or 0))
    for name, sec, concl, jid in rows:
        print(f"  {hms(sec)}  {concl:<11} {short(name):<22} {jid}")
    if a.detail == 0:
        print("\n  (-v for phases, -vv for module blocks, -vvv for suites)")
        return

    for j, p in select_jobs(d, jobs, a):
        rec = parse_job(p)
        cores = float(rec['cimon_hdr'].get('cores', 0) or 0)
        print(f"\n--- {short(j['name'])} [{j['id']}]  "
              f"{rec['env'].get('image','?')} {rec['env'].get('region','?')} ---")
        for s in (j.get('steps') or []):
            sd = dur(s.get('started_at'), s.get('completed_at'))
            if sd and sd >= 1:
                print(f"    step  {hms(sd)}  {s['name'][:62]}")
        for g in phase_groups(rec):
            print(f"    phase {hms(g['span'])}  {g['phase']:<19} n={g['n']}")
        print(f"    retries/watchdog: {len(rec['retries']) or 'none'}")
        for t, m in rec['retries']:
            print(f"        {t:%H:%M:%S} {m[:100]}")
        if a.detail < 2:
            continue
        blocks = module_blocks(rec, idx, ambig)
        print(f"    {'start':<9}{'span':>9} {'reported':>10} {'gap':>8}  {'load p50':>9}  module")
        for b in blocks:
            if b['span'] < a.min:
                continue
            print(f"    {b['start']:%H:%M:%S}{b['span']:9.0f} {b['reported']:10.1f} "
                  f"{b['span']-b['reported']:8.0f}  {pct(b['load'], .5):9.2f}  {b['module']}"
                  + (f"  (+{b['cancelled']} cancelled)" if b['cancelled'] else ''))
            if a.detail >= 3:
                for s in sorted(b['suites'], key=lambda s: -s['wall_s'])[:a.top]:
                    rep = (s.get('reported_ms') or 0) / 1000.0
                    print(f"        {s['wall_s']:8.0f}s wall {rep:8.1f}s reported  {s['name']}")


def cmd_modules(a):
    d, jobs = load_run(a.run, a.refresh)
    idx, ambig = suite_index()
    mrx = rx_of(a.module)
    for j, p in select_jobs(d, jobs, a):
        rec = parse_job(p)
        blocks = module_blocks(rec, idx, ambig)
        agg = module_agg(blocks)
        cores = float(rec['cimon_hdr'].get('cores', 0) or 0)
        print(f"\n=== {short(j['name'])} [{j['id']}] per-module "
              f"({rec['env'].get('image','?')}, {rec['env'].get('region','?')}, {cores:.0f} cores) ===")
        print(f"  {'span_s':>8} {'reported':>9} {'gap_s':>8} {'gap%':>5} {'suites':>7} "
              f"{'tests':>7} {'canc':>6} {'loadp50':>8} {'availMB':>8}  module")
        tot_s = tot_r = 0.0
        for mod, e in sorted(agg.items(), key=lambda kv: -kv[1]['span']):
            tot_s += e['span']
            tot_r += e['reported']
            if mrx and not mrx.search(mod):
                continue
            if e['span'] < a.min:
                continue
            gap = e['span'] - e['reported']
            print(f"  {e['span']:8.0f} {e['reported']:9.1f} {gap:8.0f} "
                  f"{gap/e['span']*100 if e['span'] else 0:4.0f}% {e['suites']:7} "
                  f"{e['tests']:7} {e['cancelled']:6} {pct(e['load'], .5):8.2f} "
                  f"{pct(e['avail'], .5):8.0f}  {mod}")
        print(f"  {'-'*96}")
        print(f"  total span {tot_s/60:.1f} min, reported {tot_r/60:.1f} min, "
              f"gap {(tot_s-tot_r)/60:.1f} min ({(tot_s-tot_r)/tot_s*100 if tot_s else 0:.0f}% of the test phase is not test execution)")
        if a.detail >= 1:
            print(f"\n  --- blocks in run order ---")
            for b in blocks:
                if mrx and not mrx.search(b['module']):
                    continue
                if b['span'] < a.min:
                    continue
                print(f"  {b['start']:%H:%M:%S}-{b['end']:%H:%M:%S} {b['span']:7.0f}s "
                      f"reported {b['reported']:7.1f}s  {_load_note(b['load'], cores):<34} {b['module']}")


def cmd_suites(a):
    d, jobs = load_run(a.run, a.refresh)
    idx, ambig = suite_index()
    mrx, srx = rx_of(a.module), rx_of(a.suite)
    for j, p in select_jobs(d, jobs, a):
        rec = parse_job(p)
        blocks = module_blocks(rec, idx, ambig)
        rows = []
        for b in blocks:
            for s in b['suites']:
                if mrx and not mrx.search(b['module']):
                    continue
                if srx and not srx.search(s['name']):
                    continue
                rows.append((b['module'], s))
        rows.sort(key=lambda r: -r[1]['wall_s'])
        print(f"\n=== {short(j['name'])} [{j['id']}] suites "
              f"({len(rows)} matched, top {a.top} by wall) ===")
        print(f"  {'wall_s':>8} {'reported':>9} {'gap_s':>8} {'pass':>6} {'fail':>5} "
              f"{'canc':>5}  {'module':<20} suite")
        for mod, s in rows[:a.top]:
            rep = (s.get('reported_ms') or 0) / 1000.0
            print(f"  {s['wall_s']:8.0f} {rep:9.1f} {s['wall_s']-rep:8.0f} "
                  f"{s.get('passed',0):6} {s.get('failed',0):5} {s.get('cancelled',0):5}  "
                  f"{mod:<20} {s['name']}")
        if a.detail >= 1:
            print(f"\n  matched-suite totals: wall {sum(s['wall_s'] for _, s in rows)/60:.1f} min, "
                  f"reported {sum((s.get('reported_ms') or 0) for _, s in rows)/60000:.1f} min")


def cmd_tests(a):
    d, jobs = load_run(a.run, a.refresh)
    idx, ambig = suite_index()
    mrx, srx, trx = rx_of(a.module), rx_of(a.suite), rx_of(a.test)
    for j, p in select_jobs(d, jobs, a):
        rec = parse_job(p)
        module_blocks(rec, idx, ambig)
        smod = {s['name']: s['module'] for s in rec['suites']}
        rows = []
        for t, fw, status, name, ms, suite, note in rec['tests']:
            mod = smod.get(suite, '(unmapped)')
            if mrx and not mrx.search(mod):
                continue
            if srx and not srx.search(suite or ''):
                continue
            if trx and not trx.search(name):
                continue
            if ms < a.slower_than:
                continue
            rows.append((ms, status, mod, suite, name, note))
        rows.sort(reverse=True)
        tot = sum(r[0] for r in rows)
        print(f"\n=== {short(j['name'])} [{j['id']}] tests "
              f"({len(rows)} matched, {tot/60000:.1f} min reported) ===")
        print(f"  {'ms':>9} {'status':<10} {'module':<18} {'suite':<28} test")
        for ms, status, mod, suite, name, note in rows[:a.top]:
            print(f"  {ms:9.0f} {status:<10} {mod:<18} {str(suite)[:28]:<28} {name[:70]}")
        if a.detail >= 1:
            by = defaultdict(lambda: [0.0, 0])
            for ms, status, mod, suite, name, note in rows:
                e = by[status]
                e[0] += ms
                e[1] += 1
            print("  --- by status ---")
            for st, (ms, n) in sorted(by.items(), key=lambda kv: -kv[1][0]):
                print(f"    {st:<10} n={n:<7} {ms/60000:8.1f} min")
        if a.detail >= 2:
            notes = defaultdict(int)
            for ms, status, mod, suite, name, note in rows:
                if note:
                    notes[note[:110]] += 1
            if notes:
                print("  --- cancellation / annotation reasons ---")
                for nt, n in sorted(notes.items(), key=lambda kv: -kv[1])[:15]:
                    print(f"    {n:>6}  {nt}")


def cmd_resources(a):
    d, jobs = load_run(a.run, a.refresh)
    for j, p in select_jobs(d, jobs, a):
        rec = parse_job(p)
        rows = rec['cimon']
        cores = float(rec['cimon_hdr'].get('cores', 0) or 0)
        print(f"\n=== {short(j['name'])} [{j['id']}] "
              f"({rec['env'].get('image','?')}, {rec['env'].get('region','?')}) ===")
        if not rows:
            print("  no ci-mon samples")
            continue

        def col(k):
            out = []
            for x in rows:
                try:
                    out.append(float(x[k]))
                except (KeyError, ValueError):
                    pass
            return out

        span = (rows[-1]['t'] - rows[0]['t']).total_seconds()
        print(f"  cores={cores:.0f} interval={rec['cimon_hdr'].get('interval','?')} "
              f"samples={len(rows)} span={span/60:.1f}min")
        steal = col('stealTicks')
        if steal:
            ds = steal[-1] - steal[0]
            print(f"  cpu steal : +{ds:.0f} ticks = "
                  f"{(ds/100.0)/(span*cores)*100 if span and cores else 0:.3f}% of cpu time "
                  f"({'no hypervisor contention' if ds == 0 else 'CONTENTION'})")
        else:
            print("  cpu steal : not sampled on this platform")
        for label in ('load', 'availMB', 'psiMem10', 'diskFreeMB'):
            v = col(label)
            if v:
                print(f"  {label:<11}: min={min(v):9.2f} p50={pct(v,.5):9.2f} "
                      f"p95={pct(v,.95):9.2f} max={max(v):9.2f}")
        load = col('load')
        if load and cores:
            iv = float(str(rec['cimon_hdr'].get('interval', '20s')).rstrip('s') or 20)
            lo = sum(1 for x in load if x < 1.0)
            hi = sum(1 for x in load if x >= cores * 0.75)
            print(f"  load<1.0    : {lo}/{len(load)} samples ({lo/len(load)*100:.0f}%) "
                  f"~{lo*iv/60:.0f} min with under one core busy")
            print(f"  load>={cores*0.75:.1f} : {hi}/{len(load)} samples ({hi/len(load)*100:.0f}%) "
                  f"~{hi*iv/60:.0f} min using most of the box")
        for t, msg in rec['disk'][:10] if a.detail >= 1 else []:
            print(f"  disk {t:%H:%M:%S} {msg[:110]}")
        if a.detail >= 2:
            print(f"  --- raw series (every {a.every} samples) ---")
            for x in rows[::a.every]:
                print(f"    {x['t']:%H:%M:%S} load={x.get('load','?'):>6} "
                      f"availMB={x.get('availMB','?'):>7} psi={x.get('psiMem10','?'):>5} "
                      f"top={x.get('top','')[:70]}")


def cmd_idle(a):
    d, jobs = load_run(a.run, a.refresh)
    for j, p in select_jobs(d, jobs, a):
        rec = parse_job(p)
        cores = float(rec['cimon_hdr'].get('cores', 0) or 0)
        per_min = defaultdict(lambda: [0, []])
        for t, b in log_lines(p):
            k = t.strftime('%H:%M')
            m = CIMON.match(b)
            if m:
                dd = dict(KV.findall(m.group(2)))
                try:
                    per_min[k][1].append(float(dd['load']))
                except (KeyError, ValueError):
                    pass
                continue
            if b.strip():
                per_min[k][0] += 1
        runs, cur = [], None
        for k in sorted(per_min):
            n, loads = per_min[k]
            lm = pct(loads, .5) if loads else None
            if n < a.threshold and (lm is None or lm < a.load):
                cur = cur or [k, k, []]
                cur[1] = k
                if lm is not None:
                    cur[2].append(lm)
            elif cur:
                runs.append(cur)
                cur = None
        if cur:
            runs.append(cur)
        runs = [r for r in runs if r[0] != r[1]]
        print(f"\n=== {short(j['name'])} [{j['id']}] stretches under "
              f"{a.threshold} output lines/min and load<{a.load} ===")
        tot = 0
        for s, e, loads in runs:
            mins = (dt.datetime.strptime(e, '%H:%M') - dt.datetime.strptime(s, '%H:%M')).total_seconds()/60 + 1
            tot += mins
            print(f"  {s}-{e}  {mins:5.0f} min   mean load "
                  f"{sum(loads)/len(loads) if loads else float('nan'):.2f}")
        print(f"  total {tot:.0f} min of near-idle wall clock "
              f"({tot/((rec['last']-rec['first']).total_seconds()/60)*100:.0f}% of the job)")


def cmd_gaps(a):
    """Dead time BETWEEN suites: the log is silent and nothing is scheduled.

    A cluster of near-identical gap values is the signature of a fixed timeout
    firing rather than of work: real work produces a spread.
    """
    d, jobs = load_run(a.run, a.refresh)
    idx, ambig = suite_index()
    mrx = rx_of(a.module)
    grand = Counter()
    for j, p in select_jobs(d, jobs, a):
        rec = parse_job(p)
        blocks = module_blocks(rec, idx, ambig)
        rows, per_mod = [], defaultdict(float)
        for b in blocks:
            if mrx and not mrx.search(b['module']):
                continue
            ss = sorted(b['suites'], key=lambda s: s['start'])
            for i in range(len(ss) - 1):
                g = (ss[i + 1]['start'] - (ss[i]['end'] or ss[i]['start'])).total_seconds()
                if g >= a.min:
                    rows.append((g, b['module'], ss[i]['name'], ss[i + 1]['name'], ss[i]['end']))
                    per_mod[b['module']] += g
                    grand[round(g * 2) / 2] += 1
        job_s = (rec['last'] - rec['first']).total_seconds()
        tot = sum(r[0] for r in rows)
        print(f"\n=== {short(j['name'])} [{j['id']}] inter-suite dead time (>={a.min}s) ===")
        print(f"  {len(rows)} gaps, {tot/60:.1f} min total "
              f"({tot/job_s*100 if job_s else 0:.0f}% of the job's {job_s/60:.0f} min)")
        for mod, v in sorted(per_mod.items(), key=lambda kv: -kv[1]):
            print(f"    {v:8.0f}s  {mod}")
        if a.detail >= 1:
            print(f"  --- longest ---")
            for g, mod, prev, nxt, at in sorted(rows, reverse=True)[:a.top]:
                print(f"    {g:7.1f}s at {at:%H:%M:%S}  {mod:<12} after {prev} -> {nxt}")
    if grand:
        print(f"\n=== gap-value histogram across the selected jobs ===")
        print("  a spike at one value means a timeout, not work")
        for v, n in sorted(grand.items(), key=lambda kv: -kv[1])[:12]:
            print(f"  {v:7.1f}s  x{n}")


def cmd_compare(a):
    d, jobs = load_run(a.run, a.refresh)
    idx, ambig = suite_index()
    byid = {str(j['id']): j for j in jobs}

    def pick(tok):
        if str(tok) in byid:
            return str(tok)
        for j in jobs:
            if tok.lower() in short(j['name']).lower():
                return str(j['id'])
        raise SystemExit(f"no job matching {tok!r}")

    aid, bid = pick(a.a), pick(a.b)
    ra = parse_job(os.path.join(d, 'raw', f'{aid}.log'))
    rb = parse_job(os.path.join(d, 'raw', f'{bid}.log'))
    print(f"A = {short(byid[aid]['name'])} [{aid}]  "
          f"{hms(dur(byid[aid]['started_at'], byid[aid]['completed_at']))}")
    print(f"B = {short(byid[bid]['name'])} [{bid}]  "
          f"{hms(dur(byid[bid]['started_at'], byid[bid]['completed_at']))}\n")

    def agg_tests(r):
        ran, canc = defaultdict(float), set()
        for t, fw, st, name, ms, suite, note in r['tests']:
            (canc.add(name) if st == 'CANCELLED' else ran.__setitem__(name, ran[name] + ms))
        return ran, canc

    A, ca = agg_tests(ra)
    B, cb = agg_tests(rb)
    both = set(A) & set(B)
    onlyA, onlyB = set(A) - set(B), set(B) - set(A)
    sa, sb = sum(A[k] for k in both), sum(B[k] for k in both)
    print(f"  A executed {len(A):>6} tests, cancelled {len(ca):>5}")
    print(f"  B executed {len(B):>6} tests, cancelled {len(cb):>5}")
    print(f"\n  [workload] only B ran : {len(onlyB):>6} tests  {sum(B[k] for k in onlyB)/60000:8.1f} min")
    print(f"  [workload] only A ran : {len(onlyA):>6} tests  {sum(A[k] for k in onlyA)/60000:8.1f} min")
    print(f"  [speed]    both ran   : {len(both):>6} tests  A={sa/60000:.1f} min "
          f"B={sb/60000:.1f} min  ratio={sb/sa if sa else 0:.2f}x")

    print("\n  --- per-module span, B minus A ---")
    aa = module_agg(module_blocks(ra, idx, ambig))
    bb = module_agg(module_blocks(rb, idx, ambig))
    print(f"  {'A_span':>8} {'B_span':>8} {'delta':>9}  {'A_canc':>7} {'B_canc':>7}  module")
    for m in sorted(set(aa) | set(bb),
                    key=lambda m: -(bb.get(m, {}).get('span', 0) - aa.get(m, {}).get('span', 0))):
        ea, eb = aa.get(m, {}), bb.get(m, {})
        spa, spb = ea.get('span', 0), eb.get('span', 0)
        if max(spa, spb) < a.min:
            continue
        print(f"  {spa:8.0f} {spb:8.0f} {spb-spa:+9.0f}  {ea.get('cancelled',0):7} "
              f"{eb.get('cancelled',0):7}  {m}")

    if a.detail >= 1:
        print("\n  --- individual tests both ran, by added time ---")
        rows = sorted(((B[k] - A[k], A[k], B[k], k) for k in both), reverse=True)
        for dlt, x, y, k in rows[:a.top]:
            print(f"  {dlt/1000:+8.1f}s  A={x/1000:7.1f}s B={y/1000:7.1f}s  {k[:78]}")
        print("  --- and where B is faster ---")
        for dlt, x, y, k in rows[-min(a.top, 10):]:
            print(f"  {dlt/1000:+8.1f}s  A={x/1000:7.1f}s B={y/1000:7.1f}s  {k[:78]}")
    if a.detail >= 2:
        print("\n  --- tests only B ran, by cost ---")
        for k in sorted(onlyB, key=lambda k: -B[k])[:a.top]:
            print(f"  {B[k]/1000:8.1f}s  {k[:88]}")


def main():
    ap = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest='cmd', required=True)

    def common(p, job=True):
        p.add_argument('-v', '--verbose', dest='detail', action='count', default=0,
                       help='more detail; repeat for more (-v, -vv, -vvv)')
        p.add_argument('--refresh', action='store_true', help='re-read job metadata from the API')
        if job:
            p.add_argument('--job', help='job id, or a substring of os/target such as linux-x64/JVM')
        return p

    p = sub.add_parser('fetch', help='download and cache every job log of a run')
    p.add_argument('run'); p.add_argument('--force', action='store_true'); p.set_defaults(f=cmd_fetch)

    p = sub.add_parser('runs', help='recent ci runs on a branch')
    p.add_argument('branch', nargs='?', default='main'); p.add_argument('n', nargs='?', type=int, default=15)
    p.set_defaults(f=cmd_runs)

    p = common(sub.add_parser('timeline', help='job durations, then phases, blocks, suites'))
    p.add_argument('run'); p.add_argument('--min', type=float, default=5)
    p.add_argument('--top', type=int, default=8); p.set_defaults(f=cmd_timeline)

    p = common(sub.add_parser('modules', help='per-module span vs reported, with resources'))
    p.add_argument('run'); p.add_argument('--module'); p.add_argument('--min', type=float, default=0)
    p.set_defaults(f=cmd_modules)

    p = common(sub.add_parser('suites', help='per-suite span vs reported'))
    p.add_argument('run'); p.add_argument('--module'); p.add_argument('--suite')
    p.add_argument('--top', type=int, default=30); p.set_defaults(f=cmd_suites)

    p = common(sub.add_parser('tests', help='individual test durations'))
    p.add_argument('run'); p.add_argument('--module'); p.add_argument('--suite'); p.add_argument('--test')
    p.add_argument('--top', type=int, default=30)
    p.add_argument('--slower-than', type=float, default=0, metavar='MS')
    p.set_defaults(f=cmd_tests)

    p = common(sub.add_parser('resources', help='ci-monitor.sh series: load, memory, steal, disk'))
    p.add_argument('run'); p.add_argument('--every', type=int, default=15); p.set_defaults(f=cmd_resources)

    p = common(sub.add_parser('idle', help='stretches with no output and no load'))
    p.add_argument('run'); p.add_argument('--threshold', type=int, default=30)
    p.add_argument('--load', type=float, default=1.0); p.set_defaults(f=cmd_idle)

    p = common(sub.add_parser('gaps', help='dead time between suites (timeout signatures)'))
    p.add_argument('run'); p.add_argument('--module'); p.add_argument('--min', type=float, default=5)
    p.add_argument('--top', type=int, default=15); p.set_defaults(f=cmd_gaps)

    p = common(sub.add_parser('compare', help='status-aware A/B of two jobs'), job=False)
    p.add_argument('run'); p.add_argument('a'); p.add_argument('b')
    p.add_argument('--top', type=int, default=20); p.add_argument('--min', type=float, default=30)
    p.set_defaults(f=cmd_compare)

    a = ap.parse_args()
    a.f(a)


if __name__ == '__main__':
    main()
