package com.rey.modelquery.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.Objects;

/** Test entity for {@link StatementBindLimitTest}: a two-column primary key, whose keyset cursor binds three values. */
@Entity
@Table(name = "bind_limit_pairs")
@IdClass(BindLimitPairEntity.Key.class)
public class BindLimitPairEntity {
    @Id
    @Column(name = "first_no")
    Integer firstNo;

    @Id
    @Column(name = "second_no")
    Integer secondNo;

    @Column(name = "tag")
    Integer tag;

    protected BindLimitPairEntity() {}

    BindLimitPairEntity(int firstNo, int secondNo, int tag) {
        this.firstNo = firstNo;
        this.secondNo = secondNo;
        this.tag = tag;
    }

    /** The {@link IdClass}. */
    public static class Key implements Serializable {
        Integer firstNo;
        Integer secondNo;

        public Key() {}

        @Override
        public boolean equals(Object o) {
            return o instanceof Key other && Objects.equals(firstNo, other.firstNo)
                    && Objects.equals(secondNo, other.secondNo);
        }

        @Override
        public int hashCode() {
            return Objects.hash(firstNo, secondNo);
        }
    }
}
