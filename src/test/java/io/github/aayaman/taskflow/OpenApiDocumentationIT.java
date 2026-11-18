package io.github.aayaman.taskflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class OpenApiDocumentationIT {

	private final HttpClient http = HttpClient.newHttpClient();

	@Value("${local.server.port}")
	private int port;

	@Autowired
	private JsonMapper json;

	private JsonNode spec;

	@BeforeEach
	void loadSpec() {
		HttpResponse<String> response = get("/v3/api-docs");
		assertThat(response.statusCode()).isEqualTo(200);
		spec = json.readTree(response.body());
	}

	@Test
	void documentsSubmissionWithAllStatusCodes() {
		JsonNode submit = spec.path("paths").path("/api/v1/jobs").path("post");

		assertThat(submit.path("responses").propertyNames()).containsExactlyInAnyOrder("200", "201", "400", "415", "422");
		assertThat(submit.path("responses").path("201").path("content").has("application/json")).isTrue();
		assertThat(submit.path("parameters").values())
			.anySatisfy(parameter -> {
				assertThat(parameter.path("name").asString()).isEqualTo("Idempotency-Key");
				assertThat(parameter.path("in").asString()).isEqualTo("header");
				assertThat(parameter.path("required").asBoolean()).isFalse();
				assertThat(parameter.path("schema").path("maxLength").asInt()).isEqualTo(255);
			});
	}

	@Test
	void documentsRetrievalWithAllStatusCodes() {
		JsonNode get = spec.path("paths").path("/api/v1/jobs/{id}").path("get");

		assertThat(get.path("responses").propertyNames()).containsExactlyInAnyOrder("200", "400", "404");
	}

	@Test
	void describesPayloadAsJsonObjectRatherThanJavaClass() {
		JsonNode payload = spec.path("components").path("schemas").path("SubmitJobRequest").path("properties").path("payload");

		assertThat(payload.path("type").asString()).isEqualTo("object");
		assertThat(payload.has("properties")).as("JsonNode getters must not leak into the schema").isFalse();
	}

	@Test
	void describesErrorsAsFlatProblemDetails() {
		JsonNode problem = spec.path("paths").path("/api/v1/jobs").path("post").path("responses").path("400")
			.path("content").path("application/problem+json").path("schema").path("properties");

		assertThat(problem.propertyNames()).contains("type", "title", "status", "detail", "instance", "errors")
			.doesNotContain("properties");
	}

	@Test
	void servesSwaggerUi() {
		HttpResponse<String> response = get("/swagger-ui/index.html");

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.body()).contains("swagger-ui");
	}

	private HttpResponse<String> get(String path) {
		HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build();
		try {
			return http.send(request, HttpResponse.BodyHandlers.ofString());
		}
		catch (IOException ex) {
			throw new IllegalStateException(ex);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(ex);
		}
	}

}
