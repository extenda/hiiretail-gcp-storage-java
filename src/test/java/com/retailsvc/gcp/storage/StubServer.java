package com.retailsvc.gcp.storage;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Local HTTP server that records requests and answers them with queued handlers, in order. */
final class StubServer implements AutoCloseable {

  record Recorded(String method, URI uri, Headers headers, byte[] body) {}

  @FunctionalInterface
  interface Responder {
    void respond(Recorded request, HttpExchange exchange) throws IOException;
  }

  private final HttpServer server;
  private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
  private final Queue<Responder> responses = new ConcurrentLinkedQueue<>();
  private final List<Recorded> requests = new CopyOnWriteArrayList<>();

  StubServer() {
    try {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    server.setExecutor(executor);
    server.createContext(
        "/",
        exchange -> {
          var request =
              new Recorded(
                  exchange.getRequestMethod(),
                  exchange.getRequestURI(),
                  new Headers(exchange.getRequestHeaders()),
                  exchange.getRequestBody().readAllBytes());
          requests.add(request);
          var responder = responses.poll();
          (responder == null ? respond(500, "no stubbed response") : responder)
              .respond(request, exchange);
        });
    server.start();
  }

  String url() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  void enqueue(Responder... responders) {
    responses.addAll(List.of(responders));
  }

  List<Recorded> requests() {
    return requests;
  }

  /** Responds with a body and response headers given as name/value pairs. */
  static Responder respond(int status, String body, String... headers) {
    return (request, exchange) -> {
      for (int i = 0; i < headers.length; i += 2) {
        exchange.getResponseHeaders().add(headers[i], headers[i + 1]);
      }
      var bytes = body.getBytes(UTF_8);
      exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
      exchange.getResponseBody().write(bytes);
      exchange.close();
    };
  }

  /** Sends headers promising a body, then never sends it. */
  static Responder stallBody() {
    return (request, exchange) -> {
      exchange.sendResponseHeaders(200, 10);
      exchange.getResponseBody().flush();
      try {
        // Never counted down: only the interrupt from close() ends the wait.
        new CountDownLatch(1).await();
      } catch (InterruptedException _) {
        Thread.currentThread().interrupt();
      }
    };
  }

  @Override
  public void close() {
    server.stop(0);
    executor.shutdownNow();
  }
}
