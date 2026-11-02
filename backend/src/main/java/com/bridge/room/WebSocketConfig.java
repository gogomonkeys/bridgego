package com.bridge.room;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.web.socket.config.annotation.*;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {
  private final RoomSocket handler;
  private final String[] origins;

  public WebSocketConfig(RoomSocket handler, Environment environment) {
    this.handler = handler;
    this.origins = AllowedOrigins.forProfile(
        environment.acceptsProfiles(Profiles.of("prod")),
        environment.getProperty("bridge.public-origin"));
  }

  public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
    registry
        .addHandler(handler, "/ws")
        .setAllowedOrigins(origins);
  }
}
