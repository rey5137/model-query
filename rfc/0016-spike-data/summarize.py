"""Regenerate contextual matrices from the two complete, headerless spike recordings."""
from pathlib import Path
import collections
import csv

OUT = Path(__file__).resolve().parent
VERSIONS = ['6.6.58.Final', '7.4.11.Final']
VENDORS = ['H2', 'POSTGRESQL', 'MYSQL']
CORE = ['SINGLE_TABLE', 'JOINED', 'TABLE_PER_CLASS']
ROWS = {v: list(csv.reader((OUT / f'full-run-{v}.tsv').open(), delimiter='\t')) for v in VERSIONS}
KEY = lambda r: tuple(r[i] for i in (1, 3, 4, 5, 6))
INDEX = {v: {KEY(r): r for r in rows} for v, rows in ROWS.items()}
for v, rows in ROWS.items():
    assert all(len(r) == 11 and r[2] == v and r[7] in ('PASS', 'P*', 'FAIL') for r in rows)
    assert len(rows) == len(INDEX[v])
    assert set(r[1] for r in rows) == set(VENDORS)

def write(name, header, rows):
    with (OUT / name).open('w') as f:
        w = csv.writer(f, delimiter='\t', lineterminator='\n')
        w.writerow(header)
        w.writerows(rows)

keys = sorted(set().union(*(d.keys() for d in INDEX.values())))
comparison = [[*k, *[INDEX[v].get(k, [''] * 7 + ['NOT_RUN'])[7] for v in VERSIONS]] for k in keys]
write('cell-matrix.tsv', ['vendor', 'mode', 'mapping', 'probe', 'use', *VERSIONS], comparison)
write('line-parity.tsv', ['vendor', 'mode', 'mapping', 'probe', 'use', *VERSIONS],
      [r for r in comparison if r[-1] != r[-2]])

# These are conservative query-context envelopes, not a union of independent passing flags.
# Count statements have their own rows. Each candidate must have evidence on all six targets.
def predicate_uses(op):
    if op in ('isNull', 'isNotNull'):
        return 'NULL_TEST'
    if op in ('not(isNull)', 'not(isNotNull)'):
        return 'NULL_TEST+NEGATED'
    if op in ('ne', 'notIn'):
        return 'PREDICATE+NULL_TEST+DISJUNCTION'
    if op == 'not(eq)':
        return 'PREDICATE+NEGATED'
    return 'PREDICATE'

def context(r):
    p, u = r[5:7]
    if p == 'S2.1':
        return 'S2_ENGINE_COUNT', u.split('/', 1)[1]
    if p == 'S2.2':
        _, op, variant = u.split('/')
        return 'S2_FRESH_COUNT_' + variant, predicate_uses(op)
    if p == 'S2.3':
        shape, anchor = u.split('/')
        return 'S2_' + shape + '_' + anchor, 'SELECT' + ('+GROUP_BY' if shape == 'GROUP' else '')
    if p == 'S2.4':
        return 'S2_ENTITY_LEFT', u.split('/')[1]
    if p == 'S2.5':
        return 'S2_ROOT_RESTRICTION', u
    if p in ('2', '2-shared', '2-different'):
        return 'READ', {'GROUP_BY': 'SELECT+GROUP_BY'}.get(u, u.replace(',', '+'))
    if p == '2-coalesce':
        return 'READ', 'SELECT+EXPRESSION'
    if p in ('3-valueA', '3-shared', '3-differentA'):
        _, op, shape, enclosing = u.split('/')
        uses = set(predicate_uses(op).split('+'))
        if enclosing in ('or-base', 'or-sibling', 'exists-or'):
            uses.add('DISJUNCTION')
        if enclosing.startswith('exists'):
            uses.add('SUBQUERY')
        return 'COUNT' if shape == 'count' else 'READ', '+'.join(sorted(uses))
    if p == '6':
        return 'COUNT', '+'.join(sorted(('PREDICATE', 'NULL_TEST', 'DISJUNCTION')))
    if p == '6-derived':
        shape = u.split('/')[1]
        # A pruned treated selection/order can leave a narrowed SQM root with NO live TreatSite.
        return 'COUNT_DERIVED', 'FROM_' + shape
    if p == '2-expression':
        use, shape = u.split('/')
        return 'COUNT' if shape == 'count' else 'READ', '+'.join(sorted(
            ['EXPRESSION', use] + (['SELECT'] if use == 'GROUP_BY' else [])))
    if p == '10':
        _, op, operation = u.split('/')
        uses = predicate_uses(op).split('+')
        uses = [x for x in uses if x != 'PREDICATE'] + ['WRITE_PREDICATE']
        return operation.upper(), '+'.join(sorted(uses))
    if p == '5':
        if u.startswith('TYPE,PREDICATE/'):
            _, enclosing, shape = u.split('/')
            uses = 'TYPE+PREDICATE' + (
                '+LEFT_JOIN+NEGATED+NULL_TEST+DISJUNCTION' if enclosing == 'notOfType-left' else '')
            return 'COUNT' if shape == 'count' else 'READ', uses
        return 'READ', u.replace(',', '+')
    if p in ('3-count', '3-count-fk', '3-subquery-join'):
        return 'READ', 'SELECT+AGGREGATE+SUBQUERY' + (
            '+LEFT_JOIN' if p == '3-subquery-join' else '')
    if p in ('4', '4-null-view', '4-association-upcast', '4-from-treat'):
        return 'READ', u.replace(',PRESENCE', '').replace(',', '+')
    if p == '4-uses':
        kind, op, shape = u.split('/')
        uses = ['ORDER'] if op == 'ORDER' else predicate_uses(op).split('+')
        return 'COUNT' if shape == 'count' else 'READ', '+'.join(sorted([kind, *uses]))
    if p == '7':
        if u.startswith('EXPORT'):
            return 'READ', '+'.join(sorted(['PREDICATE', 'NULL_TEST', 'DISJUNCTION']))
        return 'READ', 'SELECT+ORDER+PREDICATE+NULL_TEST+DISJUNCTION'
    if p == '7-offset-order':
        return 'READ', 'ORDER'
    if p == '7-keyset-count':
        _, reverse, null_first, page = u.split('/')
        order = ([5, 4, 2] if reverse == 'true' else [2, 4, 5])
        values = [3, 1] if reverse == 'true' else [1, 3]
        order = order + values if null_first == 'true' else values + order
        cursor = order[min(int(page) * 2, 5) - 1]
        uses = ['NULL_TEST'] if cursor not in (1, 3) else ['PREDICATE', 'DISJUNCTION']
        if cursor not in (1, 3) and null_first == 'true':
            uses.append('DISJUNCTION')
        if cursor in (1, 3) and null_first == 'false':
            uses.append('NULL_TEST')
        return 'COUNT', '+'.join(sorted(uses))
    if p == '9' and u == 'SOURCE,SELECT':
        return 'READ', 'SOURCE+SELECT'
    if p == '9-context':
        use = u.split('/')[1]
        return 'READ', 'SOURCE+' + ('NULL_TEST' if use == 'PREDICATE' else 'SELECT+AGGREGATE')
    if p == '5-combined':
        enclosing, shape = u.split('/')
        return 'COUNT' if shape == 'count' else 'READ', 'COMBINED_TYPE_TREAT/' + enclosing
    return None

site_rows = []
groups = collections.defaultdict(list)
for version, rows in ROWS.items():
    for line, r in enumerate(rows, 1):
        c = context(r)
        if c:
            shape, uses = c
            if not uses.startswith(('FROM_', 'COMBINED_')):
                uses = '+'.join(sorted(set(uses.split('+'))))
            site_rows.append([version, *r[1:2], *r[3:8], shape, uses,
                              f'full-run-{version}.tsv:{line}'])
            if r[3] == 'default' and r[4] in CORE:
                groups[shape, uses, r[4]].append(r)
write('statement-matrix.tsv', ['line', 'vendor', 'mode', 'mapping', 'probe', 'raw_use', 'status',
                             'statement_shape', 'use_envelope', 'evidence'], site_rows)
allow = []
for shape, uses in sorted({(s, u) for s, u, _ in groups}):
    for mapping in CORE:
        rows = groups[shape, uses, mapping]
        covered = {(r[2], r[1]) for r in rows}
        failed = [r for r in rows if r[7] == 'FAIL']
        decision = 'ALLOW' if not failed and len(covered) == 6 else 'REFUSE'
        # Combined TYPE+treat has multiple individual sites; its union is not an SPI key.
        if uses.startswith('COMBINED_'):
            decision = 'REFUSE: composition unmodelled'
        if shape == 'COUNT_DERIVED' and uses in ('FROM_SELECT', 'FROM_EXPRESSION', 'FROM_ORDER'):
            decision = 'REFUSE: orphan treat after pruning'
        reason = ('; '.join(sorted({r[5] + '/' + r[6] for r in failed})) if failed else
                  'All recorded cases assert values on both lines and all three vendors')
        if len(covered) != 6:
            reason = 'Incomplete six-target coverage; ' + reason
        allow.append([shape, uses, mapping, decision, len(rows), len(failed), reason])
for mapping in CORE:
    allow.append(['READ', 'CORRELATION+SUBQUERY', mapping, 'REFUSE: unprobed', 0, 0,
                  'Own-root scalar counts correlate untreated outer id, not a treated outer node'])
# The current SPI cannot see statement shape. Refuse any indistinguishable failing set.
unsafe = {(r[1], r[2]) for r in allow if r[3].startswith('REFUSE')}
for r in allow:
    orphan = {'FROM_SELECT': 'SELECT', 'FROM_EXPRESSION': 'EXPRESSION+SELECT',
              'FROM_ORDER': 'ORDER'}.get(r[1])
    if orphan:
        unsafe.add((orphan, r[2]))
for r in allow:
    spi = 'REFUSE' if (r[1], r[2]) in unsafe or r[3].startswith('REFUSE') else 'ALLOW bounded contexts'
    if r[0].startswith('S2_'):
        spi = 'REFUSE: experimental statement rewrite needs engine design'
    r.append(spi)
write('renderer-allowlist.tsv', ['statement_shape', 'use_envelope', 'strategy', 'decision',
                                 'observations', 'failures', 'evidence_or_reason', 'current_SPI_decision'], allow)
# Refuse any unseen set, mapping variant, composition, or untested operator sharing a set.
print('Observations: ' + '; '.join(v + ' ' + str(dict(collections.Counter(r[7] for r in rows)))
                                 for v, rows in ROWS.items()))
print('Parity rows: ' + str(sum(r[-1] != r[-2] for r in comparison)))
print('Renderer candidates: ' + str(collections.Counter(r[3].split(':')[0] for r in allow)))
