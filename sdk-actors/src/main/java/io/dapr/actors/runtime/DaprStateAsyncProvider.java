/*
 * Copyright (c) Microsoft Corporation.
 * Licensed under the MIT License.
 */

package io.dapr.actors.runtime;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.dapr.actors.ActorId;
import io.dapr.serializer.DaprObjectSerializer;
import io.dapr.serializer.StringContentType;
import reactor.core.publisher.Mono;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * State Provider to interact with Dapr runtime to handle state.
 */
class DaprStateAsyncProvider {

  /**
   * Used to fix problem from Dapr's state response.
   */
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  /**
   * Shared Json Factory as per Jackson's documentation, used only for this class.
   */
  private static final JsonFactory JSON_FACTORY = new JsonFactory();

  /**
   * Dapr's client for Actor runtime.
   */
  private final DaprClient daprClient;

  /**
   * Serializer for state objects.
   */
  private final DaprObjectSerializer stateSerializer;

  /**
   * Flag determining if serializer's input and output contains a valid String.
   */
  private final boolean isStateString;

  /**
   * Instantiates a new Actor's state provider.
   *
   * @param daprClient      Dapr client for Actor runtime.
   * @param stateSerializer Serializer for state objects.
   */
  DaprStateAsyncProvider(DaprClient daprClient, DaprObjectSerializer stateSerializer) {
    this.daprClient = daprClient;
    this.stateSerializer = stateSerializer;
    this.isStateString = stateSerializer.getClass().getAnnotation(StringContentType.class) != null;
  }

  <T> Mono<T> load(String actorType, ActorId actorId, String stateName, Class<T> clazz) {
    Mono<byte[]> result = this.daprClient.getActorState(actorType, actorId.toString(), stateName);

    return result.flatMap(s -> {
      try {
        T response = this.stateSerializer.deserialize(fixDaprStateResponse(s), clazz);
        if (response == null) {
          return Mono.empty();
        }

        return Mono.just(response);
      } catch (IOException e) {
        return Mono.error(new RuntimeException(e));
      }
    });
  }

  Mono<Boolean> contains(String actorType, ActorId actorId, String stateName) {
    Mono<byte[]> result = this.daprClient.getActorState(actorType, actorId.toString(), stateName);
    return result.map(s -> true).defaultIfEmpty(false);
  }

  /**
   * Saves state changes transactionally.
   * [
   * {
   * "operation": "upsert",
   * "request": {
   * "key": "key1",
   * "value": "myData"
   * }
   * },
   * {
   * "operation": "delete",
   * "request": {
   * "key": "key2"
   * }
   * }
   * ]
   *
   * @param actorType    Name of the actor being changed.
   * @param actorId      Identifier of the actor being changed.
   * @param stateChanges Collection of changes to be performed transactionally.
   * @return Void.
   */
  Mono<Void> apply(String actorType, ActorId actorId, ActorStateChange... stateChanges) {
    if ((stateChanges == null) || stateChanges.length == 0) {
      return Mono.empty();
    }

    int count = 0;
    // Constructing the JSON via a stream API to avoid creating transient objects to be instantiated.
    byte[] payload = null;
    try (ByteArrayOutputStream writer = new ByteArrayOutputStream()) {
      JsonGenerator generator = JSON_FACTORY.createGenerator(writer);
      // Start array
      generator.writeStartArray();

      for (ActorStateChange stateChange : stateChanges) {
        if ((stateChange == null) || (stateChange.getChangeKind() == null)) {
          continue;
        }

        String operationName = stateChange.getChangeKind().getDaprStateChangeOperation();
        if ((operationName == null) || (operationName.length() == 0)) {
          continue;
        }

        count++;

        // Start operation object.
        generator.writeStartObject();
        generator.writeStringField("operation", operationName);

        // Start request object.
        generator.writeObjectFieldStart("request");
        generator.writeStringField("key", stateChange.getStateName());
        if ((stateChange.getChangeKind() == ActorStateChangeKind.UPDATE)
            || (stateChange.getChangeKind() == ActorStateChangeKind.ADD)) {
          byte[] data = this.stateSerializer.serialize(stateChange.getValue());
          if (data != null) {
            if (this.isStateString) {
              generator.writeStringField("value", new String(data));
            } else {
              generator.writeBinaryField("value", data);
            }
          }
        }
        // End request object.
        generator.writeEndObject();

        // End operation object.
        generator.writeEndObject();
      }

      // End array
      generator.writeEndArray();

      generator.close();
      writer.flush();
      payload = writer.toByteArray();
    } catch (IOException e) {
      e.printStackTrace();
      return Mono.error(e);
    }

    if (count == 0) {
      // No-op since there is no operation to be performed.
      Mono.empty();
    }

    return this.daprClient.saveActorStateTransactionally(actorType, actorId.toString(), payload);
  }

  /**
   * Workaround for a bug in Dapr's runtime where actor state is saved as a JSON string and
   * returned as-is without deserializing first.
   *
   * @param raw Bytes received from Dapr.
   * @return Corrected byte[].
   */
  private byte[] fixDaprStateResponse(byte[] raw) {
    if (raw == null) {
      return raw;
    }

    if (raw.length == 0) {
      return raw;
    }

    try {
      return OBJECT_MAPPER.readValue(raw, String.class).getBytes();
    } catch (IOException e) {
      // We could not fix it, so iit goes as-is.
      return raw;
    }
  }

}
