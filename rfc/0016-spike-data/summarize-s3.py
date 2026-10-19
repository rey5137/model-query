"""Validate S3 fixture assertions and regenerate detector/cost/fallback evidence."""
from pathlib import Path
import collections
import csv
import re

OUT = Path(__file__).resolve().parent
VERSIONS = ['6.6.58.Final', '7.4.11.Final']
STRATEGIES = ['SINGLE_TABLE', 'JOINED', 'TABLE_PER_CLASS']
VENDORS = ['H2', 'POSTGRESQL', 'MYSQL']


def write(name, header, rows):
    with (OUT / name).open('w') as f:
        writer = csv.writer(f, delimiter='\t', lineterminator='\n')
        writer.writerow(header)
        writer.writerows(rows)


confusion, timings, fallbacks = [], [], []
for version in VERSIONS:
    rows = list(csv.reader((OUT / f'full-run-{version}.tsv').open(), delimiter='\t'))
    s3 = [r for r in rows if r[5].startswith('S3.')]
    assert len(s3) == 459
    assert all(r[7] in ('PASS', 'P*') for r in s3), [r[:10] for r in s3 if r[7] == 'FAIL'][:3]
    for strategy in STRATEGIES:
        for vendor in VENDORS:
            detector = [r for r in s3 if r[4] == strategy and r[1] == vendor and r[5] == 'S3.1-3']
            assert len(detector) == 45
            cells = collections.Counter()
            for r in detector:
                expected = re.search(r'expectedNarrow=(true|false)', r[9])[1] == 'true'
                detected = re.search(r'detectedNarrow=(true|false)', r[9])[1] == 'true'
                cells[expected, detected] += 1
                assert expected == detected
                assert 'noJdbcBeforeExecution=true' in r[9] and '; values=' in r[9]
                costs = [int(re.search(key + r'=(\d+)', r[9])[1]) for key in
                         ('coldNs', 'warmNs', 'cachedNs')]
                assert all(x >= 0 for x in costs)
                timings.append([version, vendor, strategy, r[6], *costs])
            assert cells[True, True] == 19 and cells[False, False] == 26
            confusion.append([version, vendor, strategy, cells[True, True], cells[False, False],
                              cells[False, True], cells[True, False]])
            fallback = [r for r in s3 if r[4] == strategy and r[1] == vendor and r[5] == 'S3.4']
            assert len(fallback) == 6
            for r in fallback:
                assert 'lastPageDetected=true; earlyOneRowPageDetected=false' in r[9]
                fallbacks.append([version, vendor, strategy, r[6], r[9]])
write('s3-confusion.tsv', ['line', 'vendor', 'strategy', 'TP', 'TN', 'FP', 'FN'], confusion)
write('s3-cost.tsv', ['line', 'vendor', 'strategy', 'shape', 'coldNs', 'warmNs', 'cachedNs'], timings)
write('s3-fallback.tsv', ['line', 'vendor', 'strategy', 'shape', 'asserted'], fallbacks)
print('S3 detector, cost and fallback: PASS (918 observations; zero FP/FN)')
