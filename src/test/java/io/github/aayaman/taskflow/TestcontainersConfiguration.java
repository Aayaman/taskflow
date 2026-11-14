package io.github.aayaman.taskflow;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Real PostgreSQL and Redis for integration tests.
 * Image tags must match compose.yaml (enforced by {@link ContainerImagesTest}).
 */
@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

	static final String POSTGRES_IMAGE = "postgres:18.7-trixie";

	static final String REDIS_IMAGE = "redis:8.10.3-alpine";

	@Bean
	@ServiceConnection
	PostgreSQLContainer postgresContainer() {
		return new PostgreSQLContainer(DockerImageName.parse(POSTGRES_IMAGE));
	}

	@Bean
	@ServiceConnection(name = "redis")
	GenericContainer<?> redisContainer() {
		return new GenericContainer<>(DockerImageName.parse(REDIS_IMAGE)).withExposedPorts(6379);
	}

}
