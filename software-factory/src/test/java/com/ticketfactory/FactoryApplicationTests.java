package com.ticketfactory;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class FactoryApplicationTests {

    @Autowired
    JdbcClient jdbc;

    @Test
    void contextLoadsAndFlywayCreatedSchema() {
        Integer tables = jdbc.sql("""
                SELECT count(*) FROM information_schema.tables
                WHERE table_name IN ('tickets','ticket_transitions','jobs')""")
                .query(Integer.class).single();
        assertThat(tables).isEqualTo(3);
    }
}
