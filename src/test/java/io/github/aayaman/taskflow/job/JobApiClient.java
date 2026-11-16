package io.github.aayaman.taskflow.job;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Minimal HTTP client for the jobs API in integration tests, plus Problem Details assertions.
 */
class JobApiClient {

	private final HttpClient http = HttpClient.newHttpClient();

	private final int port;

	private final JsonMapper json;

	JobApiClient(int port, JsonMapper json) {
		this.port = port;
		this.json = json;
	}

	HttpResponse<String> submit(String body) {
		return send(HttpRequest.newBuilder(uri(""))
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(body))
			.build());
	}

	HttpResponse<String> get(String id) {
		return get(uri("/" + id));
	}

	HttpResponse<String> get(URI uri) {
		return send(HttpRequest.newBuilder(uri).GET().build());
	}

	HttpResponse<String> send(HttpRequest request) {
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

	URI uri(String path) {
		return URI.create("http://localhost:" + port + "/api/v1/jobs" + path);
	}

	JsonNode assertProblem(HttpResponse<String> response, int status) {
		assertThat(response.statusCode()).isEqualTo(status);
		assertThat(response.headers().firstValue("Content-Type")).hasValue("application/problem+json");
		JsonNode problem = json.readTree(response.body());
		assertThat(problem.path("status").asInt()).isEqualTo(status);
		return problem;
	}

	void assertValidationProblem(HttpResponse<String> response, String field, String messageStart) {
		JsonNode problem = assertProblem(response, 400);
		assertThat(problem.path("errors").values())
			.anySatisfy(error -> {
				assertThat(error.path("field").asString()).isEqualTo(field);
				assertThat(error.path("message").asString()).startsWith(messageStart);
			});
	}

}
