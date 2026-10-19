# RFC 0016 acceptance spike: corrected results

Throwaway research on `spike/rfc-0016`; no product engine, processor or public SPI changes. The same test source
runs on Hibernate 6.6.58.Final and 7.4.11.Final (Jakarta Persistence 3.2.0), sequentially against H2,
PostgreSQL 16 (`postgres:16-alpine`) and MySQL 8.0.31 (`mysql:8.0.31`). This replaces the earlier conclusions.
Every PASS/P* asserts values, ids, counts, affected rows or full membership. Maven records provider failures in
TSV rather than failing JUnit. P* marks SQL containing join/CASE/UNION; elapsed times are not cost benchmarks.

The complete headerless [raw data](0016-spike-data/README.md) retains SQL and assertion errors. Translation
failures have empty SQL when nothing reached JDBC. The [cell matrix](0016-spike-data/cell-matrix.tsv) is keyed by
vendor × mode × strategy/mapping × probe × use × line. The [statement matrix](0016-spike-data/statement-matrix.tsv)
also separates READ, COUNT, derived COUNT, UPDATE and DELETE and links each row to raw evidence. Its use envelopes
are conservative query-context groupings, not a claim that independent passing sites compose safely.

## Matrix

Numbers are P / P* / F observations, including all core strategies in default mode. The complete matrix also
contains settings and mapping variants; a missing query after mapping bootstrap is NOT_RUN, never PASS.

| Probe family | H2 6.6 | H2 7.4 | PG 6.6 | PG 7.4 | MySQL 6.6 | MySQL 7.4 |
| --- | --- | --- | --- | --- | --- | --- |
| 2 scalar / expression / order | 4 P / 8 P* / 0 F | 4 P / 8 P* / 0 F | 4 P / 8 P* / 0 F | 4 P / 8 P* / 0 F | 4 P / 8 P* / 0 F | 4 P / 8 P* / 0 F |
| 2 group / aggregate | 0 P / 0 P* / 6 F | 0 P / 0 P* / 6 F | 0 P / 0 P* / 6 F | 0 P / 0 P* / 6 F | 0 P / 0 P* / 6 F | 0 P / 0 P* / 6 F |
| 2 shared column, both attribute shapes | 1 P / 6 P* / 4 F | 1 P / 6 P* / 4 F | 1 P / 6 P* / 4 F | 1 P / 6 P* / 4 F | 1 P / 6 P* / 4 F | 1 P / 6 P* / 4 F |
| 2 expression in predicate / order / group | 3 P / 6 P* / 9 F | 3 P / 6 P* / 9 F | 3 P / 6 P* / 9 F | 3 P / 6 P* / 9 F | 3 P / 6 P* / 9 F | 3 P / 6 P* / 9 F |
| 3 predicate content + count, all contexts | 176 P / 704 P* / 20 F | 176 P / 704 P* / 20 F | 176 P / 704 P* / 20 F | 176 P / 704 P* / 20 F | 176 P / 704 P* / 20 F | 176 P / 704 P* / 20 F |
| 3 correlated scalar counts + treated subquery join | 2 P / 7 P* / 0 F | 2 P / 7 P* / 0 F | 2 P / 7 P* / 0 F | 2 P / 7 P* / 0 F | 2 P / 7 P* / 0 F | 2 P / 7 P* / 0 F |
| 4 joins / presence / association models | 3 P / 78 P* / 0 F | 3 P / 78 P* / 0 F | 3 P / 78 P* / 0 F | 3 P / 78 P* / 0 F | 3 P / 78 P* / 0 F | 3 P / 78 P* / 0 F |
| 5 TYPE + treat/TYPE compositions | 16 P / 37 P* / 7 F | 16 P / 37 P* / 7 F | 16 P / 37 P* / 7 F | 16 P / 37 P* / 7 F | 16 P / 37 P* / 7 F | 16 P / 37 P* / 7 F |
| 6 standalone treated count | 0 P / 0 P* / 3 F | 0 P / 0 P* / 3 F | 0 P / 0 P* / 3 F | 0 P / 0 P* / 3 F | 0 P / 0 P* / 3 F | 0 P / 0 P* / 3 F |
| 6 genuinely derived counts | 4 P / 5 P* / 9 F | 4 P / 5 P* / 9 F | 4 P / 5 P* / 9 F | 4 P / 5 P* / 9 F | 4 P / 5 P* / 9 F | 4 P / 5 P* / 9 F |
| 7 keyset + offset + export, own count sites | 18 P / 36 P* / 0 F | 18 P / 36 P* / 0 F | 18 P / 36 P* / 0 F | 18 P / 36 P* / 0 F | 16 P / 38 P* / 0 F | 16 P / 38 P* / 0 F |
| 8 subtype roots / locks / tables | 6 P / 1 P* / 0 F | 6 P / 1 P* / 0 F | 6 P / 1 P* / 0 F | 6 P / 1 P* / 0 F | 6 P / 1 P* / 0 F | 6 P / 1 P* / 0 F |
| 9 CTE / union / predicate / aggregate | 2 P / 7 P* / 3 F | 2 P / 7 P* / 3 F | 2 P / 7 P* / 3 F | 2 P / 7 P* / 3 F | 2 P / 7 P* / 3 F | 2 P / 7 P* / 3 F |
| 10 update / delete, 18 operators | 0 P / 56 P* / 52 F | 0 P / 56 P* / 52 F | 0 P / 56 P* / 52 F | 0 P / 56 P* / 52 F | 0 P / 56 P* / 52 F | 0 P / 56 P* / 52 F |
| 12 unknown discriminator / duplicate id | 4 P / 1 P* / 0 F | 4 P / 1 P* / 0 F | 4 P / 1 P* / 0 F | 4 P / 1 P* / 0 F | 4 P / 1 P* / 0 F | 4 P / 1 P* / 0 F |

| S3 follow-up (P/P*/F) | 49/104/0 | 49/104/0 | 49/104/0 | 49/104/0 | 49/104/0 | 49/104/0 |
| S2 follow-up (P/P*/F) | 23/64/51 | 23/64/51 | 23/64/51 | 23/64/51 | 23/64/51 | 23/64/51 |

Full runs (including mapping variants/settings):

* 6.6.58.Final: 17658 observations; 4288 P / 12038 P* / 1332 F.
* 7.4.11.Final: 17028 observations; 3700 P / 12023 P* / 1305 F.

## Notes per probe

Cell shorthand below is `probe / raw_use`; unless qualified, it means default mode, all three core strategies,
all three vendors and both lines. A reported PASS includes P*. SQL is in the raw row, not inferred from status.

### 1. Fixtures and controls

Each hierarchy has A ids 1,2; grandchild C id 3; sibling B ids 4,5. `valueA` is alpha,NULL,gamma,NULL,NULL.
Peers are 1→1, 2→4 (sibling), 3→absent, 4→3 (grandchild), 5→2 (present A with NULL value).
SINGLE_TABLE has both same-named sibling `shared` attributes and differently named `differentA`/`differentB`
attributes on one physical column; B's attribute is read-only. Native fixture control `2-different-fixture /
PHYSICAL_COLUMN` asserts physical beta at B id 4, so negative predicate probes can detect real leakage.
All mutating probes roll back; factories and containers close through the existing TCK lifecycle.

### 2. Treated values, expressions, grouping and shared columns

`2 / SELECT`, `SELECT,EXPRESSION`, `ORDER` preserve all five ids and NULL-pad siblings. JOINED's scalar select
LEFT-joins A; TABLE_PER_CLASS uses padded UNION ALL. `2 / GROUP_BY` loses siblings: NULL group is 1, expected 3.
`2 / SELECT,AGGREGATE` returns `[2,gamma,3]`, expected `[2,gamma,5]`. Both fail in every core cell.
A plain id+treated-value SELECT preserves rows; grouping or aggregating the same read narrows the root.
JOINED rendering is LEFT for SELECT, SELECT+EXPRESSION, ORDER and plain predicate content; INNER for GROUP_BY,
SELECT+AGGREGATE and standalone predicate COUNT. OR-base/OR-sibling COUNT and EXISTS retain LEFT subtype joins;
the valid own-root scalar correlated count also retains LEFT. All these join shapes agree across vendors/lines.

Neither shared-column fixture leaks B's beta: `2-shared / SELECT` and `2-different / SELECT` produce NULL for
ids 4,5 on both lines/all vendors. Both render CASE guards. This is evidence for these two mappings, not a promise
for every shared-column mapping. `3-differentA` and `3-shared` additionally use eqBeta/inBeta/likeBeta; expected
matches are empty. Ordinary alpha-only eq/in/like would not detect beta leakage. Shared GROUP_BY/AGGREGATE still
narrow and fail. `2-coalesce` across two subtype paths preserves all values.

`2-expression / PREDICATE/content` and `ORDER/content` pass; corresponding derived counts are 1/3 and 3/5.
`GROUP_BY/content` gives missing=1 instead of 3. Its count=3 correctly counts group keys, which does not validate
the incorrect histogram. The failing content prevents admitting that composition despite its passing count.

### 3. Predicates, disjunction, EXISTS and valid correlated counts

18 operator shapes run over valueA (plus both shared shapes on ST), each as content and COUNT in five contexts:
alone, or-base, or-sibling, exists, exists-or. D5 ne/notIn explicitly include NULL; not(eq) uses SQL three-valued
NOT. gt/ge/lt/le/between and not(isNotNull) are measured, not inferred from eq.

`3-valueA / PREDICATE/isNull/count/alone` returns 1, expected 3; ne/notIn return 2, expected 4;
not(isNotNull) returns 1, expected 3. Content passes. not(isNull) counts 2 correctly, but shares the exact
`{NULL_TEST,NEGATED}` set with failing not(isNotNull): the undifferentiated count set is refused.
isNotNull likewise cannot authorize `{NULL_TEST}` because isNull fails it.

OR-base, OR-sibling, EXISTS and EXISTS-OR counts **pass**, including eq OR id=4 (2), isNull (3), and ne (4).
The review's proposed OR undercount is not reproduced. These results do not authorize the standalone count shape.
Each count derives its own sites; content SELECT or ORDER uses are not copied into a count's use set.

The old no-FROM aggregate was invalid evidence and has been removed. `3-count /
SELECT,SUBQUERY,AGGREGATE,CORRELATION/ID` has its own root and `inner.id = outer.id`, asserting
`[1,0,1,0,0]`. `3-count-fk` correlates `inner.peer.id = outer.id`, asserting `[1,0,0,0,0]`.
Both pass on every core strategy/vendor/line. `3-subquery-join` counts a LEFT treated target from its own correlated
root, asserting `[1,0,0,1,0]`; it also passes. These cover RFC 0015 R-COL-31's scalar-subquery shape.
These are SELECT+AGGREGATE+SUBQUERY sites on the inner treated read: the correlated outer id is untreated.
A genuinely treated outer correlation site `{SUBQUERY,CORRELATION}` is still unprobed/refused. The raw
CORRELATION label describes query shape and does not authorize that different SPI site.
The previous H2-only CORRELATION allowance and PostgreSQL/MySQL MQ4015 facts are withdrawn. Compliance mode
rejects SELECT subqueries, and is separately gated off as RFC §10 already requires.

### 4. Treated joins, presence and real association models

`4 / LEFT_JOIN,SELECT,PRESENCE` asserts `[owner,target,value]` for all five owners. Sibling owner2 has NULL target
id; owner5 has target id2 with NULL value. `4-null-view` selects only valueA plus a presence expression, proving
absent `[NULL,false]` differs from present all-NULL projected value `[NULL,true]`.
The type restriction remains inside the target/ON side, preserving LEFT owners on all core strategies.
INNER retains owners 1,4,5, including the present NULL-valued target.

`4-uses` exercises LEFT and INNER separately with eq/isNull/ne/not(isNotNull), ORDER, and each derived COUNT.
Every core cell passes: LEFT isNull owners `[2,3,5]`, INNER `[5]`; LEFT ne `[2,3,4,5]`, INNER `[4,5]`.
LEFT order is `[2,3,5,1,4]`, INNER `[5,1,4]`; counts are 5 and 3. These count sites are independent of selection.

`4-association-upcast` joins a real `@ManyToOne` to the base and reads id/label/value through A, asserting all
nested values/presence. `4-supertype-nested` roots at A and reads its base-typed peer, including B id4's label.
Both pass on every core cell. `4-upcast` remains explicitly only a subtype-root inherited-field control.
`4-from-treat` checks navigation from a treated owner; it does not substitute for the association probes.

### 5. TYPE, ofType, ordering, grouping and treat compositions

Root TYPE select returns actual Classes; literal/parameter ofType includes C, exact A excludes C, and an abstract
base list includes all five. Each predicate has its own COUNT; core cells pass. TYPE grouping asserts A=2,B=2,C=1.
LEFT notOfType includes the absent peer (owners2,3); its content/count pass. Selecting TYPE of an absent LEFT peer
throws discriminator-resolution errors on all core cells and is refused.

TYPE ordering asserts the complete sequence `[1,2,4,5,3]` and repeatability with id tie-breaker: A,A,B,B,C.
ST uses its discriminator; TPC uses synthetic clazz_. JOINED without a discriminator emits `order by case end`
and fails on every vendor/line. implicit-for-joined enables a discriminator and passes. Explicit joined kind
passes unless ignore-explicit removes it; implicit+ignore restores Jn, but Jd remains discriminator-free there.
The null/not-null marker fixture records PostgreSQL order `[3,1,2,4,5]` (NULL last), while H2/MySQL
record `[1,2,4,5,3]` (NULL first). This is a recorded default null-order difference, not a TYPE failure.
This records actual order rather than merely checking membership.

`5-combined / SELECT/content` verifies value plus Class for all rows; its derived count is 3, expected 5.
AND TYPE(A,C) with valueA isNull yields id2/count1; OR valueA=alpha with TYPE(B) yields ids1,4,5/count3.
Those two predicate compositions pass; arbitrary compositions remain refused.

### 6. Counts derived from real content queries

`6-derived` first asserts actual content tuples/order and calls Hibernate Criteria `createCountQuery()` on that
same query, rather than constructing an unrelated no-treat count. SELECT, EXPRESSION and ORDER yield count3
for five content rows in every core cell. A pruned projection/order still leaves narrowing in the SQM root.
This replaces the vacuous SELECT_ONLY_PRUNED PASS and directly measures the previously unmeasured premise.
A product engine that rebuilds a fresh no-treat count may avoid this, but that rewrite is not implemented/proven here.

LEFT_JOIN content includes actual treated values and presence; its derived count=5 passes. SQL may legitimately
prune the unused LEFT join. INNER_JOIN content has owners1,4,5; derived count=3 passes and preserves the restriction.
TYPE-only selection derives count5 and passes. No unused treated root contaminates the TYPE/join count controls.
`6 / COUNT,PREDICATE,NULL_TEST,DISJUNCTION` independently reproduces D5 ne's 2/4 undercount.

### 7. Paging and export

All four direction/null-precedence combinations page by treated value+id through pages2+ and NULL boundaries,
asserting the complete sequence once per id. Their use envelope includes SELECT because they project valueA.
`7-keyset-count` builds each later page's COUNT from its own seek predicate without SELECT/ORDER sites; all 108
cells per line pass, including counts3,1,0 and cursor-NULL branches. This does not rescue other count shapes that
share some uses. `7-offset-order` asserts `[2,4,5,1,3]` across offsets0/2/4; treated-ne id export asserts `[2,3,4,5]`.
Duplicate assigned ids in TPC are observed by `7-duplicate`; the engine's MQ2213 guard is not implemented here.
Sibling cursor fingerprint rejection MQ2209 is unprobed/refused: it requires the product cursor model.

### 8. Subtype roots, metadata and locks

A-root content/count/keyset includes ids1,2,3 and no B. ST subtype row locking works. Persister table metadata
shows JOINED/TPC involve multiple physical tables; actual product MQ4010 enforcement is outside this spike.

### 9. Sources, unions and subqueries

A CTE body selecting a treated value preserves all five rows; `9-context / SOURCE/PREDICATE` with isNull retains
ids2,4,5. `SOURCE/AGGREGATE` returns root count3 instead of 5 and is refused. These requested extra contexts ran.
`9 / SOURCE,UNION_ALL,SUBTYPE` asserts complete membership and discriminator tags for A(including C) unionAll B,
also over TPC's internally unioned subtype branch. Every core cell passes on both lines/all vendors.
Subquery SELECT/aggregate and treated join are covered by the correlated scalar probes in §3; arbitrary additional
subquery/source combinations have no evidence and stay refused.

### 10. Bulk writes

Both update and delete test all 18 operators and roll back. ST throws ClassCastException during SQL translation
for every operator on both lines/all vendors. JOINED/TPC scalar comparisons and not(eq) pass, but isNull and
not(isNotNull) affect1 instead of expected ids2,4,5 (3); D5 ne/notIn affect2 instead of ids2,3,4,5 (4).
The null-accepting write use sets are refused. not(isNull)'s isolated PASS cannot authorize the same set as
not(isNotNull). WRITE_PREDICATE is its own bulk-where use with NULL_TEST/NEGATED/DISJUNCTION as applicable.

### 11. Mapping variants and physically missing secondary rows

JOINED explicit discriminator/settings, formula, null/not-null discriminator markers, force, optional/required
secondary tables, subtype SQLRestriction and compliance are all recorded. Ordinary joined/formula/force variants
share the shape-dependent group/count failures; their passing scalar cells cannot authorize arbitrary variant use.
NULL_NOT_NULL additionally misclassifies the treated INNER sibling target and has TYPE marker/type errors.
Compliance rejects scalar SELECT subqueries; RFC §10 makes the renderer absent (MQ4014) regardless of other passes.

Both optional=true and optional=false secondary scalar SELECTs use LEFT joins. `11-missing-secondary` physically
deletes A id1's secondary row in a rolled-back transaction and asserts all five roots with id1's NULL value.
Both pass. NULL_TEST count then returns2, expected4. Thus optional=false does not force INNER in this read shape,
including a genuinely absent secondary row; neither mapping is broadly admitted.

On 6.6 a hidden-labelled subtype A id6 is actually inserted. `11-hidden / SUBTYPE` incorrectly returns it and
`TREAT` exposes secret-row rather than NULL: subtype SQLRestriction is ignored. `ROOT_CONTROL` has two physical
rows and actually hides its hidden-labelled row, proving a working 6.6 root restriction fixture. This control
does not establish subtype support. On 7.4 subtype SQLRestriction fails mapping bootstrap with AnnotationException
on every vendor; downstream cells are NOT_RUN. No empty fixture is counted as proof of filtering.

### 12. Unknown discriminator rows

The fixture explicitly removes only discriminator enum CHECKs before inserting unmapped discriminator data.
On all vendors/lines, notOfType retains the unknown id, ofType drops it, and typeOf throws HibernateException
with Unknown/Unrecognized discriminator value. No provider's checked DDL is silently assumed to accept that row.

### 13. Line parity and the seven acceptance answers

[Line parity](0016-spike-data/line-parity.tsv) has 633 rows: three subtype-restriction bootstrap
changes and 630 NOT_RUN downstream rows. There is no other PASS/P*/FAIL drift.

1. No sibling leakage in either same-named or differently named shared-column fixture; both guard with CASE.
2. Treated LEFT sibling target is absent (NULL id); owner5 proves present target id2 with all-NULL value projection.
3. JOINED scalar reads LEFT-join; count/group/aggregate narrow. Both secondary optionalities preserve missing rows.
4. TYPE root select/predicate/group passes; JOINED TYPE order needs a discriminator; marker/restriction variants refuse.
5. Unknown notOfType retains, ofType drops, typeOf throws on all six vendor×line combinations.
6. TYPE order recorded; engine/fresh-root counts fail, anchored/entity counts pass; keyset/offset/CTE union values pass.
7. Core parity holds; subtype SQLRestriction accepts-but-ignores on6.6, bootstrap-rejects on7.4.

## Follow-up probes (S2)

S2: each line records 69 P / 192 P* / 153 F; all 153 failures are asserted contract mismatches.
There is no S2 line/vendor status split. S2 repeats on both lines, all three vendors and all three core strategies: 42
cells per strategy/target,
plus four root-restriction cells for each strategy (138 per vendor, 414 per line). Every successful cell
asserts counts, full projection membership, groups or values. The S2 count oracle follows the independently
asserted content ids: five unfiltered, three for isNull/not(isNotNull), four for NULL-inclusive ne/notIn.

| Probe | Finding on all six targets and every core strategy |
| --- | --- |
| S2.1 engine replacement | Six failures: SELECT/ORDER count3 vs5; isNull/not(isNotNull)1 vs3; ne/notIn2 vs4 |
| S2.2 fresh count(root) / count(*) | Both fail all four NULL-accepting predicates with the same undercount |
| S2.2 fresh count(id) | All four predicates pass without an extra conjunct |
| S2.2 untreated id IS NOT NULL anchor | count(root), count(id) and count(*) all pass all four predicates |
| S2.3 keyless SELECT / CTE | Treated-only gives3 vs5; untreated selected id restores5 |
| S2.3 GROUP BY treated key | NULL group1 vs3; adding min(untreated id) restores NULL group3, alpha1, gamma1 |
| S2.4 LEFT entity join on id | SELECT, isNull, NULL-inclusive ne, isNull COUNT, GROUP BY and aggregate all pass |
| S2.5 root SQLRestriction | TREAT projection/isNull, expanded ofType and exact typeOf pass on ST/JOINED/TPC |

S2.1 builds a content-shaped Criteria query with its own treated selection/order registrations, then clears
order and replaces selection with count(root), **before any execution**, exactly as countRows does. It does
not use createCountQuery. Executing content first on that same mutable query is not this engine path and can
reuse its cached content plan; that harness mistake was corrected before the final two complete runs.
S2.2 uses a new query/root and only its predicates. count(*) uses HibernateCriteriaBuilder.count(), whose SQL
is verified in the raw data. Thus **fresh count alone does not fix counts**. The untreated id read is material:
JOINED count(root) alone INNER-joins the subtype, while count(id) or the id predicate keeps its LEFT join.

S2.3's group anchor is min(root.id), selected alongside the original treated group key/count; it keeps the
original grouping, rather than adding id to GROUP BY and changing its cardinality. Its values are alpha→1,
NULL→2, gamma→3, with counts 1/3/1. A selected id makes CONTENT and CTE bodies safe in these exact shapes;
an aggregate id anchor makes GROUPED safe in this tested shape. Neither proves arbitrary compositions safe.

S2.4 uses Hibernate's JpaFrom.join(Class, SqmJoinType.LEFT), shared by both lines, with subtype.id = root.id
in ON. Sibling ids are NULL while subtype id2 remains present with a NULL value. The aggregate asserts
count(root)=5, count(value)=2, max(value)=gamma. This is a viable alternative rendering for the measured root
scalar cases, including previously refused GROUP/aggregate and NULL-accepting counts. It needs an engine
rendering decision: no generic treated association, repeated join sharing, performance or unseen composition
is approved here. The spike changes no engine/SPI. S2.5 hides B row5 with a root-level label restriction;
all four visible ids survive treat/typeOf, isNull keeps2/4, ofType(A,C) keeps1/2/3, and row5 never appears.

The updated renderer table includes S2 construction/anchor provenance separately. Its empirical ALLOW rows
identify safe rewrites; their current_SPI_decision remains REFUSE pending engine design. Existing cb.treat
refusals stand: a fresh count by itself is insufficient, and per-read TreatSite cannot require an anchor or
choose an entity join. All S2 failures are line-wide (MQ4016), with no vendor-only MQ4015 split or line drift.
Root SQLRestriction support here does not remove the separate subtype SQLRestriction mapping gate.

## Follow-up probe (S3)

### S3.1: pre-execution translation and stability

Yes: the Hibernate module can obtain a translated `SelectStatement` before executing the Criteria query.
No product module changes were made. The same source compiles on both lines, using this path:

```text
Session.createQuery(CriteriaQuery)
  -> unwrap(SqmQuery.class).getSqmStatement() -> SqmSelectStatement
  -> unwrap(QueryImplementor.class).getParameterBindings()/getSession().getLoadQueryInfluencers()
SessionFactoryImplementor.getQueryEngine().getSqmTranslatorFactory()
  -> createSelectTranslator(statement, queryOptions, DomainParameterXref.from(statement),
       parameterBindings, loadQueryInfluencers, creationContext, true).translate().getSqlAst()
SelectStatement -> QueryPart.visitQuerySpecs -> FromClause.roots -> TableGroup
  -> TableGroupJoin / TableReferenceJoin; QuerySpec.whereClauseRestrictions.accept(walker)
```

`creationContext` is the session factory itself on 6.6. On 7.4 it is
`SessionFactoryImplementor.getSqlTranslationEngine()`; the shared source invokes that accessor reflectively.
The query implementation is `org.hibernate.query.sqm.internal.QuerySqmImpl` on 6.6 and
`org.hibernate.query.sqm.internal.SqmQueryImpl` on 7.4; the prototype unwraps interfaces instead.
Exact compiled signatures and package annotation evidence are in `s3-api-<version>.txt`.

Stability classification: CriteriaQuery is JPA API; Session/createQuery/unwrap are Hibernate-facing public APIs.
`SessionFactoryImplementor`/`LoadQueryInfluencers` are engine SPI/infrastructure, `QueryEngine`, `SqmQuery` and
`QueryImplementor` are query SPI, and `SqlAstCreationContext` is SQL-AST SPI. The translator, SQM and SQL-AST
classes are Hibernate infrastructure rather than a supported JPA query inspection API. The SQL-AST package is
`@Incubating` on both lines, and the SQL-AST tree package is also `@Incubating` on 7.4.
`DomainParameterXref`, both runtime query implementations and `SqlFragmentPredicate` live in `.internal.`
packages; `SessionFactoryImpl` is internal too. No checked public interface annotation promises stability here.
The differing context accessor already demonstrates the need for a version-specific adapter.

Important correction to the question's proposed tree walk: SINGLE_TABLE narrowing is not necessarily an outer
WHERE predicate. Both lines prune the primary NamedTableReference into opaque SQL such as
`(select * from r16_st t where t.DTYPE in ('C', 'A'))`. TPC similarly stores its pruned union in a
`UnionTableReference` SQL string, with no subtype TableGroupJoin to inspect. JOINED does expose the subtype
TableReferenceJoin as INNER versus LEFT. Walking joins and the outer WHERE alone produced 19 false negatives
per SINGLE_TABLE target in the diagnostic run; it is insufficient.

The corrected prototype also checks the derived-table discriminator WHERE and missing sibling branches in the
TPC union string. Its structured WHERE walker ignores CASE guards; ON restrictions and LEFT-joined targets do
not count as loss of the owner row. CTE bodies and subquery SelectStatements are inspected recursively.
These string checks are fixture-specific lexical checks, not a general SQL parser or proof for arbitrary mappings.
Each detector result identifies evidence paths, but attribution across multiple simultaneous treated targets,
formula discriminators, quoting/schema variations and unrelated intentional subtype restrictions is untested.

### S3.2: correctness against executed fixture values

45 shapes per strategy/vendor/line cover engine-path and fresh counts for isNull, null-inclusive ne/notIn,
not(isNotNull), count(id)/predicate anchors, treated-only/id-anchored SELECT/CTE/coalesce, grouped and aggregate
forms, treated LEFT join selection/count, null-branch keyset, export and anchored order. Each shape asserts its
complete value rows/counts after checking that translation and both walks issued no JDBC statements.
All 19 narrowing shapes and 26 safe shapes agree on every vendor and line. The full per-vendor confusion table
is in `s3-confusion.tsv`; this aggregate is over the three dialects, with TP meaning correctly flagged narrowing:

| Line | Strategy | TP | TN | FP | FN |
| --- | --- | ---: | ---: | ---: | ---: |
| 6.6.58.Final | SINGLE_TABLE | 57 | 78 | 0 | 0 |
| 6.6.58.Final | JOINED | 57 | 78 | 0 | 0 |
| 6.6.58.Final | TABLE_PER_CLASS | 57 | 78 | 0 | 0 |
| 7.4.11.Final | SINGLE_TABLE | 57 | 78 | 0 | 0 |
| 7.4.11.Final | JOINED | 57 | 78 | 0 | 0 |
| 7.4.11.Final | TABLE_PER_CLASS | 57 | 78 | 0 | 0 |

There is no classification or structural dialect split in these shapes. Earlier mapping variants and all other
statement compositions remain outside this detector's coverage; zero FN here does not establish a universal gate.

### S3.3: translation and walk cost

`s3-cost.tsv` retains all 810 per-shape observations, in nanoseconds. Cold means the first translation/walk for
that shape in an already running fixture/session factory, not a cold JVM. Warm repeats translation/walk with no
result cache. Cached measures a second lookup of the previously computed verdict in a prototype IdentityHashMap;
it is not a Hibernate translation cache or an implemented reusable semantic shape cache. Both walks agree.

| Line | First translation+walk median / p95 (µs) | Repeat median / p95 (µs) | Memoized lookup median / p95 (µs) |
| --- | --- | --- | --- |
| 6.6.58.Final | 63.625 / 194.000 | 25.542 / 92.958 | 0.084 / 0.292 |
| 7.4.11.Final | 97.583 / 381.250 | 36.375 / 176.167 | 0.125 / 0.417 |

These are diagnostic timings, including query construction/unwrap within translateS3 and visitor allocation,
with GC/JIT/order noise; they are not a latency bound or a benchmark. No single reused SQL AST is executed.
Caching only a verdict could avoid repeated work, but its key must distinguish the final selection/count shape,
dialect/version, mapping, query options, enabled filters and other translation influences. Parameter-sensitive
and mutable-query cache validity is not established by this identity-cache measurement.

### S3.4: offset fallback and RFC decision

All six engine-undercount cases (select-only, order-only, isNull, null-inclusive ne/notIn and not(isNotNull))
are tested at their last two-row page on all three strategies/vendors/lines: 108 passing observations.
The unfiltered case has offset3, rows2, total3; isNull/not(isNotNull) has offset1, rows2, total1;
ne/notIn has offset2, rows2, total2. Each asserts the actual page IDs and count, then
`rowsReturned > total - offset`. `s3-fallback.tsv` retains these values.

The inequality detects a lower bound inconsistent with total, not the exact missing count. An early one-row
page with offset0 and any of these totals does not trigger (the same assertion checks that arithmetic).
It cannot see an undercount while offset+rowsReturned <= total, or when no page is fetched; keyset/export without
an offset/total pair and counts without content are outside its coverage. If both content and count lose the
same rows, they can remain consistent and invisible. Overcounts do not trigger. An empty beyond-end page can
trigger when offset > total even for a correct total, so production use needs a nonempty-page guard.

RFC decision: pre-execution inspection is technically viable on both tested lines inside model-query-hibernate,
using a version adapter over SPI/internal/incubating infrastructure. A pure structural SQL-AST walk is not a
viable universal detector: ST derived tables and TPC unions hide restrictions in opaque SQL strings. This hybrid
prototype has zero FP/FN on its 45 known shapes, but does not justify admitting previously refused contexts or
claiming arbitrary treated-node attribution. Keep the existing allow-list unchanged; unknown representations
must fail closed pending a separately designed/supported detector. The fallback is useful only as a partial
post-execution consistency check and cannot replace count correctness or the allow-list.

## What this means for the RFC

These are desired-contract failures or incorrect rendering expectations, not reasons to redefine NULL semantics.
All cells below are both lines/all vendors unless explicitly qualified.

| RFC claim / gap | Contradicting cell, or missing evidence corrected |
| --- | --- |
| §2 R-COL-35 group key keeps sibling NULLs | `2/GROUP_BY`: NULL group1 vs3, every core strategy |
| §2 aggregate argument retains unrelated roots | `2/SELECT,AGGREGATE`: root count3 vs5; `9-context/SOURCE/AGGREGATE` also fails |
| §2/§6 treated count matches content | Standalone `3-valueA` isNull/not(isNotNull) count1 vs3, ne/notIn2 vs4 |
| §2/§6 select/order-only treat contributes nothing to count; §11.6 select-only premise | **Previously unmeasured**; real `6-derived/COUNT_FROM/{SELECT,EXPRESSION,ORDER}` counts3 vs5 |
| §11.6 each count equals content | False for standalone NULL-accepting sets, expression predicates, select/order-derived counts and combined SELECT; OR/EXISTS/keyset and join counts now measured and pass |
| §3 nullable TYPE of absent LEFT row | `5/TYPE,SELECT,LEFT_JOIN`: discriminator-resolution exception, all core strategies |
| §3/§11.5 deterministic TYPE order on JOINED | `5/TYPE,ORDER` without discriminator emits invalid CASE END; discriminator modes pass measured order |
| §11.11 optional=false secondary treatment becomes INNER and drops rows | Both secondary scalar/missing-row reads LEFT-join and preserve five ids |
| §11.11 SQLRestriction mapping support/parity | 6.6 `11-hidden/SUBTYPE,TREAT` leaks hidden row; 7.4 `11-bootstrap` rejects mapping |
| §11.3/§11.9 Correlated.count and R-VND-26 vendor fact | **Previous evidence invalid**; own-root id/FK-correlated counts now pass all vendors/lines; withdraw old MQ4015 split |
| §10 per-read TreatSite/use-set model | Statement shape matters: plain select preserves five, count/aggregate/GROUP BY narrow to three; same NULL_TEST set differs by logical shape |
| §10 sites separately derived for count | Necessary but insufficient: createCountQuery prunes SELECT/ORDER sites yet an orphan treated SQM root still narrows; no remaining read site can report that hazard |
| §10 bulk write admission | Eq-only evidence was insufficient; null-accepting update/delete sets fail JOINED/TPC and all ST writes fail translation |

The four missed issues are now explicit above: count-vs-content scope, statement-shape dependence, valid correlated
count evidence/vendor-fact withdrawal, and the previously unmeasured select-only-count premise. Shared-column
refusal in §2 was already conditional on measured leakage; neither tested mapping contradicts that condition.
No new blanket "all shared columns safe" claim follows from these fixtures.

### Renderer allow-list and vendor facts

The [complete contextual table](0016-spike-data/renderer-allowlist.tsv) records every measured candidate by
statement shape × use envelope × strategy, and fails closed unless all cases pass on both lines/all three vendors.
`current_SPI_decision` additionally refuses indistinguishable use sets with a failing shape. An ALLOW is bounded to
the measured operators/mappings and contexts; it never authorizes an unseen union of flags. Mixed TYPE/treat
compositions remain refused as a generic union despite their passing AND/OR examples.

| Measured context | ST | JOINED | TPC | Qualification |
| --- | --- | --- | --- | --- |
| READ SELECT / EXPRESSION / ORDER | P | P | P | Untreated id read required; count hazards remain |
| READ SELECT+GROUP_BY / SELECT+AGGREGATE | refuse | refuse | refuse | Sibling rows lost |
| READ comparison PREDICATE and PREDICATE+NEGATED | P | P | P | All tested scalar/range/membership/like operators |
| COUNT comparison PREDICATE and PREDICATE+NEGATED | P | P | P | Own count sites; no NULL-accepting operator in set |
| READ NULL_TEST / NULL_TEST+NEGATED | P | P | P | Generic SPI set refused because COUNT counterpart fails |
| COUNT NULL_TEST / NULL_TEST+NEGATED | refuse | refuse | refuse | isNull and not(isNotNull) failures |
| COUNT PREDICATE+NULL_TEST+DISJUNCTION | refuse | refuse | refuse | Standalone ne/notIn; OR/keyset passes cannot override |
| READ predicate+DISJUNCTION / EXISTS predicate contexts | P | P | P | Exact envelopes in TSV; count contexts separately tested |
| LEFT/INNER joined PREDICATE / ORDER / NULL_TEST | P | P | P | READ and derived COUNT pass exact joined shapes |
| SELECT+AGGREGATE+SUBQUERY, optionally LEFT_JOIN | P | P | P | Own-root ID/FK scalar count; compliance excluded |
| TYPE+SELECT / TYPE+GROUP_BY / TYPE+PREDICATE | P | P | P | Predicate COUNT separately passes |
| CORRELATION+SUBQUERY on a treated outer read | refuse | refuse | refuse | Own-root counts use untreated outer ids |
| TYPE+ORDER | P | refuse | P | JOINED no discriminator |
| TYPE+LEFT_JOIN+SELECT | refuse | refuse | refuse | Absent-row discriminator exception |
| COUNT derived TYPE / LEFT_JOIN / INNER_JOIN | P | P | P | Actual content asserted before count |
| SOURCE+SELECT / SOURCE+NULL_TEST | P | P | P | With id read; S2 treated-only body fails |
| SOURCE+SELECT+AGGREGATE | refuse | refuse | refuse | root count3 vs5 |
| WRITE_PREDICATE, scalar comparisons / NEGATED | refuse | P | P | Exact update/delete operators, not NULL-accepting sets |
| WRITE_PREDICATE+NULL_TEST, optionally NEGATED/DISJUNCTION | refuse | refuse | refuse | Narrowing or translation failure |
| Any unlisted/missing/context composition | refuse | refuse | refuse | No value-asserting evidence |

P is empirical contextual permission, not an unconditional implementation of current §10. The current TreatSite
signature has no statement-shape key and cannot detect a pruned orphan treat. Until an engine rewrite or a richer
statement preflight is specified/proven, **refuse the indistinguishable set**, including NULL_TEST+NEGATED, and
refuse affected count-bearing operations before content executes. This spike makes no public SPI decision.
All probe-11 variants stay behind conservative mapping gates; compliance keeps MQ4014. A mapping bootstrap or
line-wide failure belongs in the renderer/MQ4016. There is **no measured vendor-only split** in these final data,
so no new Tier-1 VendorProfile#treats MQ4015 denial is supported. Individual positive flags do not prove sets.

S2 adds two further contradictions/gaps: §2's unconditional SELECT guarantee fails for keyless treated-only
projections and source bodies (`S2.3/SELECT` and `SOURCE/ID_ANCHOR=false`, three vsfive). the proposed fresh-count
remedy is insufficient (`S2.2/FRESH_COUNT/{isNull,ne,notIn,not(isNotNull)}/{ROOT,STAR}`).
S2.1 independently confirms the count claim fails on the actual engine path, beyond createCountQuery.

## Commands and verification

Final source ran sequentially in the foreground (no background Maven, no ps), using:

```sh
./mvnw -q -pl model-query-tck -am verify -Dtest=Rfc16SpikeTest -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -q -pl model-query-tck -am verify -Dtest=Rfc16SpikeTest -Dsurefire.failIfNoSpecifiedTests=false \
  -Dhibernate.version=7.4.11.Final -Djakarta-persistence.version=3.2.0
python3 rfc/0016-spike-data/summarize.py
python3 rfc/0016-spike-data/check-results.py
python3 rfc/0016-spike-data/check-s2.py
python3 rfc/0016-spike-data/summarize-s3.py
git diff --check
```

* 6.6.58.Final: exit0; 4288 P / 12038 P* / 1332 F.
  Surefire: `Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 38.92 s -- in
com.rey.modelquery.tck.spike.Rfc16SpikeTest`
* 7.4.11.Final: exit0; 3700 P / 12023 P* / 1305 F.
  Surefire: `Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 50.87 s -- in
com.rey.modelquery.tck.spike.Rfc16SpikeTest`

The final raw TSVs are fresh full runs, not hand-edited or spliced. Earlier narrow H2 runs and repeated full runs
caught an incomplete operator iteration and fixture/order oracle mistakes; both lines were rerun after correction.
Per-query timeout is10s. Docker/Testcontainers teardown and transactional mutation rollback use the TCK harness.
The width check covers Java/Markdown; raw SQL TSVs intentionally keep full lines. `git diff --check` has no output.
No full suite, product engine verify, commit or push was run. SQL capture is research evidence, not SQL snapshots.

## Left for later

* RFC author/Hibernate adapter owner: decide whether to pursue fail-closed SQL-AST preflight, opaque SQL parsing
  and semantic verdict-cache keys; S3 is research only and preserves all renderer refusals.

* RFC author/M28 implementation: settle statement preflight/orphan treat and use-set granularity, then encode the
  conservative gates and diagnostics. S2 proves fresh roots alone insufficient; id anchors/entity joins need design.
* M28 implementation: real sibling-cursor MQ2209, duplicate-id MQ2213, tablesOf MQ4010 and generated-model integration.
  Association fixtures here validate Criteria semantics, not processor/public-SPI changes.
* Acceptance follow-up: HQL equivalence controls and arbitrary additional source/subquery/join/TYPE compositions,
  dialects, mappings and operators remain unprobed/refused; no assertion that isolated passing flags compose.
* Caller owns full-project/two-line verification. The throwaway branch must never merge or push.

AC covered: AC-VND-17 research evidence; downstream product diagnostics remain uncovered here.
Snapshots: none. Spec edits: none. Questions: statement-shape design is an RFC author decision, not implemented here.
