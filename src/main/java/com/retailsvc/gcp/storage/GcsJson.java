package com.retailsvc.gcp.storage;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Mapping of GCS JSON API payloads. Uses the Gson tree model rather than reflective binding, so it
 * needs no reachability metadata in a native image.
 */
final class GcsJson {

  private GcsJson() {}

  record Rewrite(boolean done, String rewriteToken) {}

  static String uploadMetadata(String name, String contentType, String crc32c) {
    var json = new JsonObject();
    json.addProperty("name", name);
    if (contentType != null) {
      json.addProperty("contentType", contentType);
    }
    json.addProperty("crc32c", crc32c);
    return json.toString();
  }

  /** The generation of an object resource. GCS encodes it as a string. */
  static long generation(String objectJson) {
    return JsonParser.parseString(objectJson).getAsJsonObject().get("generation").getAsLong();
  }

  static Rewrite rewrite(String json) {
    var rewrite = JsonParser.parseString(json).getAsJsonObject();
    var token = rewrite.get("rewriteToken");
    return new Rewrite(
        rewrite.get("done").getAsBoolean(),
        token == null || token.isJsonNull() ? null : token.getAsString());
  }
}
