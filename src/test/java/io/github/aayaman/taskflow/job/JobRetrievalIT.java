package io.github.aayaman.taskflow.job;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import io.github.aayaman.taskflow.TestJobHandlers;
import io.github.aayaman.taskflow.TestcontainersConfiguration;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({ TestcontainersConfiguration.class, TestJobHandlers.class })
class JobRetrievalIT {

	@Autowired
	private JsonMapper json;

	@Autowired
	private JdbcClient jdbc;

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
	void executionDetailsAreNullBeforeTheJobRuns() {
		JsonNode job = json.readTree(api.get(submit()).body());

		assertThat(job.has("startedAt")).isTrue();
		assertThat(job.path("startedAt").isNull()).isTrue();
		assertThat(job.path("finishedAt").isNull()).isTrue();
		assertThat(job.path("lastError").isNull()).isTrue();
	}

	@Test
	void showsExecutionDetailsOfFailedJob() {
		String id = submit();
		jdbc.sql("""
				update jobs set status = 'FAILED', worker_id = uuidv7(),
				    started_at = timestamptz '2026-11-21 10:00:00Z', finished_at = timestamptz '2026-11-21 10:00:05Z',
				    last_error = 'java.lang.IllegalStateException: boom'
				where id = cast(:id as uuid)""")
			.param("id", id)
			.update();

		JsonNode job = json.readTree(api.get(id).body());

		assertThat(job.path("status").asString()).isEqualTo("FAILED");
		assertThat(Instant.parse(job.path("startedAt").asString())).isEqualTo(Instant.parse("2026-11-21T10:00:00Z"));
		assertThat(Instant.parse(job.path("finishedAt").asString())).isEqualTo(Instant.parse("2026-11-21T10:00:05Z"));
		assertThat(job.path("lastError").asString()).isEqualTo("java.lang.IllegalStateException: boom");
	}

	@Test
	void doesNotExposeTheWorker() {
		String id = submit();
		jdbc.sql("update jobs set status = 'RUNNING', worker_id = uuidv7(), started_at = now() where id = cast(:id as uuid)")
			.param("id", id)
			.update();

		JsonNode job = json.readTree(api.get(id).body());

		assertThat(job.path("status").asString()).isEqualTo("RUNNING");
		assertThat(job.path("startedAt").isNull()).isFalse();
		assertThat(job.has("workerId")).isFalse();
	}

	private String submit() {
		return json.readTree(api.submit("""
				{"type": "SEND_EMAIL", "payload": {}}""").body()).path("id").asString();
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
