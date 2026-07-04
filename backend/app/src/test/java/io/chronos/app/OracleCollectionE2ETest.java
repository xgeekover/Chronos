package io.chronos.app;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.oracle.OracleContainer;
import org.testcontainers.utility.DockerImageName;

/** Same collection E2E as MariaDB, proving the JDBC adapter against Oracle (§12 Testcontainers). */
@Tag("heavydb") // large container image — excluded from the default `test`, run via `heavyDbTest`
@SpringBootTest
@Testcontainers
@Import(TestcontainersConfiguration.class)
class OracleCollectionE2ETest extends AbstractJdbcCollectionE2E {

    // gvenzl/oracle-free ships an arm64 image, so this runs natively on Apple Silicon.
    @Container
    static final OracleContainer ORACLE =
            new OracleContainer(DockerImageName.parse("gvenzl/oracle-free:slim-faststart"));

    @Test
    void collectsFromOracleIntoCacheAndHistory() throws Exception {
        collectAndAssert("oracle",
                ORACLE.getJdbcUrl(), ORACLE.getUsername(), ORACLE.getPassword(),
                "CREATE TABLE sensor (temp_c NUMBER, humidity NUMBER)",
                "INSERT INTO sensor (temp_c, humidity) VALUES (21.5, 47.0)",
                "SELECT temp_c, humidity FROM sensor");
    }
}
