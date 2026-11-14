package io.github.aayaman.taskflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(TestcontainersConfiguration.class)
class RedisConnectivityIT {

	@Autowired
	private RedisConnectionFactory connectionFactory;

	@Autowired
	private StringRedisTemplate redis;

	@Test
	void respondsToPing() {
		try (RedisConnection connection = connectionFactory.getConnection()) {
			assertThat(connection.ping()).isEqualTo("PONG");
		}
	}

	@Test
	void runsRedisMajorVersion8() {
		try (RedisConnection connection = connectionFactory.getConnection()) {
			String version = connection.serverCommands().info("server").getProperty("redis_version");

			assertThat(version).startsWith("8.");
		}
	}

	@Test
	void storesValueWithExpiry() {
		String key = "taskflow:it:" + UUID.randomUUID();
		try {
			redis.opsForValue().set(key, "ok", Duration.ofSeconds(30));

			assertThat(redis.opsForValue().get(key)).isEqualTo("ok");
			assertThat(redis.getExpire(key)).isPositive();
		}
		finally {
			redis.delete(key);
		}
	}

}
