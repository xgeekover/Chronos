package io.chronos.app;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Phase 2 North-Star subset (§12 demo): real MariaDB source → Task SELECT → 2 mappings →
 * pipeline → current-value cache + node-local SQLite history.
 */
@SpringBootTest
@Testcontainers
@Import(TestcontainersConfiguration.class)
class CollectionE2ETest extends AbstractJdbcCollectionE2E {

    @Container
    static final MariaDBContainer<?> MARIADB =
            new MariaDBContainer<>(DockerImageName.parse("mariadb:11"));

    @Test
    void collectsFromMariaDbIntoCacheAndHistory() throws Exception {
        collectAndAssert("maria",
                MARIADB.getJdbcUrl(), MARIADB.getUsername(), MARIADB.getPassword(),
                "CREATE TABLE sensor (temp_c DOUBLE, humidity DOUBLE)",
                "INSERT INTO sensor (temp_c, humidity) VALUES (21.5, 47.0)",
                "SELECT temp_c, humidity FROM sensor");
    }
}
