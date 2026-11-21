package io.github.aayaman.taskflow.handler;

import java.util.UUID;

import tools.jackson.databind.JsonNode;

/**
 * What a handler receives about the job it executes.
 */
public record JobContext(UUID id, JsonNode payload) {
}
