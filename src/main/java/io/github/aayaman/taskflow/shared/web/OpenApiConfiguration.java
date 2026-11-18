package io.github.aayaman.taskflow.shared.web;

import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.ProblemDetail;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import tools.jackson.databind.JsonNode;

/**
 * API metadata for the generated OpenAPI document. Paths and schemas come from the
 * controllers; this class only corrects the schemas springdoc cannot derive itself.
 */
@Configuration(proxyBeanMethods = false)
class OpenApiConfiguration {

	static {
		// springdoc introspects Jackson 3 JsonNode as a Java bean (isArray, isObject, ...).
		// On the wire it is arbitrary JSON; the API only accepts objects there.
		SpringDocUtils.getConfig().replaceWithSchema(JsonNode.class, new ObjectSchema());
		// Spring serializes ProblemDetail's extra properties (such as errors) at the top
		// level, not inside a nested "properties" field as the Java class suggests.
		SpringDocUtils.getConfig().replaceWithSchema(ProblemDetail.class, problemSchema());
	}

	@Bean
	OpenAPI taskflowOpenApi() {
		return new OpenAPI().info(new Info()
			.title("TaskFlow API")
			.version("v1")
			.description("Submit background jobs and track their status. Errors use RFC 9457 Problem Details.")
			.license(new License().name("MIT")));
	}

	private static Schema<?> problemSchema() {
		Schema<?> fieldError = new ObjectSchema()
			.addProperty("field", new StringSchema())
			.addProperty("message", new StringSchema());
		return new ObjectSchema()
			.description("RFC 9457 Problem Details")
			.addProperty("type", new StringSchema().format("uri"))
			.addProperty("title", new StringSchema())
			.addProperty("status", new IntegerSchema())
			.addProperty("detail", new StringSchema())
			.addProperty("instance", new StringSchema().format("uri"))
			.addProperty("errors", new ArraySchema().items(fieldError).description("Invalid fields; validation errors only"));
	}

}
