package com.rey.modelquery.spring.boot;

import com.rey.modelquery.core.Incubating;
import com.rey.modelquery.jpa.KeysetNullKeys;
import com.rey.modelquery.jpa.MysqlStreamingMode;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The {@code modelquery.*} properties of integration/50 §3; an unset one leaves the {@code ModelQueryConfig} default.
 * The bulk-write properties arrive with the write API (M8).
 *
 * @implSpec R-SPR-08
 */
@Incubating
@ConfigurationProperties("modelquery")
public class ModelQueryProperties {

    private String vendor;
    private Duration queryTimeout;
    private final Export export = new Export();
    private final PrimaryKeyFirst primaryKeyFirst = new PrimaryKeyFirst();
    private final Stream stream = new Stream();
    private final Mysql mysql = new Mysql();
    private final Keyset keyset = new Keyset();

    /** The vendor name that overrides detection, matched ignoring case, {@code -} and {@code _}. */
    public String getVendor() {
        return vendor;
    }

    public void setVendor(String vendor) {
        this.vendor = vendor;
    }

    public Duration getQueryTimeout() {
        return queryTimeout;
    }

    public void setQueryTimeout(Duration queryTimeout) {
        this.queryTimeout = queryTimeout;
    }

    public Export getExport() {
        return export;
    }

    public PrimaryKeyFirst getPrimaryKeyFirst() {
        return primaryKeyFirst;
    }

    public Stream getStream() {
        return stream;
    }

    public Mysql getMysql() {
        return mysql;
    }

    public Keyset getKeyset() {
        return keyset;
    }

    /** {@code modelquery.export.*}. */
    public static class Export {
        private Integer pageSize;

        public Integer getPageSize() {
            return pageSize;
        }

        public void setPageSize(Integer pageSize) {
            this.pageSize = pageSize;
        }
    }

    /** {@code modelquery.primary-key-first.*}. */
    public static class PrimaryKeyFirst {
        private Integer batchSize;

        public Integer getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(Integer batchSize) {
            this.batchSize = batchSize;
        }
    }

    /** {@code modelquery.stream.*}. */
    public static class Stream {
        private Integer fetchSize;

        public Integer getFetchSize() {
            return fetchSize;
        }

        public void setFetchSize(Integer fetchSize) {
            this.fetchSize = fetchSize;
        }
    }

    /** {@code modelquery.mysql.*}. */
    public static class Mysql {
        private MysqlStreamingMode streamingMode;

        public MysqlStreamingMode getStreamingMode() {
            return streamingMode;
        }

        public void setStreamingMode(MysqlStreamingMode streamingMode) {
            this.streamingMode = streamingMode;
        }
    }

    /** {@code modelquery.keyset.*}. */
    public static class Keyset {
        private KeysetNullKeys nullKeys;

        public KeysetNullKeys getNullKeys() {
            return nullKeys;
        }

        public void setNullKeys(KeysetNullKeys nullKeys) {
            this.nullKeys = nullKeys;
        }
    }
}
