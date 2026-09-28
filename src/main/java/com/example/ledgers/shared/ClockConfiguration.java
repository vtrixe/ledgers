package com.example.ledgers.shared;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class ClockConfiguration {

    /** Inject this instead of calling Instant.now(), so tests can fix the time. */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

}
