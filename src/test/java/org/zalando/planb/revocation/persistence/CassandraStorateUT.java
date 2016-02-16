package org.zalando.planb.revocation.persistence;

import org.junit.Test;
import org.zalando.planb.revocation.LocalTimeFormatter;

import java.text.ParseException;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Created by jmussler on 16.02.16.
 */
public class CassandraStorateUT {

    @Test
    public void testSameInteral() throws ParseException {
        String dateString = "2016-02-16 14:00:00.000+00";
        Date fromDate = LocalTimeFormatter.get().parse(dateString);

        String current = "2016-02-16 14:30:00.000+00";
        Date currentDate = LocalTimeFormatter.get().parse(current);

        List<CassandraStorage.Bucket> buckets =CassandraStorage.getBuckets(fromDate.getTime(), currentDate.getTime());
        assertThat(buckets.size()).isEqualTo(1);
        assertThat(buckets.get(0).date).isEqualTo("2016-02-16");
        assertThat(buckets.get(0).interval).isEqualTo("1");
    }

    @Test
    public void testTwoIntervalsSameDay() throws ParseException {
        String dateString = "2016-02-16 14:00:00.000+00";
        Date fromDate = LocalTimeFormatter.get().parse(dateString);

        String current = "2016-02-16 16:00:00.000+00";
        Date currentDate = LocalTimeFormatter.get().parse(current);

        List<CassandraStorage.Bucket> buckets =CassandraStorage.getBuckets(fromDate.getTime(), currentDate.getTime());
        assertThat(buckets.size()).isEqualTo(2);
        assertThat(buckets.get(0).date).isEqualTo("2016-02-16");
        assertThat(buckets.get(0).interval).isEqualTo("1");


        assertThat(buckets.get(1).date).isEqualTo("2016-02-16");
        assertThat(buckets.get(1).interval).isEqualTo("2");
    }

    @Test
    public void testTwoIntervalsNextDay() throws ParseException {
        String dateString = "2016-02-16 21:00:00.000+00";
        Date fromDate = LocalTimeFormatter.get().parse(dateString);

        String current = "2016-02-17 01:00:00.000+00";
        Date currentDate = LocalTimeFormatter.get().parse(current);

        List<CassandraStorage.Bucket> buckets =CassandraStorage.getBuckets(fromDate.getTime(), currentDate.getTime());
        assertThat(buckets.size()).isEqualTo(2);
        assertThat(buckets.get(0).date).isEqualTo("2016-02-16");
        assertThat(buckets.get(0).interval).isEqualTo("2");

        assertThat(buckets.get(1).date).isEqualTo("2016-02-17");
        assertThat(buckets.get(1).interval).isEqualTo("0");
    }

    @Test
    public void testLongerInterval() throws ParseException {
        String dateString = "2016-02-16 01:00:00.000+00";
        Date fromDate = LocalTimeFormatter.get().parse(dateString);

        String current = "2016-02-17 01:00:00.000+00";
        Date currentDate = LocalTimeFormatter.get().parse(current);

        List<CassandraStorage.Bucket> buckets =CassandraStorage.getBuckets(fromDate.getTime(), currentDate.getTime());
        assertThat(buckets.size()).isEqualTo(4);
    }
}
