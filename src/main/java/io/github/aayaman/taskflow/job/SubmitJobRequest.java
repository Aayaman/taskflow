package io.github.aayaman.taskflow.job;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;

/**
 * Request body for submitting a job.
 * The type rules mirror the {@code jobs_type_format} database constraint.
 */
public record SubmitJobRequest(

		@NotNull(message = "is required")
		@Size(max = 100, message = "must be at most 100 characters")
		@Pattern(regexp = "[A-Z][A-Z0-9]*(_[A-Z0-9]+)*", message = "must be UPPER_SNAKE_CASE, for example SEND_EMAIL")
		String type,

		@NotNull(message = "is required")
		@JobPayload
		JsonNode payload) {
}
