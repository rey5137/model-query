# Fetch plans

A query model selects what its own row holds. A fetch plan adds what a row cannot: the to-many children of each model
(an order's items), a to-one child found by key, a nested plan for a `@Join`ed model, and values you look up yourself.
The library loads them once per page, in a few extra statements, so there is no query per row.

Fetch plans are `@Incubating`, as every type is in 0.x; see [API stability](stability.md).

## A plan and a child

Mark the field with `@Child`. A to-many child is a `List` of another `@QueryModel` and names the child's column that
points back, `foreignKey`; the child needs a `@PrimaryKey`.

```java
@QueryModel(root = CustomerEntity.class)
public record CustomerOrders(
        @PrimaryKey Long id,
        String name,
        @Child(foreignKey = "customer.id") List<OrderLines> orders) {}

@QueryModel(root = OrderEntity.class)
public record OrderLines(
        @PrimaryKey Long id,
        String status,
        BigDecimal total,
        @Child(foreignKey = "order.id") List<Line> items) {}

@QueryModel(root = OrderItemEntity.class)
public record Line(@PrimaryKey Long id, String productCode, Integer quantity) {}
```

`key` and `foreignKey` are attribute paths on the parent's and the child's entity. `key` defaults to the parent's
`@PrimaryKey`. Both take one path, and their types must be equal. A `@Child` of type `Optional` is a to-one child.
The processor generates a `ChildField` constant per `@Child`, such as `QCustomerOrders.ORDERS`.

A plan says which children to load. It is immutable, built bottom-up, and attached to the query with `fetch(...)`:

```java
FetchPlan<Line> lines = FetchPlan.of(QLine.ALL);
FetchPlan<OrderLines> orders = FetchPlan.of(QOrderLines.ALL).child(QOrderLines.ITEMS, lines);
FetchPlan<CustomerOrders> customers = FetchPlan.of(QCustomerOrders.ALL).child(QCustomerOrders.ORDERS, orders);

var q = QCustomerOrders.query()
        .fetch(customers)
        .orderBy(QCustomerOrders.NAME.asc())
        .build();
```

`fetch(plan)` selects the plan's columns, and the columns it needs besides: each child's key. It replaces `select`, and
the last of the two wins as a whole, so call `fetch` last. `q.withFetch(otherPlan)` returns a copy of a query with
another plan, for a plan that depends on the call, such as an export's columns.

Run the query as usual. The plan runs on the models a call returns, at every level:

```java
List<CustomerOrders> list = executor.list(q, Limit.of(100));
Slice<CustomerOrders> page = executor.page(q, PageSpec.of(0, 50), CountMode.NO_COUNT);
executor.export(q, ExportOptions.of(500), p -> p, sink);
```

A plan works with every paging mode. With `keyset()` or `primaryKeyFirst(...)`, the children load for the models of
each page, after the page's own statements:

```java
var deep = QCustomerOrders.query()
        .fetch(customers)
        .orderBy(QCustomerOrders.NAME.asc())
        .keyset()                  // or .primaryKeyFirst(PrimaryKeyFirst.whenOffsetAbove(10_000))
        .build();
executor.export(deep, ExportOptions.of(500), p -> p, sink);
```

The same calls on a Spring repository run it too, since the plan is part of the query:

```java
ModelPage<CustomerOrders> page = customerRepository.findPage(q, PageRequest.of(0, 50), CountMode.COUNT);
List<CustomerOrders> top = customerRepository.findAll(q, Limit.of(10));
```

`count` and `ONLY_COUNT` load no children. `stream` refuses a plan that has any (`MQ2605`); use `export`, which runs
the plan on each page; on the last page it runs on the whole page and the export's `limit` cuts after, since a
`pageTransformer` can change the item count.

### A to-one child, and a key other than the primary key

A to-one child is an `Optional`. It is matched on `key`, a column of the parent, against the child's `foreignKey`,
which defaults to the child's `@PrimaryKey`. Set `key` when the parent holds the child's key, as an order holds its
customer's:

```java
@QueryModel(root = OrderEntity.class)
public record OrderBuyer(
        @PrimaryKey Long id,
        @Child(key = "customer.id") Optional<Buyer> buyer,          // OrderEntity.customer.id = CustomerEntity.id
        @Child(key = "referrerId") Optional<Buyer> referrer) {}     // a plain, nullable column

@QueryModel(root = CustomerEntity.class)
public record Buyer(@PrimaryKey Long id, String name) {}

FetchPlan<Buyer> buyer = FetchPlan.of(QBuyer.ALL);
FetchPlan<OrderBuyer> plan = FetchPlan.of(QOrderBuyer.ALL)
        .child(QOrderBuyer.BUYER, buyer)
        .child(QOrderBuyer.REFERRER, buyer);                         // one plan serves both fields
```

An order whose `referrerId` is `null` keeps `Optional.empty()`. If two distinct child rows match one key, the load
fails with `MQ2601` naming the field: use a `List` for a key that is not unique on the child's side.

Both sides may name other columns, of equal type, for children keyed on something other than an id:

```java
@QueryModel(root = CustomerEntity.class)
public record CustomerNotes(
        @PrimaryKey Long id,
        String email,
        @Child(key = "email", foreignKey = "customerEmail") List<Note> notes) {}
```

Keys are compared by the database. If the child's column has a case-insensitive collation and the parent's does not, a
child can match a key that differs in case, which fails with `MQ2604` rather than attach it to the wrong parent.

### A class model

A class model takes `@Child` the same way. The library sets the field through its setter on the same instance, rather
than building a copy as it does for a record. A mapped row holds `List.of()` or `Optional.empty()` until its child is
loaded, as a record does:

```java
@QueryModel(root = OrderEntity.class)
public class OrderForm {
    @PrimaryKey private Long id;
    @Child(foreignKey = "order.id") private List<Line> items;

    // a no-argument constructor, getters and setters
}
```

### Filters, order and a cap on a child

`child` takes a `ChildQuery` to filter a child, order it and bound it:

```java
.child(QCustomerOrders.ORDERS, orders, c -> c
        .where(f -> f.eq(QOrderLines.STATUS, "PAID"))
        .orderBy(QOrderLines.TOTAL.desc())
        .maxPerParent(50))
```

The child's order is closed by its primary key, which is also the default order. A child row is read once per parent,
so a filter through a to-many join does not repeat it. A to-one child without a `@PrimaryKey` is not
deduplicated: reached through a to-many path it fails with `MQ2601` on repeated rows, so give it a `@PrimaryKey`.

## What to know

- **A child not in the plan reads as empty.** A model holds `List.of()` or `Optional.empty()` until its child is loaded.
  A model from a query without the plan, or with a plan that omits the child, looks the same as a parent that has no
  children. Do not read an empty list as "none exist" unless the plan loads that child.
- **Children are unbounded without `maxPerParent`.** A page of 50 parents loads every child of those 50. With
  `maxPerParent(n)`, a parent with more than `n` children, or a statement that reads its row cap, fails with `MQ2603`
  instead of loading them. Set it on every child whose size you do not control.
- **One consistent snapshot only inside a transaction.** A page and each child load are separate statements. Outside a
  transaction they are separate reads, and a child can change between them. Run the call in a read-only transaction to
  read all of them from one snapshot. Child loads run on the same `EntityManager`, inside your transaction if there is
  one.
- Child keys are matched on the database values, split into rounds of at most the vendor's bind limit, so a large page
  runs more than one statement per child. A child whose key matches none of its round's keys, which a case-insensitive
  collation can cause, fails with `MQ2604`.
- A child on a grouped query is refused (`MQ1704`): a grouped row has no single key.
- A column a plan needs may not be read through a to-many join (`MQ1702`), and a plan that names a child or join
  twice fails with `MQ1703`.

## Many-to-many children

A many-to-many child has several parents, and each parent has several of it. It appears under every parent it belongs
to. There are two ways to declare one.

**From the child's side.** If the child's entity maps the association back, a `foreignKey` may cross the child's
collection. An order's labels, where `LabelEntity.orders` is the owning side:

```java
@QueryModel(root = OrderEntity.class)
public record OrderLabels(
        @PrimaryKey Long id,
        @Child(foreignKey = "orders.id") List<LabelView> labels) {}
```

**With `through`.** When the association is mapped on the parent's side only, name the path from the parent's entity
to the child's entity instead. The child query is rooted at the parent's entity and joined along the path.

```java
// A unidirectional @ManyToMany: PatronEntity.referrers has no way back.
@QueryModel(root = PatronEntity.class)
public record PatronReferrers(
        @PrimaryKey Long id,
        @Child(through = "referrers") List<Referrer> referrers) {}

// The inverse side of a bidirectional one: OrderEntity.labels is mappedBy LabelEntity.orders.
@QueryModel(root = OrderEntity.class)
public record OrderLabelSet(
        @PrimaryKey Long id,
        @Child(through = "labels") List<LabelView> labels) {}

// Through a join entity: a label, the orders that carry it, and their customers.
@QueryModel(root = LabelEntity.class)
public record LabelBuyers(
        @PrimaryKey Long id,
        @Child(through = "orders.customer") List<Buyer> buyers) {}
```

The rules for `through`:

- The path may cross associations only, to-one or to-many, and must end at the child model's root entity. Anything else
  is `MQ3406`.
- The parent's `key` must be its root's single `@Id`, which is the default. Any other key is `MQ3406`.
- `through` and `foreignKey` exclude each other (`MQ3401`).
- A grouped child model is refused (`MQ3406`).
- A child's filter, order and customizer resolve their paths against the join at the end of the path, so the generated
  columns of the child model work unchanged. A customizer must resolve paths through its `JoinContext`, not through
  `query.getRoots()`, which holds the parent's entity.

So a `through` child is filtered, ordered and nested like any other. A label's paid orders, largest first, each with
its items:

```java
@QueryModel(root = LabelEntity.class)
public record LabelOrders(
        @PrimaryKey Long id,
        @Child(through = "orders") List<OrderLines> orders) {}

FetchPlan<LabelOrders> plan = FetchPlan.of(QLabelOrders.ALL)
        .child(QLabelOrders.ORDERS,
                FetchPlan.of(QOrderLines.ALL).child(QOrderLines.ITEMS, FetchPlan.of(QLine.ALL)),
                c -> c.where(f -> f.eq(QOrderLines.STATUS, "PAID"))   // OrderLines.status, on the joined order
                      .orderBy(QOrderLines.TOTAL.desc()));
```

## Plans through a join

A `@Join` field holds a model read in the same row. A plan for that model applies through the join, with its columns
re-rooted under it, so one plan serves on its own and nested:

```java
@QueryModel(root = CustomerEntity.class)
public record Patron(
        @PrimaryKey Long id,
        String name,
        @Child(foreignKey = "customer.id") List<OrderRef> orders) {}

@QueryModel(root = OrderEntity.class)
public record OrderPatrons(
        @PrimaryKey Long id,
        String status,
        @Join Optional<Patron> customer,
        @Join Optional<Patron> referrer) {}                  // a LEFT join: most orders have no referrer

FetchPlan<Patron> patron = FetchPlan.of(QPatron.ALL).child(QPatron.ORDERS, FetchPlan.of(QOrderRef.ALL));
FetchPlan<OrderPatrons> plan = FetchPlan.of(QOrderPatrons.ALL)
        .join(QOrderPatrons.CUSTOMER_JOIN, patron)           // the same plan under both joins
        .join(QOrderPatrons.REFERRER_JOIN, patron);
```

The processor generates a `JoinField` constant per `@Join`, such as `QOrderPatrons.CUSTOMER_JOIN`. The `patron` plan
also runs on its own, on `QPatron.query().fetch(patron)`. Its child loads in batches for all the page's customers,
not one statement per order.

An empty `Optional` from a `LEFT` join is skipped. A join plan whose join has no selected column at or below it fails
with `MQ1701`, and one that selects an aggregate with `MQ1705`.

## Enrichers

An enricher fills a field the database cannot, once per page, from code you own. The field is `@Transient`, which the
library never fills itself, and the model needs a way to return a copy with it set. The enricher declares the columns it
reads, and the plan selects them for you.

`Enricher.byKey` collects a key from each model, makes one lookup for the page's distinct keys, and fills each model
from the result:

```java
@QueryModel(root = CustomerEntity.class)
public record CustomerCard(
        @PrimaryKey Long id,
        String email,
        @Transient Optional<LoyaltyTier> tier) {
    CustomerCard withTier(Optional<LoyaltyTier> value) {
        return new CustomerCard(id, email, value);
    }
}

FetchPlan<CustomerCard> plan = FetchPlan.of(QCustomerCard.ALL)
        .enrich(Enricher.byKey(
                CustomerCard::email,                          // the key of one model
                emails -> loyalty.tiersFor(emails),           // one call per page: Map<String, LoyaltyTier>
                (c, tier) -> c.withTier(Optional.of(tier)),   // a model whose key is missing from the map stays as is
                QCustomerCard.EMAIL));                        // the columns the key reads
```

`Enricher.of` takes the whole page and returns it filled, one model per position, in the page's order: position `i` of
the result replaces position `i` of the page. Use it when your code already fills a list of models, or a value depends
on the page as a whole rather than on one key:

```java
.enrich(Enricher.of(page -> loyalty.fillTiers(page), QCustomerCard.EMAIL))   // List<CustomerCard> in, same size out
```

A result of another size, or with a `null` element, fails with `MQ2602`.

### The order a plan runs in

Within a plan, children and joins run first, then its enrichers in the order added. A nested plan, under a join or a
child, runs whole before the outer plan's enrichers, so an outer enricher sees the joined models filled:

```java
FetchPlan<Patron> patron = FetchPlan.of(QPatron.ALL)
        .child(QPatron.ORDERS, FetchPlan.of(QOrderRef.ALL))                    // 1. the customer's orders
        .enrich(Enricher.of(page -> tagged(page)));                             // 2. reads p.orders()
FetchPlan<OrderPatrons> plan = FetchPlan.of(QOrderPatrons.ALL)
        .join(QOrderPatrons.CUSTOMER_JOIN, patron)
        .enrich(Enricher.of(page -> noted(page), QOrderPatrons.STATUS));       // 3. reads o.customer() and its tag
```

## Diagnostics

The fetch-plan codes are `MQ1701` to `MQ1705` (checked when the query is built or first run), `MQ2601` to `MQ2605`
(at execution) and `MQ3401` to `MQ3406` (at compile time); see [Diagnostics](diagnostics.md).

At `DEBUG`, the `com.rey.modelquery.jpa.DefaultModelQueryExecutor` logger logs each child load with its field and the
number of keys and rounds, for example `child CustomerOrders.orders: 50 keys in 1 round(s)`. At `TRACE`, each round's
statement logs as any statement does. Key values are never logged; see [Logging](diagnostics.md#logging).
