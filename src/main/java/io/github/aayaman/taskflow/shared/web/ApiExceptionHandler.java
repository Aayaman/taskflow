package io.github.aayaman.taskflow.shared.web;

import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import tools.jackson.databind.exc.UnrecognizedPropertyException;

/**
 * Renders API errors as RFC 9457 Problem Details.
 * <p>
 * Extends Spring's handler, which already covers standard MVC errors (malformed JSON,
 * unsupported media type, ...), and adds the list of invalid fields to validation errors.
 */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

	@Override
	protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		ProblemDetail problem = ex.getBody();
		problem.setDetail("The request contains invalid fields.");
		problem.setProperty("errors", ex.getBindingResult().getAllErrors().stream().map(this::toFieldError).toList());
		return handleExceptionInternal(ex, problem, headers, status, request);
	}

	/**
	 * Explains unreadable bodies without exposing parser internals: names the unknown
	 * field if there is one, otherwise reports invalid JSON.
	 */
	@Override
	protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		String detail = (ex.getCause() instanceof UnrecognizedPropertyException unknown)
				? "Unknown field '" + unknown.getPropertyName() + "'."
				: "The request body is not valid JSON.";
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
		return handleExceptionInternal(ex, problem, headers, status, request);
	}

	private Map<String, String> toFieldError(ObjectError error) {
		String field = (error instanceof FieldError fieldError) ? fieldError.getField() : error.getObjectName();
		return Map.of("field", field, "message", String.valueOf(error.getDefaultMessage()));
	}

}
