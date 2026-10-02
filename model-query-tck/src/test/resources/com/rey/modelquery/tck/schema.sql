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
)
