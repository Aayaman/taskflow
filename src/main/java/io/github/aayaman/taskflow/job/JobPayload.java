package io.github.aayaman.taskflow.job;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.ElementType.RECORD_COMPONENT;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.nio.charset.StandardCharsets;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import tools.jackson.databind.JsonNode;

/**
 * A job payload must be a JSON object of at most {@link #MAX_BYTES} bytes,
 * measured as compact UTF-8 JSON. {@code null} is accepted; combine with {@code @NotNull}.
 */
@Documented
@Retention(RUNTIME)
@Target({ FIELD, PARAMETER, RECORD_COMPONENT })
@Constraint(validatedBy = JobPayload.Validator.class)
public @interface JobPayload {

	int MAX_BYTES = 64 * 1024;

	String message() default "is not a valid job payload";

	Class<?>[] groups() default {};

	Class<? extends Payload>[] payload() default {};

	class Validator implements ConstraintValidator<JobPayload, JsonNode> {

		@Override
		public boolean isValid(JsonNode value, ConstraintValidatorContext context) {
			if (value == null) {
				return true;
			}
			String violation = null;
			if (!value.isObject()) {
				violation = "must be a JSON object";
			}
			else if (value.toString().getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
				violation = "must be at most " + MAX_BYTES + " bytes";
			}
			if (violation == null) {
				return true;
			}
			context.disableDefaultConstraintViolation();
			context.buildConstraintViolationWithTemplate(violation).addConstraintViolation();
			return false;
		}

	}

}
