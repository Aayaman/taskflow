package io.github.aayaman.taskflow.handler;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/**
 * All job handlers, by type. Fails application startup if a handler declares an invalid
 * type or two handlers declare the same type, because such handlers could never run as
 * intended.
 */
@Component
public class JobHandlerRegistry {

	private static final Pattern TYPE = Pattern.compile(JobHandler.TYPE_PATTERN);

	private final Map<String, JobHandler> handlers;

	public JobHandlerRegistry(List<JobHandler> handlers) {
		Map<String, JobHandler> byType = new TreeMap<>();
		for (JobHandler handler : handlers) {
			String type = handler.type();
			if (type == null || type.length() > JobHandler.TYPE_MAX_LENGTH || !TYPE.matcher(type).matches()) {
				throw new IllegalStateException("Job handler " + handler.getClass().getName()
						+ " declares invalid type '" + type + "'; expected UPPER_SNAKE_CASE, at most "
						+ JobHandler.TYPE_MAX_LENGTH + " characters");
			}
			JobHandler existing = byType.putIfAbsent(type, handler);
			if (existing != null) {
				throw new IllegalStateException("Job type '" + type + "' has two handlers: "
						+ existing.getClass().getName() + " and " + handler.getClass().getName());
			}
		}
		this.handlers = Collections.unmodifiableMap(byType);
	}

	public Optional<JobHandler> find(String type) {
		return Optional.ofNullable(handlers.get(type));
	}

	public boolean supports(String type) {
		return handlers.containsKey(type);
	}

	public Set<String> types() {
		return handlers.keySet();
	}

}
