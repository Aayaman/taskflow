package io.github.aayaman.taskflow.job;

import io.github.aayaman.taskflow.handler.JobHandler;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;

/**
 * Request body for submitting a job.
 * The type format mirrors the {@code jobs_type_format} database constraint, and the
 * type must have a registered handler.
 */
public record SubmitJobRequest(

		@Schema(description = "Job type, used to pick the handler", example = "SEND_EMAIL")
		@NotNull(message = "is required")
		@Size(max = JobHandler.TYPE_MAX_LENGTH, message = "must be at most 100 characters")
		@Pattern(regexp = JobHandler.TYPE_PATTERN, message = "must be UPPER_SNAKE_CASE, for example SEND_EMAIL")
		@KnownJobType
		String type,

		@Schema(description = "Input for the handler: a JSON object of at most 64 KB",
				example = "{\"to\": \"user-42\", \"template\": \"welcome\"}")
		@NotNull(message = "is required")
		@JobPayload
		JsonNode payload) {
}
