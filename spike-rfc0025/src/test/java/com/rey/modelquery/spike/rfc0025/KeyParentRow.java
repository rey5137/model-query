package com.rey.modelquery.spike.rfc0025;

import com.rey.modelquery.annotations.Child;
import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;
import java.util.List;

/**
 * One child per key type, each keyed on a same-named column of both sides. {@code byte[]} is not among them: the
 * processor refuses an array key (MQ3404), so {@link HandChildren} declares that child by hand.
 */
@QueryModel(root = Entities.KeyParent.class)
public record KeyParentRow(@PrimaryKey Long id, byte[] bytesKey,
        @Child(key = "intKey", foreignKey = "intKey") List<KeyChildRow> byInt,
        @Child(key = "strKey", foreignKey = "strKey") List<KeyChildRow> byStr,
        @Child(key = "uuidKey", foreignKey = "uuidKey") List<KeyChildRow> byUuid,
        @Child(key = "dateKey", foreignKey = "dateKey") List<KeyChildRow> byDate,
        @Child(key = "colorKey", foreignKey = "colorKey") List<KeyChildRow> byColor,
        @Child(key = "monoKey", foreignKey = "monoKey") List<KeyChildRow> byMono,
        @Child(key = "nothingKey", foreignKey = "nothingKey") List<KeyChildRow> byNothing,
        @Child(key = "codeKey", foreignKey = "codeKey") List<KeyChildRow> byCode) {}
