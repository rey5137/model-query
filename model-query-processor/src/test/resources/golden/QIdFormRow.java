package idf;

import com.rey.modelquery.annotations.Incubating;
import com.rey.modelquery.core.Changes;
import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.FieldIndex;
import com.rey.modelquery.core.InsertColumns;
import com.rey.modelquery.core.ModelDelete;
import com.rey.modelquery.core.ModelInsert;
import com.rey.modelquery.core.ModelPersist;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.ModelUpdate;
import com.rey.modelquery.core.OrderedColumnField;
import com.rey.modelquery.core.PrimaryKey;
import com.rey.modelquery.core.Row;
import com.rey.modelquery.core.RowMapper;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.core.ValuesInsert;
import java.util.List;
import javax.annotation.processing.Generated;

@Generated("com.rey.modelquery.processor.ModelQueryProcessor")
public final class QTicketRow {
    public static final TableField<TicketEntity, TicketEntity> ROOT = TableField.root(TicketEntity.class);

    public static final OrderedColumnField<TicketRow, TicketEntity, Long> ID = ColumnField.of(TicketRow.class,
            ROOT, "id", Long.class).named("id");

    public static final OrderedColumnField<TicketRow, TicketEntity, String> TITLE = ColumnField.of(TicketRow.class,
            ROOT, "title", String.class).named("title");

    public static final OrderedColumnField<TicketRow, TicketEntity, Long> REFERRER_ID = ColumnField.of(TicketRow.class,
            ROOT, "referrer", Long.class).named("referrerId");

    public static final OrderedColumnField<TicketRow, TicketEntity, CustomerEntity> BUYER = ColumnField.of(TicketRow.class,
            ROOT, "buyer", CustomerEntity.class).named("buyer");

    public static final ColumnField<TicketRow, TicketEntity, String> BUYER_REF = ColumnField.of(TicketRow.class,
            ROOT, "buyer", String.class, Long.class, CustomerIdConverter.INSTANCE)
            .named("buyerRef");

    public static final SelectSet<TicketRow> ALL = SelectSet.of(ID, TITLE, REFERRER_ID, BUYER,
            BUYER_REF);

    public static final SelectSet<TicketRow> DEFAULT = ALL;

    public static final PrimaryKey<TicketRow, Long> KEY = PrimaryKey.of(ID);

    public static final RowMapper<TicketRow> MAPPER = QTicketRow::map;

    /**
     * The root columns {@code insert}, {@code insertFrom} and {@code persist} write.
     * <p>Left out:
     * <ul>
     * <li>{@code buyer}: a to-one; only an {@code @InsertModel} writes a foreign key</li>
     * </ul>
     */
    @Incubating
    public static final InsertColumns<TicketRow, TicketEntity> INSERT_COLUMNS = InsertColumns.<TicketRow, TicketEntity>of(ROOT)
            .addKey(ID, TicketRow::id)
            .add(TITLE, TicketRow::title)
            .add(REFERRER_ID, TicketRow::referrerId)
            .add(BUYER_REF, TicketRow::buyerRef);

    private QTicketRow() {
    }

    public static ModelQuery.Builder<TicketEntity, Long, TicketRow> query() {
        return ModelQuery.builder(ROOT, MAPPER).primaryKey(KEY);
    }

    private static TicketRow map(Row row) {
        return new TicketRow(row.get(ID), row.get(TITLE), row.get(REFERRER_ID), row.get(BUYER),
                row.get(BUYER_REF));
    }

    /**
     * This model's fields by name, built on the first call ({@code R-GEN-32}); incubating.
     */
    @Incubating
    public static FieldIndex<TicketRow> fields() {
        return Index.INSTANCE;
    }

    @Incubating
    public static TicketRowChanges changes() {
        return new TicketRowChanges();
    }

    @Incubating
    public static ModelUpdate.Builder<TicketEntity, Long, TicketRow> update(
            Changes<TicketRow> changes) {
        return ModelUpdate.builder(ROOT).primaryKey(KEY).set(changes);
    }

    @Incubating
    public static ModelDelete.Builder<TicketEntity, Long, TicketRow> delete() {
        return ModelDelete.builder(ROOT).primaryKey(KEY);
    }

    @Incubating
    public static <S> ModelInsert.SelectStart<TicketEntity, TicketRow> insertFrom(
            TableField<S, S> sourceRoot) {
        return ModelInsert.select(INSERT_COLUMNS, sourceRoot);
    }

    @Incubating
    public static ValuesInsert.Rows<TicketEntity, Long, TicketRow> insert(
            List<? extends TicketRow> rows) {
        return ValuesInsert.builder(INSERT_COLUMNS, Long.class, rows);
    }

    @Incubating
    public static ModelPersist<TicketEntity, Long, TicketRow> persist(TicketRow row) {
        return ModelPersist.of(INSERT_COLUMNS, Long.class, row);
    }

    private static final class Index {
        static final FieldIndex<TicketRow> INSTANCE = FieldIndex.builder(TicketRow.class)
                .select(ID, TITLE, REFERRER_ID, BUYER, BUYER_REF)
                .set("ALL", ALL)
                .set("DEFAULT", DEFAULT)
                .build();
    }
}
