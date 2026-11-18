package io.github.aayaman.taskflow.job;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/**
 * An idempotency key was reused for a request with a different type or payload.
 * Rendered as a 422 Problem Detail.
 */
public class IdempotencyKeyReusedException extends ErrorResponseException {

	public IdempotencyKeyReusedException() {
		super(HttpStatus.UNPROCESSABLE_CONTENT, ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT,
				"The Idempotency-Key was already used for a request with a different type or payload."), null);
	}

}
