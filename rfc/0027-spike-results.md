# RFC 0027 acceptance spike: results

**1 Part A (basic and embeddable value children): keep, with one change.** Plain Criteria reads an element collection
as `multiselect(root.get("id"), root.join("tags"))` with `where id in (...)`, and the rows are right on H2, PostgreSQL and
MySQL, Hibernate 6.6.58 and 7.4.11 (all cells), and EclipseLink 4.0.8 and 5.0.2 (all but the cells in 3): duplicates kept,
the NULL element returned as `null`, an `@Convert`ed element read back converted, `@OrderColumn` order by `ListJoin.index()`
(rows seeded in the order 2, 0, 1 come back 0, 1, 2), a basic and a nested embedded path (`geo.lat`) and the whole
embeddable. The change: the statement needs `ListJoin` for `index()`, so the engine must call `joinList` (or cast the
`through` join) when the attribute has an `@OrderColumn`; `join(String)` returns a plain `Join`.
**2 Default order without `@OrderColumn` (Q1b): keep, with a change.** `order by root.id, element` is stable (3 runs each,
identical) but NULL placement is the vendor's: H2 and MySQL first, PostgreSQL last (ascending; reversed for `desc`),
on both Hibernate lines and EclipseLink. Duplicates are indistinguishable, so nothing else varies. The change: R-FCH-19
should say the NULL element's position is vendor-defined, or the engine adds an explicit null ordering (not portable in
JPA 3.1 Criteria; it is `nullsFirst`/`nullsLast` on 3.2).
**3 Part B (map children): keep for basic key and value; change for the rest.**
`MapJoin.key()`/`value()` in select and in `order by` are right for `Map<String,String>` on every lane. Three failures, all
data in the TSVs: (a) `MapJoin.entry()` in the select fails on Hibernate 6.6 and 7.4 (`ArrayIndexOutOfBoundsException`,
no SQL sent) and, on EclipseLink, returns the values without the keys: the RFC's `key()`+`value()` is the only usable
form (keep it, and say so). (b) A `Map<String, AddressValue>` (embeddable value) and a `Map<String, Entity>` read fine on
Hibernate with `order by key()`, but EclipseLink 4.0.8 and 5.0.2 render the order term as `t1.place_key.t1.city`, which
every vendor rejects; selecting without that order term works. (c) `@Convert(attributeName = "key")` on a map: see 5.
**4 Part C filters (Q1c): keep.** A correlated `exists` (`sq.correlate(root).join("tags")`), an uncorrelated second root
with entity equality and the join below it (the R-EXE-16 shape), the same second root correlated on `id`, and
`isMember`/`isEmpty` return the right parents, once each, on all lanes and vendors (also for the converted element, an
embeddable path, and a map value). A plain join returns a parent once per match (`'a'` gives order 1 twice), `distinct`
fixes the ids and `count(root)` over-counts (2, not 1), as R-EXE-16/R-EXE-04 already say for any to-many join.
Hibernate renders the second-root form as `exists (select 1 from orders o2 join order_tags t on o2.id=t.order_id where
o2.id=o.id and t.tag=?)`; the correlate form is `exists (select 1 from order_tags t where t.tag=? and o.id=t.order_id)`,
a plain correlated sub-query without the second `orders` scan. EclipseLink inlines the literal and puts the entity equality in
a second `orders` scan for both.
**5 Converters on a map (item b): keep on Hibernate, refuse on EclipseLink.** On Hibernate 6.6 and 7.4, a
`@Convert(converter = Prefix.class, attributeName = "key")` and a second on `"value"` (via `@Converts`) come back converted
from `key()` and `value()`, convert the bound literal in a filter on either, and order by the converted key correctly.
EclipseLink 4.0.8 and 5.0.2 refuse the unit at bootstrap (`EclipseLink-7355`: "not a valid mapping type for a convert
using an attribute name specification"), so a map key converter is Hibernate-only.
**6 Q2 (embeddable root): keep, no new kind.** `EntityMetamodel#walk` already resolves attribute paths from any declared
type, embeddeds included (`EntityMetamodel.java:155-176`), and `JoinContext#rootedAt(From, Class)` takes any `From`
(`JoinContext.java:248`; `ModelQuery.java:666`). The blocker is `ModelValidator.java:373-382` (`rootKindOk`, `MQ3038`:
root must be `@Entity` or `@SourceModel`) and `QueryModelReader.java:367`. `TableField.java:296-306` passes with the join's
element class as `rootType`, and `ColumnField.java:505-517` finds the managed type from the join's element type.
**7 Q3 (filter column through a to-many): keep, reuse R-EXE-16.** Today `FilterLayout.java:241-246` refuses a collection
step unless `joinType` is explicit (`MQ3011`); with one, the join is made and R-EXE-16 renders a filter-only to-many join
as a semi-join over a second root (`ModelQuery.java:1182-1200`), which Q1c shows is correct on an element collection too.

Proposed RFC changes: R-FCH-14's text (`api/15`:123-130) says a `through` path crossing a non-association or an embedded
value is `MQ3406` and that a to-many child needs a `@PrimaryKey` (`api/15`:89): both need an element-collection exception.
State the vendor-defined NULL position (2), drop `entry()` (3a), say a map key/value converter is Hibernate-only (5)
and that an embeddable or entity map value ordered by key is not EclipseLink-portable (3b).

Spike on `spike/rfc-0027`: test-only module `spike-rfc0027`, data in [`0027-spike-data/`](0027-spike-data/README.md);
`matrix.tsv` has every cell by lane and vendor.

**Versions.** H2 2.5.252, PostgreSQL 16 (`postgres:16-alpine`), MySQL 8.0.31; Hibernate 6.6.58.Final (API 3.1.0) and
7.4.11.Final (API 3.2.0); EclipseLink 4.0.8 (API 3.1.0) and 5.0.2 (API 3.2.0), run in-process with weaving off.
Results: Hibernate 41 PASS and 2 FAIL per vendor and line (both `entry()`); EclipseLink 33 PASS and 6 FAIL per vendor and
version (`entry()` x2, the three embeddable/entity map cells, the converted-map bootstrap, whose cells then did not run).

**What is emulated.** Only plain JPA Criteria runs: no `ModelQuery`, no generated `Q*` classes. The probes build what the
engine would; the data is seeded over JDBC so the NULL element and the out-of-order positions are exact. Q2 and Q3 are
code reading only.
