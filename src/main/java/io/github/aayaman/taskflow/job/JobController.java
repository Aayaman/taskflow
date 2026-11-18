package io.github.aayaman.taskflow.job;

import java.net.URI;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import io.github.aayaman.taskflow.job.internal.JobService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/**
 * Jobs API. The OpenAPI annotations only state what springdoc cannot infer: the status
 * codes each endpoint returns and the Problem Details body of its errors.
 */
@RestController
@RequestMapping("/api/v1/jobs")
@Tag(name = "Jobs", description = "Submit jobs and look up their status")
class JobController {

	static final String IDEMPOTENCY_KEY = "Idempotency-Key";

	static final int MAX_IDEMPOTENCY_KEY_LENGTH = 255;

	private static final String PROBLEM_JSON = "application/problem+json";

	private final JobService jobService;

	JobController(JobService jobService) {
		this.jobService = jobService;
	}

	/**
	 * Returns 201 for a new job, or 200 with the original job when an idempotent
	 * submission is repeated.
	 */
	@PostMapping
	@Operation(summary = "Submit a job",
			description = "Persists the job as PENDING. Send an Idempotency-Key to make retries safe: "
					+ "repeating the same request with the same key returns the original job.")
	@ApiResponse(responseCode = "201", description = "Job created",
			headers = @Header(name = "Location", description = "URL of the new job"))
	@ApiResponse(responseCode = "200", description = "Repeated request with the same Idempotency-Key; the original job",
			headers = @Header(name = "Location", description = "URL of the original job"))
	@ApiResponse(responseCode = "400", description = "Invalid body or Idempotency-Key",
			content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "415", description = "Body is not application/json",
			content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "422", description = "Idempotency-Key already used for a different request",
			content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
	ResponseEntity<JobResponse> submit(@Valid @RequestBody SubmitJobRequest request,
			@Parameter(description = "Optional client-chosen key, for example a UUID, that deduplicates retries",
					schema = @Schema(minLength = 1, maxLength = MAX_IDEMPOTENCY_KEY_LENGTH))
			@RequestHeader(name = IDEMPOTENCY_KEY, required = false) @Nullable String idempotencyKey) {
		validateIdempotencyKey(idempotencyKey);
		JobService.Submission submission = jobService.submit(request.type(), request.payload(), idempotencyKey);
		Job job = submission.job();
		URI location = ServletUriComponentsBuilder.fromCurrentRequest()
			.path("/{id}")
			.buildAndExpand(job.id())
			.toUri();
		ResponseEntity.BodyBuilder response = submission.created() ? ResponseEntity.created(location)
				: ResponseEntity.ok().location(location);
		return response.body(JobResponse.from(job));
	}

	/**
	 * Checked by hand rather than with {@code @Size}: a constraint on a parameter switches
	 * Spring to method validation, which would also change how body errors are reported.
	 */
	private static void validateIdempotencyKey(@Nullable String key) {
		if (key != null && (key.isBlank() || key.codePointCount(0, key.length()) > MAX_IDEMPOTENCY_KEY_LENGTH)) {
			throw new ErrorResponseException(HttpStatus.BAD_REQUEST, ProblemDetail.forStatusAndDetail(
					HttpStatus.BAD_REQUEST,
					"Header '" + IDEMPOTENCY_KEY + "' must be 1 to " + MAX_IDEMPOTENCY_KEY_LENGTH + " characters."), null);
		}
	}

	@GetMapping("/{id}")
	@Operation(summary = "Get a job")
	@ApiResponse(responseCode = "200", description = "The job")
	@ApiResponse(responseCode = "400", description = "The id is not a UUID",
			content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "404", description = "No job with this id",
			content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
	JobResponse get(@Parameter(description = "Job id") @PathVariable UUID id) {
		return jobService.find(id).map(JobResponse::from).orElseThrow(() -> new JobNotFoundException(id));
	}

}
