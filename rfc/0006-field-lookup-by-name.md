# RFC 0006 — Looking up a model's fields by name

- **Status:** draft
- **Affects:** `processor/31` (new R-GEN-32, R-GEN-33, AC-GEN-21..AC-GEN-24); `api/10` (new R-COL-23, R-COL-24,
  AC-COL-*); `reference/90` (new `MQ1105`); `reference/92` (new D-121). Reuses the property paths of `api/11`
  R-QRY-14 unchanged.
- **Discussion:** [#31](https://github.com/rey5137/model-query/discussions/31#discussioncomment-18807679)
- **Target:** 0.6. One public addition per generated class, `Q<M>.fields()`, and one core type, `FieldIndex<M>`,
  both `@Incubating`.

## Summary

Each generated `Q<M>` gains a static `fields()` method that returns a `FieldIndex<M>`: an immutable index of the
model's selectable columns, column sets, filter columns and children, keyed by the same property paths that sort
properties already use (`customer.name`). An application that lets its clients choose fields (a search endpoint with
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

    /** This model's fields by property path (R-GEN-32). */
    public static FieldIndex<OrderView> fields() {
        return Index.INSTANCE;
    }

    private static final class Index {
        static final FieldIndex<OrderView> INSTANCE = FieldIndex.<OrderView>builder(OrderView.class)
                .column("id", ID)
                .column("status", STATUS)
                .column("customer.id", CUSTOMER_ID)
                .column("customer.name", CUSTOMER_NAME)
                .filterColumn("customer.country", CUSTOMER_COUNTRY)
                .set("ALL", ALL)
                .set("DEFAULT", DEFAULT)
                .set("customer", CUSTOMER)
                .child("items", ITEMS)
                .build();
    }
}
```

**R-GEN-32** *(D-121)* Every `@QueryModel` generates a `public static FieldIndex<M> fields()` method that returns one
instance, held in a private nested holder class named `Index`. It is a method and not a constant, so it can't clash
with a column constant (a model field named `fields` generates the constant `FIELDS`, which Java keeps apart from the
method `fields()`), and so the holder initialises only after every constant of the outer class is set. Update and
insert models generate no `fields()`.

**R-GEN-33** *(D-121)* The index holds the following, in declaration order:

| Kind | Key | Included |
|---|---|---|
| Column | Its property path (R-QRY-14 tier 1): the field name, or `join.field` for a `@Join` model's column at any depth | Every mapped column, `@Computed` and `@Aggregate` constant, which are the ones `SELECTED_FIELDS` holds (R-GEN-29) |
| Filter column | The `@FilterColumn`'s property path | Filter-only columns. They can be filtered on but not selected |
| Column set | The constant's name for `ALL` and `DEFAULT`; the `@Join` field's property path for a join set | Every generated `SelectSet` constant |
| Child | The `@Child` field's property path | Every `ChildField` |

Keys are exact and case-sensitive, as in R-QRY-14. A column key and a set key can be the same string (`customer` is a
set; `customer.name` is a column), because they are looked up by kind.

### 2. `FieldIndex<M>`

```java
@Incubating
public final class FieldIndex<M> {
    public Optional<SelectField<M, ?>> select(String path);            // a selectable column, expression or aggregate
    public Optional<ColumnField<M, ?, ?>> filter(String path);         // any column, filter-only ones included
    public Optional<SelectSet<M>> set(String name);
    public Optional<ChildField<M, ?>> child(String path);

    public Resolution<M> resolve(Collection<String> names);            // see below
    public FieldIndex<M> only(Collection<String> names);               // a narrower index, for a public API
    public Set<String> names();                                        // every key, for documentation and errors

    public record Resolution<M>(SelectSet<M> select, List<ChildField<M, ?>> children, List<String> unknown) {}
}
```

**R-COL-23** `resolve(names)` turns each name into a select field, a set (merged into `select`) or a child (added to
`children`), in that order of kinds, and collects every name that matches none of them in `unknown`, in input order. A
filter-only column counts as unknown here. It never throws: the names usually come from a client, and the application
decides whether an unknown name is a `400`, a warning or something to ignore. An empty input gives an empty `select`;
the caller picks a default (`Q<M>.DEFAULT`).

**R-COL-24** `only(names)` returns an index holding just those keys, so an endpoint can expose part of a model and
keep the rest private. A name that isn't a key throws `MQ1105` naming it and the index's keys: this is the
application's own whitelist, so a mismatch is a definition error found at startup, not a client error.

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
none. The response is shaped by the application's own writer, which can read the row's `@Selected` set
(R-GEN-29) to leave out unselected fields instead of writing them as `null`.

### 4. New ids

| Id | Where | What |
|---|---|---|
| R-GEN-32, R-GEN-33 | `processor/31` §1 | The generated `fields()` and what the index holds |
| AC-GEN-21 | `processor/31` | `fields()` holds every column, set, filter column and child of a model with a `@Join`, a nested `@Join`, a `@Child`, a `@FilterColumn`, a `@Computed` and an `@Aggregate`, keyed as R-GEN-33 says |
| AC-GEN-22 | `processor/31` | A model with a field named `fields` compiles, and its `FIELDS` constant and `fields()` method both work |
| AC-GEN-23 | `processor/31` | A property path resolved through `fields()` and the same path used as a sort property name the same column |
| AC-GEN-24 | `processor/31` | Update and insert models generate no `fields()` |
| R-COL-23, R-COL-24 | `api/10` | `resolve` and `only` |
| AC-COL-* | `api/10` | `resolve` with known, unknown, filter-only, set and child names; `only` with an unknown name throws `MQ1105` |
| `MQ1105` | `reference/90` §2 | `FieldIndex.only` names a key the index doesn't hold |
| D-121 | `reference/92` | Lookup by property path, through a method and not a constant, with no value parsing |

## Compatibility

Purely additive. Existing generated classes gain one method and one private nested class; no constant changes name or
type. A model that already declares a static method `fields()` can't exist, because models aren't generated classes,
so the only possible clash is a hand-written class named `Q<M>` in the same package, which already clashes today. The
new API is `@Incubating`.

## Alternatives

- **Do nothing.** Applications keep their own maps. This works, but each one drifts and names fields its own way (see
  Motivation).
- **A public constant `FIELDS`.** Simpler, but it takes a name that a model field called `fields` already generates,
  and a constant declared last runs into the same initialisation order rule as `SELECTED_FIELDS` (R-GEN-29). A
  method with a holder class avoids both.
- **A runtime index built by reflection over `Q<M>`'s constants.** No processor change, but constant names
  (`CUSTOMER_NAME`) lose the path structure (`customer.name`) that the processor knows at compile time, and
  generated mappers otherwise read rows without reflection.
- **Also parse a field syntax (`id,status,customer(name)`) and convert filter values from strings.** Both are useful,
  but both need decisions this RFC doesn't need: a grammar, and a value-conversion SPI for dates, enums and converted
  columns. They can build on `FieldIndex` in a later RFC.
- **Aliases in the index (`customerName` → `customer.name`).** That puts the API's naming into the library. An
  application that needs other names keeps a small map in front of `resolve`. See the unresolved questions.

## Unresolved questions

1. **Aliases.** Should `only` take a map of public name to path, so an API can rename without its own map? It's cheap
   to add, but it starts to make the index an API contract and not a model description. The maintainer decides
   before acceptance.
2. **Children's own selection.** `resolve` returns the `ChildField`s, but a child's own fields
   (`items.code,items.quantity`) would need a `FetchPlan` per child. Either `resolve` builds the fetch plans, or it
   returns the child names and leaves the plans to the caller. This is left open until there's a real use.
3. **Column types.** Exposing each column's Java type (`Class<C>`) from the index would let an application convert
   filter values generically. Whether that belongs here or in the value-conversion RFC is left open.
4. **Spring Data integration.** Whether the starter should bind a `fields` request parameter the way it binds `Sort`
   is left for after acceptance.
