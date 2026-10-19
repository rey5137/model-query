from pathlib import Path
import csv
import collections
root = Path.cwd()
out = root / 'rfc/0016-spike-data'
versions = ['6.6.58.Final', '7.4.11.Final']
core = ['SINGLE_TABLE', 'JOINED', 'TABLE_PER_CLASS']
rows = {v: list(csv.reader((out / f'full-run-{v}.tsv').open(), delimiter='\t')) for v in versions}
for v, rr in rows.items():
    index = {(r[1], r[3], r[4], r[5], r[6]): r for r in rr}
    assert len(index) == len(rr)
    assert all(len(r) == 11 and r[2] == v for r in rr)
    assert all(not r[10] for r in rr if r[9].startswith('java.lang.ClassCastException'))
    for vendor in ['H2', 'POSTGRESQL', 'MYSQL']:
        for strategy in core:
            def row(probe, use):
                return index[vendor, 'default', strategy, probe, use]
            def passes(probe, use):
                assert row(probe, use)[7] in ('PASS', 'P*'), row(probe, use)
            for probe, use in [('3-count', 'SELECT,SUBQUERY,AGGREGATE,CORRELATION/ID'),
                               ('3-count-fk', 'SELECT,SUBQUERY,AGGREGATE,CORRELATION'),
                               ('3-subquery-join', 'SELECT,SUBQUERY,AGGREGATE,CORRELATION,LEFT_JOIN'),
                               ('4', 'INNER_JOIN,SELECT,PRESENCE'),
                               ('4-null-view', 'LEFT_JOIN,SELECT,PRESENCE'),
                               ('4-association-upcast', 'LEFT_JOIN,SELECT'),
                               ('4-supertype-nested', 'LEFT_JOIN,SELECT'),
                               ('6-derived', 'COUNT_FROM/TYPE'),
                               ('6-derived', 'COUNT_FROM/LEFT_JOIN'),
                               ('6-derived', 'COUNT_FROM/INNER_JOIN'),
                               ('7-offset-order', 'ORDER')]:
                passes(probe, use)
            for use in ['SELECT', 'EXPRESSION', 'ORDER']:
                r = row('6-derived', 'COUNT_FROM/' + use)
                assert r[7] == 'FAIL' and 'expected=5; actual=3' in r[9]
            for op in ['isNull', 'not(isNotNull)']:
                r = row('3-valueA', f'PREDICATE/{op}/count/alone')
                assert r[7] == 'FAIL' and 'expected=[3]; actual=[1]' in r[9]
            for ctx in ['or-base', 'or-sibling', 'exists', 'exists-or']:
                for op in ['eq', 'isNull', 'ne', 'not(isNotNull)']:
                    passes('3-valueA', f'PREDICATE/{op}/count/{ctx}')
            r = row('5', 'TYPE,ORDER')
            assert (r[7] == 'FAIL') == (strategy == 'JOINED')
            if strategy != 'JOINED':
                assert r[9] == '[1, 2, 4, 5, 3]'
            for reverse in ['false', 'true']:
                for nf in ['false', 'true']:
                    for page in ['1', '2', '3']:
                        passes('7-keyset-count', f'COUNT/{reverse}/{nf}/{page}')
            if strategy != 'SINGLE_TABLE':
                for operation in ['update', 'delete']:
                    for op in ['isNull', 'ne', 'not(isNotNull)']:
                        assert row('10', f'WRITE_PREDICATE/{op}/{operation}')[7] == 'FAIL'
        for attr in ['shared', 'different']:
            assert index[vendor, 'default', 'SINGLE_TABLE', '2-' + attr, 'SELECT'][7] in ('PASS', 'P*')
        for strategy in ['SECONDARY_OPTIONAL', 'SECONDARY_REQUIRED']:
            assert index[vendor, 'default', strategy, '11-missing-secondary', 'SELECT/content'][7] == 'P*'
        hidden = index[vendor, 'default', 'RESTRICTION', '11-bootstrap', 'MAPPING']
        if v.startswith('6'):
            assert hidden[7] == 'PASS'
            assert index[vendor, 'default', 'RESTRICTION', '11-hidden', 'ROOT_CONTROL'][7] == 'PASS'
            for ctx in ['SUBTYPE', 'TREAT']:
                assert index[vendor, 'default', 'RESTRICTION', '11-hidden', ctx][7] == 'FAIL'
        else:
            assert hidden[7] == 'FAIL' and 'AnnotationException' in hidden[9]
# No vendor-only status split in this dataset, after eliminating vendor from each key.
for v, rr in rows.items():
    status = collections.defaultdict(set)
    for r in rr:
        status[tuple(r[3:7])].add('FAIL' if r[7] == 'FAIL' else 'PASS')
    assert all(len(x) == 1 for x in status.values())
allow = list(csv.DictReader((out / 'renderer-allowlist.tsv').open(), delimiter='\t'))
for r in allow:
    if r['decision'] == 'ALLOW':
        assert int(r['failures']) == 0 and int(r['observations']) >= 6
for file in [root / 'model-query-tck/src/test/java/com/rey/modelquery/tck/spike/Rfc16SpikeTest.java',
             root / 'model-query-tck/src/test/java/com/rey/modelquery/tck/spike/Rfc16Entities.java',
             root / 'rfc/0016-spike-results.md', out / 'README.md', out / 'summarize.py']:
    for n, line in enumerate(file.read_text().splitlines(), 1):
        assert line.rstrip() == line, (file, n, 'trailing whitespace')
        if file.suffix == '.java' or (file.suffix == '.md' and not line.startswith('|') and not line.startswith('* ')):
            assert len(line) <= 120, (file, n, len(line))
print('RFC 0016 data and width checks: PASS')
