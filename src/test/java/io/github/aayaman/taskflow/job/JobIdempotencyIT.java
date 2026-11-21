package io.github.aayaman.taskflow.job;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
class JobIdempotencyIT {

	private static final String BODY = """
			{"type": "SEND_EMAIL", "payload": {"to": "user-1", "template": "welcome"}}""";

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
	void repeatedSubmissionReturnsOriginalJob() {
		String key = newKey();
		HttpResponse<String> first = api.submit(BODY, key);
		HttpResponse<String> second = api.submit(BODY, key);

		assertThat(first.statusCode()).isEqualTo(201);
		assertThat(second.statusCode()).isEqualTo(200);
		assertThat(json.readTree(second.body())).isEqualTo(json.readTree(first.body()));
		assertThat(second.headers().firstValue("Location")).isEqualTo(first.headers().firstValue("Location"));
		assertThat(jobsWithKey(key)).isEqualTo(1);
	}

	@Test
	void payloadIsComparedAsJsonNotAsText() {
		String key = newKey();
		api.submit(BODY, key);

		HttpResponse<String> reordered = api.submit("""
				{"payload": {"template":"welcome",   "to":"user-1"}, "type": "SEND_EMAIL"}""", key);

		assertThat(reordered.statusCode()).isEqualTo(200);
		assertThat(jobsWithKey(key)).isEqualTo(1);
	}

	@Test
	void rejectsKeyReusedWithDifferentPayload() {
		String key = newKey();
		api.submit(BODY, key);

		HttpResponse<String> response = api.submit("""
				{"type": "SEND_EMAIL", "payload": {"to": "user-2", "template": "welcome"}}""", key);

		JsonNode problem = api.assertProblem(response, 422);
		assertThat(problem.path("detail").asString()).contains("Idempotency-Key");
		assertThat(jobsWithKey(key)).isEqualTo(1);
	}

	@Test
	void rejectsKeyReusedWithDifferentType() {
		String key = newKey();
		api.submit(BODY, key);

		HttpResponse<String> response = api.submit("""
				{"type": "SEND_SMS", "payload": {"to": "user-1", "template": "welcome"}}""", key);

		api.assertProblem(response, 422);
	}

	@Test
	void submissionsWithoutKeyAreNeverDeduplicated() {
		JsonNode first = json.readTree(api.submit(BODY).body());
		JsonNode second = json.readTree(api.submit(BODY).body());

		assertThat(first.path("id")).isNotEqualTo(second.path("id"));
	}

	@Test
	void acceptsKeyOf255Characters() {
		assertThat(api.submit(BODY, "k".repeat(255)).statusCode()).isIn(200, 201);
	}

	@Test
	void rejectsKeyLongerThan255Characters() {
		JsonNode problem = api.assertProblem(api.submit(BODY, "k".repeat(256)), 400);

		assertThat(problem.path("detail").asString()).isEqualTo("Header 'Idempotency-Key' must be 1 to 255 characters.");
	}

	@Test
	void rejectsEmptyKey() {
		api.assertProblem(api.submit(BODY, ""), 400);
	}

	@Test
	void concurrentSubmissionsWithSameKeyCreateExactlyOneJob() throws Exception {
		int clients = 20;
		String key = newKey();
		CountDownLatch start = new CountDownLatch(1);
		List<Future<HttpResponse<String>>> futures = new ArrayList<>();
		try (ExecutorService pool = Executors.newFixedThreadPool(clients)) {
			for (int i = 0; i < clients; i++) {
				futures.add(pool.submit(() -> {
					start.await();
					return api.submit(BODY, key);
				}));
			}
			start.countDown();
			List<Integer> statuses = new ArrayList<>();
			List<String> ids = new ArrayList<>();
			for (Future<HttpResponse<String>> future : futures) {
				HttpResponse<String> response = future.get(30, TimeUnit.SECONDS);
				statuses.add(response.statusCode());
				ids.add(json.readTree(response.body()).path("id").asString());
			}

			assertThat(statuses).containsOnly(200, 201);
			assertThat(statuses).filteredOn(status -> status == 201).hasSize(1);
			assertThat(ids).hasSize(clients).containsOnly(ids.getFirst());
		}
		assertThat(jobsWithKey(key)).isEqualTo(1);
	}

	private int jobsWithKey(String key) {
		return jdbc.sql("select count(*) from jobs where idempotency_key = :key")
			.param("key", key)
			.query(Integer.class)
			.single();
	}

	private static String newKey() {
		return "test-" + UUID.randomUUID();
	}

}
