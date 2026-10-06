package com.ticketfactory.web;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import org.springframework.stereotype.Component;

/** Formatting helpers for templates: {@code ${@fmt.usd(t.costUsd)}}. */
@Component("fmt")
public class Fmt {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'")
            .withZone(ZoneOffset.UTC);

    public String usd(BigDecimal v) {
        return v == null ? "—" : "$" + v.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    public String usdPrecise(BigDecimal v) {
        return v == null ? "—" : "$" + v.setScale(4, RoundingMode.HALF_UP).toPlainString();
    }

    public String duration(Long ms) {
        return ms == null ? "—" : duration(Duration.ofMillis(ms));
    }

    public String duration(Duration d) {
        if (d == null) {
            return "—";
        }
        long s = d.toSeconds();
        if (s < 60) {
            return s + "s";
        }
        if (s < 3600) {
            return (s / 60) + "m " + (s % 60) + "s";
        }
        return (s / 3600) + "h " + ((s % 3600) / 60) + "m";
    }

    public String between(Instant a, Instant b) {
        return a == null || b == null ? "—" : duration(Duration.between(a, b));
    }

    public String ts(Instant i) {
        return i == null ? "—" : TS.format(i);
    }

    public String percent(Double ratio) {
        return ratio == null ? "—" : Math.round(ratio * 1000) / 10.0 + "%";
    }

    public String tokens(long n) {
        if (n >= 1_000_000) {
            return String.format("%.2fM", n / 1_000_000.0);
        }
        if (n >= 1_000) {
            return String.format("%.1fk", n / 1_000.0);
        }
        return Long.toString(n);
    }
}
