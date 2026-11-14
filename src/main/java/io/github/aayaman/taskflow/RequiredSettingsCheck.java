package io.github.aayaman.taskflow;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.util.StringUtils;

/**
 * Fails startup early when required secrets are missing.
 * <p>
 * Spring Boot leaves unresolved placeholders as literal text when binding
 * properties, so a missing password would otherwise surface later as a
 * misleading authentication error.
 */
final class RequiredSettingsCheck implements ApplicationListener<ApplicationEnvironmentPreparedEvent> {

	private static final List<String> REQUIRED = List.of(
			"spring.datasource.password",
			"spring.data.redis.password");

	@Override
	public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
		List<String> missing = findMissing(event.getEnvironment());
		if (!missing.isEmpty()) {
			throw new IllegalStateException("Missing required settings " + missing
					+ ". Copy .env.example to .env and fill in the passwords,"
					+ " or provide them as environment variables.");
		}
	}

	static List<String> findMissing(ConfigurableEnvironment environment) {
		List<String> missing = new ArrayList<>();
		for (String key : REQUIRED) {
			if (!isResolvable(environment, key)) {
				missing.add(key);
			}
		}
		return missing;
	}

	private static boolean isResolvable(ConfigurableEnvironment environment, String key) {
		try {
			return StringUtils.hasText(environment.getProperty(key));
		}
		catch (IllegalArgumentException unresolvedPlaceholder) {
			return false;
		}
	}

}
