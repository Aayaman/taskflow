package io.github.aayaman.taskflow.job;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpResponse;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import io.github.aayaman.taskflow.TestJobHandlers;
import io.github.aayaman.taskflow.TestcontainersConfiguration;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({ TestcontainersConfiguration.class, TestJobHandlers.class })
class JobRetrievalIT {

	@Autowired
	private JsonMapper json;

	private JobApiClient api;

	@BeforeEach
	void setUp(@Value("${local.server.port}") int port) {
		api = new JobApiClient(port, json);
	}

	@Test
	void locationFromSubmissionReturnsTheSameJob() {
		HttpResponse<String> submitted = api.submit("""
				{"type": "SEND_EMAIL", "payload": {"to": "user-7", "tags": ["a", "b"]}}""");
		URI location = URI.create(submitted.headers().firstValue("Location").orElseThrow());

		HttpResponse<String> fetched = api.get(location);

		assertThat(fetched.statusCode()).isEqualTo(200);
		assertThat(fetched.headers().firstValue("Content-Type")).hasValue("application/json");
		assertThat(json.readTree(fetched.body())).isEqualTo(json.readTree(submitted.body()));
	}

	@Test
	void returnsStoredFields() {
		String id = json.readTree(api.submit("""
				{"type": "RESIZE_IMAGE", "payload": {"width": 640}}""").body()).path("id").asString();

		JsonNode job = json.readTree(api.get(id).body());

		assertThat(job.path("id").asString()).isEqualTo(id);
		assertThat(job.path("type").asString()).isEqualTo("RESIZE_IMAGE");
		assertThat(job.path("status").asString()).isEqualTo("PENDING");
		assertThat(job.path("payload")).isEqualTo(json.readTree("{\"width\": 640}"));
	}

	@Test
	void returns404ForUnknownJob() {
		UUID unknown = UUID.randomUUID();

		JsonNode problem = api.assertProblem(api.get(unknown.toString()), 404);

		assertThat(problem.path("detail").asString()).isEqualTo("Job " + unknown + " was not found.");
	}

	@ParameterizedTest
	@ValueSource(strings = { "not-a-uuid", "12345", "0198c3a2-zzzz-7000-8000-000000000000" })
	void returns400ForMalformedId(String id) {
		JsonNode problem = api.assertProblem(api.get(id), 400);

		assertThat(problem.path("detail").asString()).isEqualTo("Parameter 'id' must be a UUID.");
	}

}
