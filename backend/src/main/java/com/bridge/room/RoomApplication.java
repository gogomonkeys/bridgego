package com.bridge.room;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class RoomApplication {
  public static void main(String[] args) {
    SpringApplication.run(RoomApplication.class, args);
  }
}
