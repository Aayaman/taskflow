package io.github.aayaman.taskflow.job;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/**
 * No job exists with the requested id. Rendered as a 404 Problem Detail.
 */
class JobNotFoundException extends ErrorResponseException {

	JobNotFoundException(UUID id) {
		super(HttpStatus.NOT_FOUND, ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Job " + id + " was not found."),
				null);
	}

}
