# Migration recipes

Recipes for moving an existing reporting service onto model-query, one adoption case at a time. Each recipe's code is
copied from a test that runs, named under the code.

## Join on a non-key column or a formula

The case in one line: an association that joins on something other than the target's primary key, or on a computed
value, and an ad-hoc join that needs an extra `ON` condition.

The mapping states the association as JPA or Hibernate already does; the generated `@Join` follows it.

```java
@Entity
@Table(name = "sku_order_lines")
public class SkuOrderLineEntity {
    @Id
    Long id;

    int quantity;

    @ManyToOne
    @JoinColumn(name = "product_sku", referencedColumnName = "sku")   // the product's unique non-key sku
    SkuProductEntity product;
}
```

```java
@Entity
@Table(name = "formula_lines")
public class FormulaLineEntity {
    @Id
    Long id;

    @Column(name = "product_code")
    String productCode;

    int quantity;

    @ManyToOne
    @JoinFormula("upper(product_code)")   // joins the product whose code is upper(product_code)
    FormulaProductEntity product;
}
```

The `@Join` model has an `Optional` nested model per association, with no hint of how the join is written:

```java
@QueryModel(root = SkuOrderLineEntity.class)
public record SkuLineView(@PrimaryKey Long id, Integer quantity, @Join Optional<SkuProductView> product) {}
```

Selecting a nested column, filtering on it and sorting on it all use the mapped join:

```java
var q = QSkuLineView.query()
        .select(SelectSet.of(QSkuLineView.ID, QSkuLineView.QUANTITY, QSkuLineView.PRODUCT_ID,
                QSkuLineView.PRODUCT_SKU, QSkuLineView.PRODUCT_NAME, QSkuLineView.PRODUCT_PRICE))
        .where(f -> f.eq(QSkuLineView.PRODUCT_NAME, Optional.of("Sku 03")))
        .orderBy(QSkuLineView.ID.asc())
        .build();
```

The SQL it renders, abbreviated:

```sql
select l.id, l.quantity, p.id, p.sku, p.name, p.price
from sku_order_lines l
left join sku_products p on p.sku = l.product_sku
where p.name = ?
order by l.id asc
```

For an extra `ON` condition the mapping does not carry, write a hand-written `TableField` and add it with `on(...)`,
which requires the alias from `as(...)`; D-111 declines `@Join(on = ...)` in favour of this:

```java
private record CheapLine(Long id, String productName) {}
private static final TableField<SkuOrderLineEntity, SkuOrderLineEntity> LINES =
        TableField.root(SkuOrderLineEntity.class);
private static final TableField<SkuOrderLineEntity, SkuProductEntity> CHEAP =
        TableField.<SkuOrderLineEntity, SkuProductEntity>join(LINES, "product", LEFT).as("cheap")
                .on((p, cb) -> cb.le(p.<BigDecimal>get("price"), new BigDecimal("1.50")));
private static final ColumnField<CheapLine, SkuOrderLineEntity, Long> LINE_ID =
        ColumnField.of(CheapLine.class, LINES, "id", Long.class);
private static final ColumnField<CheapLine, SkuProductEntity, String> CHEAP_NAME =
        ColumnField.of(CheapLine.class, CHEAP, "name", String.class);

ModelQuery<SkuOrderLineEntity, Long, CheapLine> q = ModelQuery
        .builder(LINES, row -> new CheapLine(row.get(LINE_ID), row.get(CHEAP_NAME)))
        .select(SelectSet.of(LINE_ID, CHEAP_NAME))
        .primaryKey(PrimaryKey.of(LINE_ID))
        .orderBy(LINE_ID.asc())
        .build();
```

Because the condition goes through `Join#on`, a line whose product is not cheap keeps its row with a `NULL` name.

Tested by `NonKeyJoinTest`.

## Keyset paging with a String or embedded key

The case in one line: a table whose primary key is a String, or an `@EmbeddedId` of two columns, paged by keyset and by
primary-key-first just like a numeric key.

```java
@Entity
@Table(name = "string_key_products")
public class StringKeyProductEntity {
    @Id
    String code;

    String name;

    String category;

    BigDecimal price;
}
```

```java
@Entity
@Table(name = "embedded_key_items")
public class EmbeddedKeyEntity {
    @EmbeddedId
    Key key;

    String label;

    BigDecimal amount;

    @Embeddable
    public static class Key implements Serializable {
        @Column(name = "region_code")
        String regionCode;

        @Column(name = "seq_no")
        int seqNo;
        // equals and hashCode omitted
    }
}
```

The query states its primary key as usual; a String key is `PrimaryKey.of(code)`, an embedded one is
`PrimaryKey.composite(regionCode, seqNo)`:

```java
ModelQuery.Builder<StringKeyProductEntity, String, StringRow> rows = ModelQuery
        .builder(PRODUCTS, row -> new StringRow(row.get(CODE), row.get(NAME), row.get(CATEGORY)))
        .select(SelectSet.of(CODE, NAME, CATEGORY))
        .primaryKey(PrimaryKey.of(CODE));
```

```java
ModelQuery.Builder<EmbeddedKeyEntity, List<Object>, EmbeddedRow> rows = ModelQuery
        .builder(EMBEDDED, row -> new EmbeddedRow(row.get(REGION), row.get(SEQ), row.get(LABEL)))
        .select(SelectSet.of(REGION, SEQ, LABEL))
        .primaryKey(PrimaryKey.composite(REGION, SEQ));
```

The keyset page and its neighbour calls; the cursor is opaque, so pass it through rather than rebuilding it:

```java
var q = rows.orderBy(CATEGORY.asc()).keyset().build();

KeysetSpec spec = KeysetSpec.first(size);
KeysetSlice<StringRow> page = executor.page(q, spec);
while (page.hasNext()) {
    spec = KeysetSpec.after(page.nextCursor().orElseThrow(), size);   // the cursor the page handed back
    page = executor.page(q, spec);
}
// the last page's cursor, walked the other way
KeysetSlice<StringRow> back = executor.page(q, KeysetSpec.before(page.previousCursor().orElseThrow(), size));
```

Primary-key-first paging works the same over the key:

```java
var twoStep = rows.orderBy(CATEGORY.asc())
        .primaryKeyFirst(PrimaryKeyFirst.whenOffsetAbove(0))
        .build();
Slice<StringRow> page = executor.page(twoStep, PageSpec.of(0, 500), CountMode.NO_COUNT);
```

The key's columns close the order, so tied sort values never skip or repeat a row.

Tested by `StringAndEmbeddedKeyTest`.
