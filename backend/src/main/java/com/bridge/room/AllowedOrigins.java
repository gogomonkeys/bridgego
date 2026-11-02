package com.bridge.room;

import java.net.URI;
import java.util.List;

/** Production is one exact HTTPS origin, never a wildcard or a URL with a path. */
final class AllowedOrigins {
  private AllowedOrigins() {}

  static String[] forProfile(boolean production, String configured) {
    if (!production) {
      return List.of(
              "http://127.0.0.1:5173", "http://localhost:5173",
              "http://127.0.0.1:8080", "http://localhost:8080")
          .toArray(String[]::new);
    }
    String value = configured == null ? "" : configured.trim();
    try {
      URI uri = URI.create(value);
      if (!"https".equals(uri.getScheme()) || uri.getHost() == null
          || uri.getRawUserInfo() != null || uri.getRawQuery() != null
          || uri.getRawFragment() != null || !uri.getRawPath().isEmpty()
          || uri.getPort() == 0 || uri.getPort() > 65535
          || value.contains("*") || value.contains(",")) {
        throw new IllegalArgumentException();
      }
    } catch (IllegalArgumentException e) {
      throw new IllegalStateException(
          "PUBLIC_ORIGIN must be one HTTPS origin without path, credentials, query or wildcard");
    }
    return new String[] {value};
  }
}
