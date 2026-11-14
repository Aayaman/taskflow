package io.github.aayaman.taskflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class ApplicationHealthIT {

	private final HttpClient http = HttpClient.newHttpClient();

	@Value("${local.server.port}")
	private int port;

	@Autowired
	private JsonMapper json;

	@Test
	void healthIsUpWithDatabaseAndRedis() throws Exception {
		HttpResponse<String> response = get("/actuator/health");

		assertThat(response.statusCode()).isEqualTo(200);
		JsonNode health = json.readTree(response.body());
		assertThat(health.path("status").asString()).isEqualTo("UP");
		assertThat(health.path("components").path("db").path("status").asString()).isEqualTo("UP");
		assertThat(health.path("components").path("redis").path("status").asString()).isEqualTo("UP");
	}

	@Test
	void healthDoesNotExposeComponentDetails() throws Exception {
		JsonNode health = json.readTree(get("/actuator/health").body());

		assertThat(health.path("components").path("db").has("details")).isFalse();
		assertThat(health.path("components").path("redis").has("details")).isFalse();
	}

	@Test
	void otherActuatorEndpointsAreNotExposed() throws Exception {
		assertThat(get("/actuator/env").statusCode()).isEqualTo(404);
		assertThat(get("/actuator/beans").statusCode()).isEqualTo(404);
	}

	private HttpResponse<String> get(String path) throws IOException, InterruptedException {
		HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build();
		return http.send(request, HttpResponse.BodyHandlers.ofString());
	}

}
