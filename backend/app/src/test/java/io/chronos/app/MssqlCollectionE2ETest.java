package io.chronos.app;

import java.time.Duration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mssqlserver.MSSQLServerContainer;
import org.testcontainers.utility.DockerImageName;

/** Same collection E2E as MariaDB, proving the JDBC adapter against MS-SQL (§12 Testcontainers). */
@Tag("heavydb") // large container image — excluded from the default `test`, run via `heavyDbTest`
@SpringBootTest
@Testcontainers
@Import(TestcontainersConfiguration.class)
class MssqlCollectionE2ETest extends AbstractJdbcCollectionE2E {

    // MS-SQL has no arm64 image — runs under amd64 emulation, so allow a generous startup window.
    @Container
    static final MSSQLServerContainer MSSQL =
            new MSSQLServerContainer(DockerImageName.parse("mcr.microsoft.com/mssql/server:2022-latest"))
                    .acceptLicense()
                    .withStartupTimeout(Duration.ofMinutes(5));

    @Test
    void collectsFromMssqlIntoCacheAndHistory() throws Exception {
        // mssql-jdbc 12+ defaults to encrypt=true; disable TLS for the throwaway test container.
        String jdbcUrl = MSSQL.getJdbcUrl() + ";encrypt=false;trustServerCertificate=true";
        collectAndAssert("mssql",
                jdbcUrl, MSSQL.getUsername(), MSSQL.getPassword(),
                "CREATE TABLE sensor (temp_c FLOAT, humidity FLOAT)",
                "INSERT INTO sensor (temp_c, humidity) VALUES (21.5, 47.0)",
                "SELECT temp_c, humidity FROM sensor");
    }
}
