package io.chronos.app;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Phase 1 demo-as-test: the whole Spring Boot context boots against a real PostgreSQL and
 * Flyway has migrated the metadata schema (§12 Phase 1 done = build + tests + demo).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ChronosApplicationTests {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void contextLoadsAndFlywayCreatedMetadataSchema() {
        Integer tables = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.tables "
                        + "WHERE table_schema = 'public' "
                        + "AND table_name IN ('device','node','tag','task','mapping_rule','app_user')",
                Integer.class);
        assertThat(tables).isEqualTo(6);
    }
}
