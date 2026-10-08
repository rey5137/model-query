# RFC 0006 — Looking up a model's fields by name

- **Status:** accepted (revision 3, after the M13.1 `architect-review`; recorded as D-121)
- **Affects:** `processor/31` (new R-GEN-32, R-GEN-33, AC-GEN-21..AC-GEN-25); `processor/32` (new warning `MQ3022`);
  `api/10` (new R-COL-23, R-COL-24, AC-COL-23..); `api/11` R-QRY-14 (the aggregate tier also matches a `named`
  property); `reference/90` (new `MQ1105`); `reference/92` (new D-121); `delivery/61` (the builder is incubating and
  called by generated code).
- **Discussion:** [#31](https://github.com/rey5137/model-query/discussions/31#discussioncomment-18807679)
- **Target:** 0.6.0 (M13). One public addition per generated class, `Q<M>.fields()`; one core type, `FieldIndex<M>`,
  and `AggregateField.named(String)`, all `@Incubating`. Not 0.5.1: a patch release takes fixes only (R-REL-07,
  SemVer), and this adds public API.

## Summary

Each generated `Q<M>` gains a static `fields()` method that returns a `FieldIndex<M>`: an immutable index of the
model's selectable fields, column sets, filter columns and children, keyed by the same names that sort properties
already use (`customer.name`). An application that lets its clients choose fields (a search endpoint with
`?fields=id,status,customer.name`, a GraphQL selection set, a saved report) can turn those names into a typed
`SelectSet<M>` without writing a hand-kept map from strings to constants. The index resolves names only. Which names a
client may use, what the response looks like and how values are parsed stay with the application.

## Motivation

The processor knows every field of a model, but the only way to reach one is through its Java constant. A search API
whose clients choose the fields has to keep its own map:

```java
static final Map<String, SelectField<OrderView, ?>> ORDER_FIELDS = Map.of(
        "id", QOrderView.ID,
        "status", QOrderView.STATUS,
        "customer.name", QOrderView.CUSTOMER_NAME,
        "customer.country", QOrderView.CUSTOMER_COUNTRY);   // a filter-only column; selecting it is a mistake
```

That map has three problems:

1. **It drifts.** Adding a field to the model doesn't add it to the map, and nothing fails until a client asks for
   the field.
2. **It duplicates a naming rule the library already has.** `orderedBy(SortSpec)` (R-QRY-14) matches sort
   properties by property path. A map written by hand can name the same column differently, so `sort=customer.name`
   and `fields=customer_name` both work against one endpoint.
3. **It can't tell the kinds of field apart.** A filter-only column, a column set and a child are all reachable by
   name, but each needs a different call (`select`, `where`, a fetch plan). A flat map of constants loses which is
   which.

## Design

### 1. The generated entry point

```java
public final class QOrderView {
    // … the existing constants, unchanged …

    /** This model's fields by name (R-GEN-32). */
    public static FieldIndex<OrderView> fields() {
        return Index.INSTANCE;
    }

    private static final class Index {
        static final FieldIndex<OrderView> INSTANCE = FieldIndex.builder(OrderView.class)
                .select(ID, STATUS, TOTAL, CUSTOMER_ID, CUSTOMER_NAME, ITEM_COUNT)
                .filterOnly(CUSTOMER_COUNTRY)
                .set("ALL", ALL)
                .set("DEFAULT", DEFAULT)
                .joinSet(CUSTOMER_TABLE, CUSTOMER)
                .child(ITEMS)
                .build();
    }
}
```

**R-GEN-32** *(D-121)* Every `@QueryModel` generates a `public static FieldIndex<M> fields()` method that returns one
instance, held in a private nested holder class and initialised on the first `fields()` call. It is a method and not
a constant, so it can't clash with a column constant (a model field named `fields` generates the constant `FIELDS`,
which Java keeps apart from the method `fields()`), and the holder is lazy, so a `Q<M>` that is never asked for its
index never builds one. Calling `fields()` from `Q<M>`'s own static initialiser (for example from a converter's or an
`ExpressionDefinition`'s initialiser) fails with `ExceptionInInitializerError`, not a silent `null`. Update and insert
models generate no `fields()`.

**R-GEN-33** *(D-121)* The generated code passes constants, never key strings, apart from the reserved set names `ALL`
and `DEFAULT`. `FieldIndex` derives each key from the constant itself, with tier 1 of the rule `orderedBy` uses
(R-QRY-14), through one helper both share. The index holds the following, in declaration order:

| Kind | Key, derived by core | Included |
|---|---|---|
| Select | A column's property path (the field name, or `join.field` for a `@Join` model's column at any depth), else its attribute path. An expression's `named` property, else its name. An aggregate's `named` property, else its name | Every field the mapper reads (R-GEN-29): mapped columns, `@Computed` and `@Aggregate` constants |
| Filter-only column | Its attribute path (`@FilterColumn.path`): a filter-only column has no property (D-55). The alias isn't part of the key | `@FilterColumn` constants. They can be filtered on but not selected |
| Column set | `ALL` and `DEFAULT` as passed; a join set by its join's property path (`TableField.propertyPath()`) | `ALL`, `DEFAULT` and each `@Join` set, when the model generates sets. Not `GROUP_KEYS`, not `SELECTED_FIELDS` |
| Child | `ChildField.name()`, the `@Child` field's name | Every `ChildField` |

Keys are exact and case-sensitive, as in R-QRY-14. A select key and a set key can be the same string (`customer` is a
set; `customer.name` is a column), because they are looked up by kind. Within one kind, the same key with an equal
field keeps one entry; the same key with a different field makes `build()` throw `IllegalStateException`. The processor
never passes such a pair: a filter-only column whose key a mapped column already holds (`@FilterColumn(name =
"STATUS_RAW", path = "status")`), or a second filter-only column on one path, is left out of the index with the
warning `MQ3022`, and stays a constant. It isn't an error, because such models compile on 0.5.

The vocabulary is tier 1 only, a subset of the names a sort accepts: an attribute path that differs from the property
path (`customer.fullName` for the property `customer.name`) is unknown to the index. A name that resolves through
`fields()` names the same field as the same name used as a sort property whenever the sort resolves it. The sort can
still refuse a name that is ambiguous across its tiers, for example two `@Join`s on one attribute, both selected.

**Aggregate names.** An aggregate's `name()` is the canonical text of its function (`sum(total)`, `count(OrderEntity)`
for `COUNT(*)`), which would become a client-facing key and leak entity names. `AggregateField` gains
`named(String)`, as `ExpressionField` has, outside `equals` so a generated aggregate stays equal to the same one
written by hand (R-AGG-01). The processor emits `.named(field)` for every `@Aggregate`, and R-QRY-14's aggregate tier
matches the `named` property first, then the name, so existing sorts keep working.

### 2. `FieldIndex<M>`

```java
@Incubating
public final class FieldIndex<M> {
    public static <M> Builder<M> builder(Class<M> model);              // for generated and hand-written vocabularies

    public Optional<SelectField<M, ?>> select(String name);            // a column, expression or aggregate
    public Optional<ScalarField<M, ?>> filter(String name);            // a column (filter-only ones included) or an
                                                                       // expression: what Filters takes (D-115)
    public Optional<SelectSet<M>> set(String name);
    public Optional<ChildField<M, ?>> child(String name);

    public Resolution<M> resolve(Collection<String> names);            // see below
    public FieldIndex<M> only(Collection<String> names);               // a narrower index, for a public API
    public Set<String> names();                                        // what resolve accepts
    public Set<String> filterNames();                                  // what filter accepts

    public record Resolution<M>(SelectSet<M> select, List<ChildField<M, ?>> children, List<String> unknown) {}

    @Incubating
    public static final class Builder<M> {
        @SafeVarargs public final Builder<M> select(SelectField<M, ?>... fields);
        @SafeVarargs public final Builder<M> filterOnly(ColumnField<M, ?>... columns);
        public Builder<M> set(String name, SelectSet<M> set);
        public Builder<M> joinSet(TableField<M> join, SelectSet<M> set);
        @SafeVarargs public final Builder<M> child(ChildField<M, ?>... children);
        public FieldIndex<M> build();
    }
}
```

The builder is public and `@Incubating`, not `@EngineFacing`: generated code is compiled into users' jars, so the
builder is a binary contract, as `ColumnField.withTable` and `TableField.withParent` already are. A builder can gain a
kind later without breaking a signature, which an array factory couldn't. `select` and `filter` return wildcard
fields. `SelectSet.of` and `with` take `SelectField<M, ?>`, so a selection needs no capture. A filter does: an
application that converts a value itself writes a two-line helper, shown in the recipe,

```java
static <M, C> Filters<M> eqValue(Filters<M> f, ScalarField<M, C> column, Object value) {
    return f.eq(column, column.type().cast(value));
}
```

and the library adds no untyped `eq(ScalarField<M, ?>, Object)`, which would let a wrongly typed value compile (P-2).

**R-COL-23** `resolve(names)` turns each name into a select field, a set or a child, looked up in that order of
kinds, and collects every name that matches none of them in `unknown`, distinct, in input order. `select` holds the
select fields in input order, a set expanded in place, the first occurrence of a field kept. `children` holds each
child once, in input order, so a repeated name can't reach `MQ1703`. A filter-only column counts as unknown here, and
so does `""`; a `null` element throws `NullPointerException`. `resolve` never throws otherwise: the names usually come
from a client, and the application decides whether an unknown name is a `400`, a warning or something to ignore. An
input with no select field or set, empty or children only, gives an empty `select`, and the caller picks a default
(`Q<M>.DEFAULT`). The `Resolution` lists are copies (CC-IMM).

**R-COL-24** `only(names)` returns an index holding just those keys, so an endpoint can expose part of a model and
keep the rest private. A kept set holds only the select fields the narrowed index keeps, so `only(List.of("ALL",
"id"))` can't select more than `id`. A name that isn't a key, or a kept set left empty, throws `MQ1105` naming it and
the index's `names()` and `filterNames()`: this is the application's own whitelist, so a mismatch is a definition error
found at startup, not a client error.

### 3. In use

```java
private static final FieldIndex<OrderView> API_FIELDS =
        QOrderView.fields().only(List.of("id", "status", "total", "customer", "customer.name", "items"));

public List<Map<String, Object>> search(List<String> fields, Optional<String> status) {
    var resolved = API_FIELDS.resolve(fields);
    if (!resolved.unknown().isEmpty()) {
        throw new BadRequest("Unknown fields: " + resolved.unknown() + ", expected one of " + API_FIELDS.names());
    }
    var select = resolved.select().isEmpty() ? QOrderView.DEFAULT : resolved.select();
    var query = QOrderView.query()
            .select(select)
            .where(f -> f.eq(QOrderView.STATUS, status))
            .build();
    return executor.list(query, Limit.of(100)).stream().map(OrderResponses::write).toList();
}
```

Joins still follow the selection: asking for `customer.name` adds the customer join, and asking only for `id` adds
none. With `fields=items` alone, `select` is empty and the query falls back to `DEFAULT`. The response is shaped by the
application's own writer, which can read the row's `@Selected` set (R-GEN-29) to leave out unselected fields instead
of writing them as `null`. A resolved child is matched by identity (`child.equals(QOrderView.ITEMS)`) to build its
`FetchPlan`, because `FetchPlan.child` needs the child's typed model, which a `ChildField<M, ?>` doesn't carry.

### 4. New ids

| Id | Where | What |
|---|---|---|
| R-GEN-32, R-GEN-33 | `processor/31` §1 | The generated `fields()`, its lazy holder, and what the index holds |
| AC-GEN-21 | `processor/31` | `fields()` holds every select field, set, filter column and child of a model with a `@Join`, a nested `@Join`, a `@Child`, a `@FilterColumn`, a `@Computed` and an `@Aggregate`, keyed as R-GEN-33 says |
| AC-GEN-22 | `processor/31` | A model with a field named `fields` compiles, and its `FIELDS` constant and `fields()` method both work |
| AC-GEN-23 | `processor/31` | A property path resolved through `fields()` and the same path used as a sort property name the same field, for a column, a nested `@Join` column, an expression and a `named` aggregate |
| AC-GEN-24 | `processor/31` | Update and insert models generate no `fields()` |
| AC-GEN-25 | `processor/31` | A model whose entity and converter are named `Index` compiles, and its `fields()` works |
| `MQ3022` | `processor/32` | Warning: a filter-only column whose key a mapped column or another filter-only column already holds is left out of `fields()` |
| R-COL-23, R-COL-24 | `api/10` | `resolve` and `only` |
| AC-COL-23.. | `api/10` | `resolve` with known, unknown, filter-only, set, child, repeated, empty and `""` names and its ordering; `only` narrowing a set, and `only` with an unknown name or a set left empty throwing `MQ1105`; the builder's duplicate-key rule; `AggregateField.named` outside `equals` |
| `MQ1105` | `reference/90` §2 | `FieldIndex.only` names a key the index doesn't hold, or leaves a kept set empty |
| D-121 | `reference/92` | The decisions of this RFC |

## Compatibility

Purely additive. Existing generated classes gain one method and one private nested class; no constant changes name or
type. `AggregateField.named` is new and outside `equals`, and the aggregate sort tier still matches the old name. A
model that already declares a static method `fields()` can't exist, because models aren't generated classes, so the
only possible clash is a hand-written class named `Q<M>` in the same package, which already clashes today. A
`@FilterColumn` sharing a key with another column gains a warning, never an error. The new API is `@Incubating`.

## Alternatives

- **Do nothing.** Applications keep their own maps. This works, but each one drifts and names fields its own way (see
  Motivation).
- **A public constant `FIELDS`.** Simpler, but it takes a name that a model field called `fields` already generates.
- **A private static field declared last, as `SELECTED_FIELDS` is.** It builds every model's index in its `Q<M>`'s
  initialiser, used or not, and returns `null` when called during that initialiser. The holder is lazy and fails
  loudly (INV-5).
- **An `@EngineFacing` builder.** That marker means an executor's contract, which may change in any release; generated
  code in a user's jar can't.
- **A runtime index built by reflection over `Q<M>`'s constants.** No processor change, but constant names
  (`CUSTOMER_NAME`) lose the path structure (`customer.name`) that the processor knows at compile time, and
  generated mappers otherwise read rows without reflection.
- **Also parse a field syntax (`id,status,customer(name)`) and convert filter values from strings.** Both are useful,
  but both need decisions this RFC doesn't need: a grammar, and a value-conversion SPI for dates, enums and converted
  columns. They can build on `FieldIndex` in a later RFC.
- **Aliases in the index (`customerName` → `customer.name`).** That puts the API's naming into the library. An
  application that needs other names keeps a small map in front of `resolve` (decided question 1).
- **`filter(String, Class<C>)` returning a typed field.** A client-driven endpoint doesn't know `C` statically. It can
  be added later without breaking anything.

## Decided questions

1. **Aliases: no.** The index describes the model; public names are the API's. An application that renames keeps a
   small map in front of `resolve`.
2. **Children's own selection: not in 0.6.** `resolve` returns the `ChildField`s and the caller builds their
   `FetchPlan`s, matching each child by identity. A dotted name under a child (`items.code`) is unknown. A later RFC
   can add it once there's a real use.
3. **Column types and value conversion: nothing new.** Every `SelectField` already has `type()`, so an application can
   convert a filter value itself. Typed conversion helpers belong to the value-conversion RFC.
4. **Spring Data: not in 0.6.** Binding a `fields` request parameter waits for adopter feedback on the core API.
5. **The builder (M13.1):** public `@Incubating`, varargs methods `final` and `@SafeVarargs`, join sets passed by their
   `TableField`.
6. **The holder (M13.1):** a private nested class, lazy, loud when called during `Q<M>`'s own initialisation.
7. **Generics (M13.1):** no helper in the library; the recipe shows the capture helper.
8. **Aggregate keys (M13.1):** `AggregateField.named`, emitted by the processor, matched first by the sort.
