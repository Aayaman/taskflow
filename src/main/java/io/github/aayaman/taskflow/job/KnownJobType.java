package io.github.aayaman.taskflow.job;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.ElementType.RECORD_COMPONENT;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.util.regex.Pattern;

import io.github.aayaman.taskflow.handler.JobHandler;
import io.github.aayaman.taskflow.handler.JobHandlerRegistry;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

/**
 * The job type must have a registered handler. {@code null} and malformed types are left
 * to {@code @NotNull} and {@code @Pattern}, so a client gets one message per problem.
 */
@Documented
@Retention(RUNTIME)
@Target({ FIELD, PARAMETER, RECORD_COMPONENT })
@Constraint(validatedBy = KnownJobType.Validator.class)
public @interface KnownJobType {

	String message() default "has no handler";

	Class<?>[] groups() default {};

	Class<? extends Payload>[] payload() default {};

	/**
	 * Created by Spring's constraint validator factory, which injects the registry.
	 */
	class Validator implements ConstraintValidator<KnownJobType, String> {

		private static final Pattern TYPE = Pattern.compile(JobHandler.TYPE_PATTERN);

		private final JobHandlerRegistry handlers;

		Validator(JobHandlerRegistry handlers) {
			this.handlers = handlers;
		}

		@Override
		public boolean isValid(String type, ConstraintValidatorContext context) {
			if (type == null || type.length() > JobHandler.TYPE_MAX_LENGTH || !TYPE.matcher(type).matches()) {
				return true;
			}
			return handlers.supports(type);
		}

	}

}
