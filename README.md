# Extenda Hii Retail GCP Storage client
Simple file operations (save, load, move, delete) on Google Cloud Storage for JDK 25+, over the GCS JSON API.

[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=extenda_hiiretail-gcp-storage-java&metric=alert_status&token=38cd6e4249d32992ab84592be19958602fb47b4d)](https://sonarcloud.io/dashboard?id=extenda_hiiretail-gcp-storage-java)
[![Coverage](https://sonarcloud.io/api/project_badges/measure?project=extenda_hiiretail-gcp-storage-java&metric=coverage&token=38cd6e4249d32992ab84592be19958602fb47b4d)](https://sonarcloud.io/dashboard?id=extenda_hiiretail-gcp-storage-java)
[![Code Smells](https://sonarcloud.io/api/project_badges/measure?project=extenda_hiiretail-gcp-storage-java&metric=code_smells&token=38cd6e4249d32992ab84592be19958602fb47b4d)](https://sonarcloud.io/dashboard?id=extenda_hiiretail-gcp-storage-java)

## Configuration

The library talks to the [GCS JSON API](https://cloud.google.com/storage/docs/json_api) with
`java.net.http.HttpClient`. It authenticates with Google application default credentials, scoped to
`devstorage.read_write`. That covers Cloud Run/GKE, service account keys, impersonation,
`gcloud auth application-default login` and workload identity federation.

These settings are read from system properties first, then environment variables. Blank values are
ignored.

* `STORAGE_EMULATOR_HOST`

  URL of an emulator, **including the scheme**, e.g. `http://localhost:9023`. When set, the client
  sends no real credentials.

* `STORAGE_REQUEST_TIMEOUT_SECONDS`

  Deadline for each request, including the response body. Defaults to `60`.
  `GcsClientFactory.withRequestTimeout(Duration)` takes precedence.

### Docker compose example

```yaml
services:
  cloudstorage:
    image: oittaa/gcp-storage-emulator:v2026.07.19
    command: >
      start
        --default-bucket=test-bucket
        --host 0.0.0.0
        --port 9023
    environment:
      STORAGE_DIR: cloudstorage
    volumes:
      - ./cloudstorage:/cloudstorage
    ports:
      - "9023:9023"
```

## Dependency

```xml
<dependency>
  <groupId>com.retailsvc</groupId>
  <artifactId>hiiretail-gcp-storage-java</artifactId>
  <version>x.y.z</version>
</dependency>
```

## Usage

Create one client and reuse it. It is thread safe.

```java
try (GcsClient client = new GcsClientFactory()
    .withRequestTimeout(Duration.ofSeconds(30)) // optional
    .withMaxConcurrency(8)                      // optional, parallel uploads in saveAll (default 16)
    .create()) {

  client.save("my-bucket", "reports/2026/report.pdf", pdf, "application/pdf"); // create only
  client.overwrite("my-bucket", "reports/latest.pdf", pdf, "application/pdf"); // create or replace
  Optional<byte[]> report = client.load("my-bucket", "reports/2026/report.pdf"); // empty if missing

  client.move("my-bucket", "reports/2026/report.pdf", "archive/2026/report.pdf"); // atomic
  client.moveAcrossBuckets("my-bucket", "archive/2026/report.pdf", "cold-bucket", "2026/report.pdf");
  client.delete("cold-bucket", "2026/report.pdf");

  Map<String, GcsClientException> failures = client.saveAll("my-bucket", files, "text/csv");
}
```

* **Integrity:** uploads send a crc32c of the content, and GCS rejects them if it doesn't match.
  `load` verifies the downloaded bytes against the stored crc32c and throws
  `ChecksumMismatchException` if they differ.
* **Existing files:** `save`, `saveAll`, `move` and `moveAcrossBuckets` never replace a file; they
  fail with `AlreadyExistsException`. Use `overwrite` to replace one.
* **Move:** `move` renames a file within a bucket atomically. `moveAcrossBuckets` copies the file to
  another bucket and then deletes the source; if the delete fails, both files remain. Emulators have
  no atomic move, so against one `move` copies and deletes too.
* **Batches:** `saveAll` saves the files in parallel, at most `withMaxConcurrency` (default 16) at a
  time across the client, and returns the failures by name in the files' iteration order.
* **Errors:** failures throw `GcsClientException`, whose `status()` is the HTTP status (0 when there
  was no response) and whose `retryable()` says whether retrying may help. The exceptions have
  public constructors, so test doubles can throw them.
* **Limits:** only in-memory `byte[]` content. Files stored with `Content-Encoding: gzip` fail
  checksum verification.

### Retrying after an error

The client never retries on its own. `retryable()` is true for timeouts, I/O errors, 408, 429 and
5xx responses, and for `ChecksumMismatchException`. Credential failures follow google-auth's own
verdict, so a revoked key is not retryable. Retry only retryable errors, with a backoff:

```java
static void withRetry(Runnable call) throws InterruptedException {
  var delay = Duration.ofMillis(200);
  for (int attempt = 1; ; attempt++) {
    try {
      call.run();
      return;
    } catch (GcsClientException e) {
      if (!e.retryable() || attempt == 5) {
        throw e;
      }
      Thread.sleep(delay);
      delay = delay.multipliedBy(2);
    }
  }
}
```

A retried `save` fails with `AlreadyExistsException` if an earlier attempt succeeded but its
response was lost. If the file can only be your own, treat that as done:

```java
try {
  withRetry(() -> client.save(bucket, name, content, contentType));
} catch (AlreadyExistsException e) {
  // saved by an earlier attempt
}
```

`saveAll` doesn't throw for individual files. Retry the ones that failed with a retryable error:

```java
Map<String, GcsClientException> failures = client.saveAll(bucket, files, contentType);
Map<String, byte[]> retry = failures.entrySet().stream()
    .filter(failure -> failure.getValue().retryable())
    .collect(Collectors.toMap(Map.Entry::getKey, failure -> files.get(failure.getKey())));
if (!retry.isEmpty()) {
  Thread.sleep(Duration.ofMillis(500));
  failures = client.saveAll(bucket, retry, contentType);
}
```

## Local development environment

* JDK 25+
* Docker, for the integration tests in `mvn verify`
* Python / pre-commit

### Building

```bash
$ mvn clean package
```

```bash
$ mvn verify
```

#### Install and run the pre-commit hooks before you submit code:

```bash
$ pre-commit install -t pre-commit -t commit-msg
```

## Contribution

Contributions to the project are welcome, but must adhere to a few guidelines:

 * [Conventional commits](https://www.conventionalcommits.org/en/v1.0.0/) should be followed
 * Install and use a `editorconfig` plugin to use the project supplied settings
