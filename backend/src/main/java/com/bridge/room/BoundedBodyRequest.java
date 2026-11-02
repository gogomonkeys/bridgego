package com.bridge.room;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Caps chunked bodies too; a malformed/oversized JSON body is rejected without buffering it. */
final class BoundedBodyRequest extends HttpServletRequestWrapper {
  static final class TooLarge extends IOException {
    TooLarge() { super("Request body too large"); }
  }

  private final int limit;
  private ServletInputStream bounded;

  BoundedBodyRequest(HttpServletRequest request, int limit) {
    super(request);
    this.limit = limit;
  }

  @Override
  public ServletInputStream getInputStream() throws IOException {
    if (bounded == null) {
      ServletInputStream source = super.getInputStream();
      bounded = new ServletInputStream() {
        private int read;
        public int read() throws IOException {
          int value = source.read();
          if (value != -1 && ++read > limit) throw new TooLarge();
          return value;
        }
        public int read(byte[] data, int offset, int length) throws IOException {
          if (length == 0) return 0;
          int count = source.read(data, offset, Math.min(length, Math.max(1, limit - read + 1)));
          if (count > 0 && (read += count) > limit) throw new TooLarge();
          return count;
        }
        public boolean isFinished() { return source.isFinished(); }
        public boolean isReady() { return source.isReady(); }
        public void setReadListener(ReadListener listener) { source.setReadListener(listener); }
        public void close() throws IOException { source.close(); }
      };
    }
    return bounded;
  }

  @Override
  public BufferedReader getReader() throws IOException {
    return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
  }
}
