package com.bridge.room;

import org.springframework.boot.web.server.MimeMappings;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.servlet.server.ConfigurableServletWebServerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Serve the install manifest with its registered MIME type, not a download fallback. */
@Configuration
public class StaticMimeConfiguration {
  @Bean
  WebServerFactoryCustomizer<ConfigurableServletWebServerFactory> manifestMimeMapping() {
    return factory -> {
      MimeMappings mappings = new MimeMappings(MimeMappings.DEFAULT);
      mappings.add("webmanifest", "application/manifest+json");
      factory.setMimeMappings(mappings);
    };
  }
}
