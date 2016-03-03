package org.zalando.planb.revocation.persistence;

import static com.datastax.driver.core.querybuilder.QueryBuilder.bindMarker;
import static com.datastax.driver.core.querybuilder.QueryBuilder.eq;
import static com.datastax.driver.core.querybuilder.QueryBuilder.gt;

import java.io.IOException;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.zalando.planb.revocation.config.properties.CassandraProperties;
import org.zalando.planb.revocation.domain.RevocationType;
import org.zalando.planb.revocation.util.LocalDateFormatter;

import com.datastax.driver.core.BoundStatement;
import com.datastax.driver.core.Cluster;
import com.datastax.driver.core.PreparedStatement;
import com.datastax.driver.core.RegularStatement;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.Row;
import com.datastax.driver.core.Session;
import com.datastax.driver.core.querybuilder.QueryBuilder;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

/**
 * TODO: small javadoc
 *
 * @author  <a href="mailto:rodrigo.reis@zalando.de">Rodrigo Reis</a>
 */
@Slf4j
public class CassandraStorage implements RevocationStore {

    public static final String REVOCATION_TABLE = "revocation";
    private static final RegularStatement SELECT_STATEMENT = QueryBuilder.select().column("revocation_type")
                                                                         .column("revocation_data").column("revoked_by")
                                                                         .column("revoked_at").from(REVOCATION_TABLE)
                                                                         .where(eq("bucket_date", bindMarker()))
                                                                         .and(eq("bucket_interval", bindMarker())).and(
                                                                             gt("revoked_at", bindMarker()));

    private static final RegularStatement INSERT_STATEMENT = QueryBuilder.insertInto(REVOCATION_TABLE)
                                                                         .value("bucket_date", bindMarker())
                                                                         .value("bucket_interval", bindMarker())
                                                                         .value("revocation_type", bindMarker())
                                                                         .value("revocation_data", bindMarker())
                                                                         .value("revoked_by", bindMarker()).value(
                                                                             "revoked_at", bindMarker());

    private final Session session;

    private final Long maxTimeDelta;

    private final PreparedStatement getFrom;

    private final PreparedStatement storeRevocation;

    private final Map<RevocationType, RevocationDataMapper> dataMappers = new EnumMap<>(RevocationType.class);

    private static class GlobalMapper implements RevocationDataMapper {
        @Override
        public RevocationData get(final String data) throws IOException {
            return mapper.readValue(data, StoredGlobal.class);
        }
    }

    private static class ClaimMapper implements RevocationDataMapper {
        @Override
        public RevocationData get(final String data) throws IOException {
            return mapper.readValue(data, StoredClaim.class);
        }
    }

    private static class TokenMapper implements RevocationDataMapper {
        @Override
        public RevocationData get(final String data) throws IOException {
            return mapper.readValue(data, StoredToken.class);
        }
    }

    public CassandraStorage(final CassandraProperties cassandraProperties) {
        Cluster cluster = Cluster.builder().addContactPoints(cassandraProperties.getContactPoints().split(","))
                                 .withClusterName(cassandraProperties.getClusterName())
                                 .withPort(cassandraProperties.getPort()).build();

        session = cluster.connect(cassandraProperties.getKeyspace());
        maxTimeDelta = cassandraProperties.getMaxTimeDelta();
        dataMappers.put(RevocationType.TOKEN, new TokenMapper());
        dataMappers.put(RevocationType.GLOBAL, new GlobalMapper());
        dataMappers.put(RevocationType.CLAIM, new ClaimMapper());

        getFrom = session.prepare(SELECT_STATEMENT);
        storeRevocation = session.prepare(INSERT_STATEMENT);
    }

    static final ObjectMapper mapper = new ObjectMapper();

    static class Bucket {
        public String date;
        public long interval;

        Bucket(final String d, final long i) {
            date = d;
            interval = i;
        }
    }

    private static final long BUCKET_LENGTH = 8 * 60 * 60 * 1000; // 8 Hours per bucket/row

    protected static List<Bucket> getBuckets(long from, final long currentTime) {
        List<Bucket> buckets = new ArrayList<>();

        final long maxTime = ((currentTime / BUCKET_LENGTH) * BUCKET_LENGTH) + BUCKET_LENGTH;
        log.debug("{} {}", currentTime, maxTime);

        do {
            String bucket_date = LocalDateFormatter.get().format(new Date(from));
            long bucket_interval = getInterval(from);

            buckets.add(new Bucket(bucket_date, bucket_interval));

            from += BUCKET_LENGTH;

            log.debug("adding bucket to list: {} {}", bucket_date, bucket_interval);

        } while (from < maxTime);

        return buckets;
    }

    @Override
    public Collection<StoredRevocation> getRevocations(final long from) {

        Collection<StoredRevocation> revocations = new ArrayList<>();

        long currentTime = System.currentTimeMillis();
        if ((currentTime - from) > maxTimeDelta) {
            throw new IllegalArgumentException("From Timestamp is too old!"); // avoid erroneous query of too many
                                                                              // buckets
        }

        for (Bucket b : getBuckets(from, currentTime)) {

            log.debug("Selecting bucket: {} {}", b.date, b.interval);

            ResultSet rs = session.execute(getFrom.bind(b.date, b.interval, from));
            List<Row> rows = rs.all();

            for (Row r : rows) {
                try {
                    RevocationType type = RevocationType.valueOf(r.getString("revocation_type").toUpperCase());
                    String unmapped_data = r.getString("revocation_data");
                    RevocationData data = dataMappers.get(type).get(unmapped_data);
                    StoredRevocation revocation = new StoredRevocation(data, type, r.getString("revoked_by"));
                    revocation.setRevokedAt(r.getLong("revoked_at"));
                    revocations.add(revocation);
                } catch (IOException ex) {
                    log.error("Failed to read revocation", ex);
                }
            }
        }

        return revocations;
    }

    protected static long getInterval(final long timestamp) {
        long hours = timestamp / 1000 / 60 / 60;
        return (hours % 24) / 8;
    }

    @Override
    public boolean storeRevocation(final StoredRevocation revocation) {
        String date = LocalDateFormatter.get().format(new Date(revocation.getRevokedAt()));
        long interval = getInterval(revocation.getRevokedAt());
        try {
            String data = mapper.writeValueAsString(revocation.getData());
            log.debug("Storing in bucket: {} {} {}", date, interval, data);

            BoundStatement bs = storeRevocation.bind(date, interval, revocation.getType().name(), data,
                    revocation.getRevokedBy(), revocation.getRevokedAt());
            session.execute(bs);
            return true;
        } catch (JsonProcessingException ex) {
            log.error("Failed to serialize json", ex);
            return false;
        }
    }
}
