package br.com.js.mailsender;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/** Exige Postgres de pe (compose.yaml). Rode com -Dtest.excludedGroups= -Dgroups=integration. */
@SpringBootTest
@ActiveProfiles("test")
@Tag("integration")
class MailsenderApplicationTests {

    @Test
    void contextLoads() {
    }
}
