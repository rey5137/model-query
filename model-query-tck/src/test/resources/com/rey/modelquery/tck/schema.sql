-- Fixture schema (spec delivery/60 §2). Portable DDL; @COLLATE@ becomes a binary collation per vendor so string
-- ordering is identical everywhere, and @CI_TEXT@ a VARCHAR(150) with a case-insensitive one.
CREATE TABLE customers (
    id         BIGINT NOT NULL PRIMARY KEY,
    name       VARCHAR(100) @COLLATE@ NOT NULL,
    email      VARCHAR(150) @COLLATE@ NOT NULL,
    country    VARCHAR(2) @COLLATE@ NOT NULL,
    vip        BOOLEAN NOT NULL,
    created_at TIMESTAMP NOT NULL
);
CREATE TABLE orders (
    id          BIGINT NOT NULL PRIMARY KEY,
    customer_id BIGINT NOT NULL,
    status      VARCHAR(20) @COLLATE@ NOT NULL,
    total       DECIMAL(12,2) NOT NULL,
    placed_at   TIMESTAMP NOT NULL,
    referrer_id BIGINT NULL,
    version     INT DEFAULT 0 NOT NULL,
    CONSTRAINT fk_orders_customer FOREIGN KEY (customer_id) REFERENCES customers (id),
    CONSTRAINT fk_orders_referrer FOREIGN KEY (referrer_id) REFERENCES customers (id)
);
CREATE TABLE order_items (
    id           BIGINT NOT NULL PRIMARY KEY,
    order_id     BIGINT NOT NULL,
    product_code VARCHAR(20) @COLLATE@ NOT NULL,
    quantity     INT NOT NULL,
    unit_price   DECIMAL(12,2) NOT NULL,
    CONSTRAINT fk_order_items_order FOREIGN KEY (order_id) REFERENCES orders (id)
);
CREATE TABLE composite_key_items (
    tenant_id INT NOT NULL,
    item_no   INT NOT NULL,
    label     VARCHAR(50) @COLLATE@ NOT NULL,
    amount    DECIMAL(12,2) NOT NULL,
    PRIMARY KEY (tenant_id, item_no)
);
CREATE TABLE nullable_sort_rows (
    id        BIGINT NOT NULL PRIMARY KEY,
    sort_int  INT NULL,
    sort_text VARCHAR(20) @COLLATE@ NULL,
    sort_ts   TIMESTAMP NULL
);
CREATE TABLE labels (
    id   BIGINT NOT NULL PRIMARY KEY,
    name VARCHAR(20) @COLLATE@ NOT NULL
);
CREATE TABLE order_labels (
    label_id BIGINT NOT NULL,
    order_id BIGINT NOT NULL,
    PRIMARY KEY (label_id, order_id),
    CONSTRAINT fk_order_labels_label FOREIGN KEY (label_id) REFERENCES labels (id),
    CONSTRAINT fk_order_labels_order FOREIGN KEY (order_id) REFERENCES orders (id)
);
CREATE TABLE customer_notes (
    id             BIGINT NOT NULL PRIMARY KEY,
    customer_email @CI_TEXT@ NOT NULL,
    body           VARCHAR(50) @COLLATE@ NOT NULL
);
-- One row per keyset cursor type (TCK AC-PAG-18): amount keeps a scale, stamp microseconds, token a UUID,
-- payload a byte[] and shape a converted value class no cursor codec carries.
CREATE TABLE keyset_types (
    id      BIGINT NOT NULL PRIMARY KEY,
    tie     INT NOT NULL,
    amount  DECIMAL(12,4) NOT NULL,
    stamp   @MICRO_TS@ NOT NULL,
    token   VARCHAR(36) @COLLATE@ NOT NULL,
    payload @BINARY@ NOT NULL,
    shape   VARCHAR(20) @COLLATE@ NOT NULL
);
-- A String primary key whose codes sort differently from their insertion order (TCK AC-PAG-24).
CREATE TABLE string_key_products (
    code     VARCHAR(12) @COLLATE@ NOT NULL PRIMARY KEY,
    name     VARCHAR(40) @COLLATE@ NOT NULL,
    category VARCHAR(12) @COLLATE@ NOT NULL,
    price    DECIMAL(12,2) NOT NULL
);
-- An @EmbeddedId of a region code and a sequence number (TCK AC-PAG-25).
CREATE TABLE embedded_key_items (
    region_code VARCHAR(10) @COLLATE@ NOT NULL,
    seq_no      INT NOT NULL,
    label       VARCHAR(30) @COLLATE@ NOT NULL,
    amount      DECIMAL(12,2) NOT NULL,
    PRIMARY KEY (region_code, seq_no)
);
-- A product with a surrogate key and a unique non-key sku, and lines that reference it by that sku (TCK AC-COL-15).
CREATE TABLE sku_products (
    id    BIGINT NOT NULL PRIMARY KEY,
    sku   VARCHAR(20) @COLLATE@ NOT NULL UNIQUE,
    name  VARCHAR(40) @COLLATE@ NOT NULL,
    price DECIMAL(12,2) NOT NULL
);
CREATE TABLE sku_order_lines (
    id          BIGINT NOT NULL PRIMARY KEY,
    product_sku VARCHAR(20) @COLLATE@ NOT NULL,
    quantity    INT NOT NULL,
    CONSTRAINT fk_sku_order_lines_product FOREIGN KEY (product_sku) REFERENCES sku_products (sku)
);
-- A product joined through a Hibernate @JoinFormula on upper(product_code) (TCK AC-COL-16).
CREATE TABLE formula_products (
    code  VARCHAR(12) @COLLATE@ NOT NULL PRIMARY KEY,
    name  VARCHAR(40) @COLLATE@ NOT NULL,
    price DECIMAL(12,2) NOT NULL
);
CREATE TABLE formula_lines (
    id           BIGINT NOT NULL PRIMARY KEY,
    product_code VARCHAR(20) @COLLATE@ NOT NULL,
    quantity     INT NOT NULL
)
