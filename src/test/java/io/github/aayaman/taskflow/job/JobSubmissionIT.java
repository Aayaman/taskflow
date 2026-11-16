package io.github.aayaman.taskflow.job;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import io.github.aayaman.taskflow.TestcontainersConfiguration;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class JobSubmissionIT {

	private final HttpClient http = HttpClient.newHttpClient();

	@Value("${local.server.port}")
	private int port;

	@Autowired
	private JsonMapper json;

	@Autowired
	private JdbcClient jdbc;

	@Test
	void acceptsValidJob() {
		HttpResponse<String> response = submit("""
				{"type": "GENERATE_INVOICE", "payload": {"orderId": "order-12345", "lines": [1, 2]}}""");

		assertThat(response.statusCode()).isEqualTo(201);
		JsonNode job = json.readTree(response.body());
		UUID id = UUID.fromString(job.path("id").asString());
		assertThat(id.version()).isEqualTo(7);
		assertThat(job.path("type").asString()).isEqualTo("GENERATE_INVOICE");
		assertThat(job.path("status").asString()).isEqualTo("PENDING");
		assertThat(job.path("payload")).isEqualTo(json.readTree("{\"orderId\": \"order-12345\", \"lines\": [1, 2]}"));
		assertThat(Instant.parse(job.path("runAt").asString())).isNotNull();
		assertThat(Instant.parse(job.path("createdAt").asString())).isNotNull();
		assertThat(response.headers().firstValue("Location"))
			.hasValueSatisfying(location -> assertThat(location).endsWith("/api/v1/jobs/" + id));
	}

	@Test
	void persistsSubmittedJob() {
		JsonNode job = json.readTree(submit("""
				{"type": "SEND_EMAIL", "payload": {"to": "user-42"}}""").body());

		String storedPayload = jdbc.sql("select payload::text from jobs where id = :id and status = 'PENDING'")
			.param("id", UUID.fromString(job.path("id").asString()))
			.query(String.class)
			.single();
		assertThat(json.readTree(storedPayload)).isEqualTo(json.readTree("{\"to\": \"user-42\"}"));
	}

	@Test
	void rejectsMissingFields() {
		HttpResponse<String> response = submit("{}");

		assertValidationProblem(response, "type", "is required");
		assertValidationProblem(response, "payload", "is required");
	}

	@ParameterizedTest
	@ValueSource(strings = { "send_email", "SEND-EMAIL", "SEND__EMAIL", "_SEND", "" })
	void rejectsMalformedType(String type) {
		HttpResponse<String> response = submit("{\"type\": \"" + type + "\", \"payload\": {}}");

		assertValidationProblem(response, "type", "must be UPPER_SNAKE_CASE");
	}

	@Test
	void rejectsTypeLongerThan100Characters() {
		HttpResponse<String> response = submit("{\"type\": \"" + "A".repeat(101) + "\", \"payload\": {}}");

		assertValidationProblem(response, "type", "must be at most 100 characters");
	}

	@ParameterizedTest
	@ValueSource(strings = { "[]", "\"text\"", "42", "true" })
	void rejectsPayloadThatIsNotAnObject(String payload) {
		HttpResponse<String> response = submit("{\"type\": \"SEND_EMAIL\", \"payload\": " + payload + "}");

		assertValidationProblem(response, "payload", "must be a JSON object");
	}

	@Test
	void acceptsPayloadOfExactly64Kilobytes() {
		// {"data":"..."} is 11 bytes of structure plus the string content.
		String payload = "{\"data\":\"" + "x".repeat(JobPayload.MAX_BYTES - 11) + "\"}";

		assertThat(submit("{\"type\": \"SEND_EMAIL\", \"payload\": " + payload + "}").statusCode()).isEqualTo(201);
	}

	@Test
	void rejectsPayloadLargerThan64Kilobytes() {
		String payload = "{\"data\":\"" + "x".repeat(JobPayload.MAX_BYTES - 10) + "\"}";

		HttpResponse<String> response = submit("{\"type\": \"SEND_EMAIL\", \"payload\": " + payload + "}");

		assertValidationProblem(response, "payload", "must be at most 65536 bytes");
	}

	@Test
	void rejectsUnknownFields() {
		HttpResponse<String> response = submit("""
				{"type": "SEND_EMAIL", "payload": {}, "priority": 9}""");

		JsonNode problem = assertProblem(response, 400);
		assertThat(problem.path("detail").asString()).isEqualTo("Unknown field 'priority'.");
	}

	@Test
	void rejectsMalformedJson() {
		JsonNode problem = assertProblem(submit("{\"type\": \"SEND_EMAIL\", "), 400);

		assertThat(problem.path("detail").asString()).isEqualTo("The request body is not valid JSON.");
	}

	@Test
	void rejectsNonJsonContentType() {
		HttpRequest request = HttpRequest.newBuilder(uri())
			.header("Content-Type", "text/plain")
			.POST(HttpRequest.BodyPublishers.ofString("SEND_EMAIL"))
			.build();

		assertProblem(send(request), 415);
	}

	private void assertValidationProblem(HttpResponse<String> response, String field, String messageStart) {
		JsonNode problem = assertProblem(response, 400);
		assertThat(problem.path("errors").values())
			.anySatisfy(error -> {
				assertThat(error.path("field").asString()).isEqualTo(field);
				assertThat(error.path("message").asString()).startsWith(messageStart);
			});
	}

	private JsonNode assertProblem(HttpResponse<String> response, int status) {
		assertThat(response.statusCode()).isEqualTo(status);
		assertThat(response.headers().firstValue("Content-Type")).hasValue("application/problem+json");
		JsonNode problem = json.readTree(response.body());
		assertThat(problem.path("status").asInt()).isEqualTo(status);
		return problem;
	}

	private HttpResponse<String> submit(String body) {
		return send(HttpRequest.newBuilder(uri())
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(body))
			.build());
	}

	private HttpResponse<String> send(HttpRequest request) {
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

	private URI uri() {
		return URI.create("http://localhost:" + port + "/api/v1/jobs");
	}

}
