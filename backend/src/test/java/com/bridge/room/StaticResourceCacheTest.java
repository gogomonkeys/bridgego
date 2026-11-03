package com.bridge.room;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.hamcrest.Matchers.containsString;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;

/** Loads the actual Boot resource configuration, not a manually configured handler. */
@SpringBootTest
@AutoConfigureMockMvc
// No mocks: avoid Mockito's agent attachment; exercise the real resource handler.
@TestExecutionListeners(DependencyInjectionTestExecutionListener.class)
class StaticResourceCacheTest {
  @Autowired MockMvc mvc;

  @ParameterizedTest
  @ValueSource(strings = {"Fri, 01 Feb 1980 00:00:00 GMT", "Thu, 31 Dec 2099 23:59:59 GMT"})
  void obsoleteDateValidatorsCannotHideCurrentStaticContent(String previousDate) throws Exception {
    mvc.perform(get("/pwa-cache-regression.js").header("If-Modified-Since", previousDate))
      .andExpect(status().isOk())
      .andExpect(header().doesNotExist("Last-Modified"))
      .andExpect(header().string("Cache-Control", "no-cache"))
      .andExpect(content().string(containsString("current-static-bytes")));
  }

  @Test void obsoleteEntityTagAndDateCannotProduceFalseNotModified() throws Exception {
    mvc.perform(get("/pwa-cache-regression.js")
        .header("If-None-Match", "\"previous-release\"")
        .header("If-Modified-Since", "Thu, 31 Dec 2099 23:59:59 GMT"))
      .andExpect(status().isOk())
      .andExpect(header().doesNotExist("Last-Modified"))
      .andExpect(content().string(containsString("current-static-bytes")));
  }

  @Test void headUsesSameSafeResourcePolicy() throws Exception {
    mvc.perform(head("/pwa-cache-regression.js")
        .header("If-Modified-Since", "Thu, 31 Dec 2099 23:59:59 GMT"))
      .andExpect(status().isOk())
      .andExpect(header().doesNotExist("Last-Modified"))
      .andExpect(header().string("Cache-Control", "no-cache"))
      .andExpect(content().string(""));
  }

  @Test void privateEndpointsRemainNoStore() throws Exception {
    mvc.perform(get("/api/me")).andExpect(status().isUnauthorized())
      .andExpect(header().string("Cache-Control", "no-store"));
    mvc.perform(get("/health/ready")).andExpect(status().isOk())
      .andExpect(header().string("Cache-Control", "no-store"));
  }
}
