package io.github.aayaman.taskflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/**
 * Keeps local infrastructure and integration tests on the same image versions.
 */
class ContainerImagesTest {

	@Test
	void composeUsesSameImagesAsIntegrationTests() throws IOException {
		String compose = Files.readString(Path.of("compose.yaml"));

		assertThat(compose)
			.contains("image: " + TestcontainersConfiguration.POSTGRES_IMAGE)
			.contains("image: " + TestcontainersConfiguration.REDIS_IMAGE);
	}

}
