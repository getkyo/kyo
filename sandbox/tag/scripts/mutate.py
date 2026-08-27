#!/usr/bin/env python3
"""Mutation run: each mutant disables one rule in TagMacro; the suite must go red for every one.

A mutant that survives means the suite has a blind spot (or the rule is dead code), and the run
fails. A compile failure of lib counts as red: most mutants turn an accepted cell into a refusal.
"""
import subprocess, sys, os, re

os.chdir(os.path.join(os.path.dirname(__file__), '..'))
MACRO = 'core/src/main/scala/kyo/internal/TagMacro.scala'
original = open(MACRO).read()

def first(old, new):
    def apply(s):
        assert old in s, f'pattern missing: {old!r}'
        return s.replace(old, new, 1)
    return apply

MUTANTS = {
    'M1 never refuse collapsed': first(
        'else scope.collect { case (sym, underlying) if bindUnderlying(underlying, node).isDefined => sym }',
        'else Nil'),
    'M2 skip given-in-scope check': first(
        '.find(sym => sym.flags.is(Flags.Given) && !sym.isClassDef)',
        '.find(_ => false)'),
    'M3 consult memo before scope check': first(
        '        if opaqueScope.isEmpty then\n            encodedCache.get(typeKey) match',
        '        if true then\n            encodedCache.get(typeKey) match'),
    'M4 wildcard bounds not unified': first(
        'case (TypeBounds(patternLow, patternHigh), TypeBounds(valueLow, valueHigh)) =>',
        'case (TypeBounds(patternLow, patternHigh), TypeBounds(valueLow, valueHigh)) if false =>'),
    'M5 unwalkable check off': first(
        'scope.find((_, underlying) => !walkable(underlying))',
        'scope.find(_ => false)'),
    'M6 skip every opaque node (old branch rule)': first(
        'if transparent.contains(node.typeSymbol) then Nil',
        'if node.typeSymbol.flags.is(Flags.Opaque) then Nil'),
    'M7 no extra members at the root': first(
        'Option.when(unify(pattern, node, true) && holes.forall(_.isDefined))',
        'Option.when(unify(pattern, node, false) && holes.forall(_.isDefined))'),
    'M8 arguments not descended': first(
        'args.foreach(arg => check(arg.dealiasKeepOpaques.simplified))',
        '()'),
    'M9 site-dependent member sort': lambda s: s.replace('.sortBy(_.dealiasKeepOpaques.show)', '.sortBy(_.show)'),
    'M12 given check only for vals': first(
        'case DefDef(_, _, tpt, _)    => Some(tpt.tpe)',
        'case DefDef(_, _, tpt, _)    => None'),
    'M13 unapplied constructor not matched': first(
        'case lambda: TypeLambda if lambda =:= node => Some(Nil)',
        'case lambda: TypeLambda if false => Some(Nil)'),
}

def run():
    p = subprocess.run(['sbt', '-batch', 'clean', 'lib/compile', 'tests/test'], capture_output=True, text=True)
    out = p.stdout + p.stderr
    red = p.returncode != 0
    m = re.search(r'(Passed|Failed): Total \d+, Failed \d+, Errors \d+, Passed \d+', out)
    detail = m.group(0) if m else ('compile failed' if 'Compilation failed' in out else 'no summary')
    # The negative project must fail to compile; compiling is red.
    n = subprocess.run(['sbt', '-batch', 'negative/clean', 'negative/compile'], capture_output=True, text=True)
    if n.returncode == 0:
        red = True
        detail += '; negative compiled'
    return red, detail

survivors = []
only = sys.argv[1:]
try:
    for name, mutate in MUTANTS.items():
        if only and not any(o in name for o in only):
            continue
        open(MACRO, 'w').write(mutate(original))
        red, detail = run()
        print(f"{'killed  ' if red else 'SURVIVED'}  {name}: {detail}", flush=True)
        if not red:
            survivors.append(name)
finally:
    open(MACRO, 'w').write(original)

if survivors:
    print('\nSURVIVING MUTANTS (blind spots or dead code):')
    for s in survivors:
        print('  ' + s)
    sys.exit(1)
print('\nall mutants killed')
