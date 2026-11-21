package com.buytogether;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

// Wiring check only: MongoDB connects lazily, so no database is needed as long as indexes are not created on start.
@SpringBootTest(properties = {
        "app.jwt.secret=test-only-secret-key-that-is-at-least-32-bytes",
        "spring.data.mongodb.auto-index-creation=false"
})
class BuyTogetherApplicationTests {

    @Test
    void contextLoads() {
    }
}
