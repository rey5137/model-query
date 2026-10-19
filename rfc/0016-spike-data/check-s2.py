"""Check S2 coverage and its asserted findings against the raw six-target recordings."""
from pathlib import Path
import csv

out = Path(__file__).resolve().parent
for version in ('6.6.58.Final', '7.4.11.Final'):
    rows = list(csv.reader((out / f'full-run-{version}.tsv').open(), delimiter='\t'))
    s2 = [r for r in rows if r[5].startswith('S2.')]
    assert len(s2) == 414, (version, len(s2))
    index = {(r[1], r[4], r[5], r[6]): r for r in s2}
    assert len(index) == 414
    for vendor in ('H2', 'POSTGRESQL', 'MYSQL'):
        for strategy in ('SINGLE_TABLE', 'JOINED', 'TABLE_PER_CLASS'):
            cells = [r for r in s2 if r[1] == vendor and r[4] == strategy]
            assert len(cells) == 42
            for r in cells:
                probe, use = r[5:7]
                fail = probe == 'S2.1'
                if probe == 'S2.2':
                    fail = use.split('/')[-1] in ('ROOT', 'STAR')
                if probe == 'S2.3':
                    fail = use.endswith('false')
                assert (r[7] == 'FAIL') == fail, r[:10]
                if fail:
                    assert r[9].startswith('java.lang.AssertionError: expected='), r[:10]
                    assert r[10], r[:10]
            for mapping in ('ROOT_RESTRICTION_ST', 'ROOT_RESTRICTION_JOINED', 'ROOT_RESTRICTION_TPC'):
                restricted = [r for r in s2 if r[1] == vendor and r[4] == mapping]
                assert len(restricted) == 4
                assert all(r[7] in ('PASS', 'P*') for r in restricted)
print('S2 coverage and findings: PASS (828 observations, six targets)')
