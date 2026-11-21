package io.github.aayaman.taskflow.job;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpRequest;
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
class JobSubmissionIT {

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
	void acceptsValidJob() {
		HttpResponse<String> response = api.submit("""
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
		JsonNode job = json.readTree(api.submit("""
				{"type": "SEND_EMAIL", "payload": {"to": "user-42"}}""").body());

		String storedPayload = jdbc.sql("select payload::text from jobs where id = :id and status = 'PENDING'")
			.param("id", UUID.fromString(job.path("id").asString()))
			.query(String.class)
			.single();
		assertThat(json.readTree(storedPayload)).isEqualTo(json.readTree("{\"to\": \"user-42\"}"));
	}

	@Test
	void rejectsMissingFields() {
		HttpResponse<String> response = api.submit("{}");

		api.assertValidationProblem(response, "type", "is required");
		api.assertValidationProblem(response, "payload", "is required");
	}

	@ParameterizedTest
	@ValueSource(strings = { "send_email", "SEND-EMAIL", "SEND__EMAIL", "_SEND", "" })
	void rejectsMalformedType(String type) {
		HttpResponse<String> response = api.submit("{\"type\": \"" + type + "\", \"payload\": {}}");

		api.assertValidationProblem(response, "type", "must be UPPER_SNAKE_CASE");
	}

	@Test
	void rejectsTypeWithoutHandler() {
		HttpResponse<String> response = api.submit("{\"type\": \"NO_SUCH_TYPE\", \"payload\": {}}");

		api.assertValidationProblem(response, "type", "has no handler");
	}

	@Test
	void reportsOnlyTheFormatProblemForMalformedType() {
		JsonNode problem = api.assertProblem(api.submit("{\"type\": \"no-such-type\", \"payload\": {}}"), 400);

		assertThat(problem.path("errors").values())
			.filteredOn(error -> error.path("field").asString().equals("type"))
			.singleElement()
			.satisfies(error -> assertThat(error.path("message").asString()).startsWith("must be UPPER_SNAKE_CASE"));
	}

	@Test
	void rejectsTypeLongerThan100Characters() {
		HttpResponse<String> response = api.submit("{\"type\": \"" + "A".repeat(101) + "\", \"payload\": {}}");

		api.assertValidationProblem(response, "type", "must be at most 100 characters");
	}

	@ParameterizedTest
	@ValueSource(strings = { "[]", "\"text\"", "42", "true" })
	void rejectsPayloadThatIsNotAnObject(String payload) {
		HttpResponse<String> response = api.submit("{\"type\": \"SEND_EMAIL\", \"payload\": " + payload + "}");

		api.assertValidationProblem(response, "payload", "must be a JSON object");
	}

	@Test
	void acceptsPayloadOfExactly64Kilobytes() {
		// {"data":"..."} is 11 bytes of structure plus the string content.
		String payload = "{\"data\":\"" + "x".repeat(JobPayload.MAX_BYTES - 11) + "\"}";

		assertThat(api.submit("{\"type\": \"SEND_EMAIL\", \"payload\": " + payload + "}").statusCode()).isEqualTo(201);
	}

	@Test
	void rejectsPayloadLargerThan64Kilobytes() {
		String payload = "{\"data\":\"" + "x".repeat(JobPayload.MAX_BYTES - 10) + "\"}";

		HttpResponse<String> response = api.submit("{\"type\": \"SEND_EMAIL\", \"payload\": " + payload + "}");

		api.assertValidationProblem(response, "payload", "must be at most 65536 bytes");
	}

	@Test
	void rejectsUnknownFields() {
		HttpResponse<String> response = api.submit("""
				{"type": "SEND_EMAIL", "payload": {}, "priority": 9}""");

		JsonNode problem = api.assertProblem(response, 400);
		assertThat(problem.path("detail").asString()).isEqualTo("Unknown field 'priority'.");
	}

	@Test
	void rejectsMalformedJson() {
		JsonNode problem = api.assertProblem(api.submit("{\"type\": \"SEND_EMAIL\", "), 400);

		assertThat(problem.path("detail").asString()).isEqualTo("The request body is not valid JSON.");
	}

	@Test
	void rejectsNonJsonContentType() {
		HttpRequest request = HttpRequest.newBuilder(api.uri(""))
			.header("Content-Type", "text/plain")
			.POST(HttpRequest.BodyPublishers.ofString("SEND_EMAIL"))
			.build();

		api.assertProblem(api.send(request), 415);
	}

}
