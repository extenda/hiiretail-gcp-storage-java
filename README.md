# Extenda Hii Retail GCP Storage client
A Google Cloud Platform Storage client implemented for JDK 21+ (Virtual threads).

[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=extenda_hiiretail-gcp-storage-java&metric=alert_status&token=38cd6e4249d32992ab84592be19958602fb47b4d)](https://sonarcloud.io/dashboard?id=extenda_hiiretail-gcp-storage-java)
[![Coverage](https://sonarcloud.io/api/project_badges/measure?project=extenda_hiiretail-gcp-storage-java&metric=coverage&token=38cd6e4249d32992ab84592be19958602fb47b4d)](https://sonarcloud.io/dashboard?id=extenda_hiiretail-gcp-storage-java)
[![Code Smells](https://sonarcloud.io/api/project_badges/measure?project=extenda_hiiretail-gcp-storage-java&metric=code_smells&token=38cd6e4249d32992ab84592be19958602fb47b4d)](https://sonarcloud.io/dashboard?id=extenda_hiiretail-gcp-storage-java)

## :nut_and_bolt: Configuration

The library supports changing these settings, via environmental variables:

* `SERVICE_PROJECT_ID`

  The value of your GCP project id. Using `test-project` if not set.

* `STORAGE_EMULATOR_HOST`

  The host url to the emulator. ***Can also be set as system property, e.g. in tests.***

### Docker compose example

```yaml
services:
  cloudstorage:
    image: oittaa/gcp-storage-emulator
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

## :notebook_with_decorative_cover: Usage

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>com.google.cloud</groupId>
      <artifactId>libraries-bom</artifactId>
      <version>${version.google-cloud}</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>

<dependencies>
  <dependency>
    <groupId>com.google.cloud</groupId>
    <artifactId>google-cloud-storage</artifactId>
  </dependency>
</dependencies>
```

The library uses `SLF4J` as logging API, so make sure you have `log4j[2]` or `logback` or other
compatible implementation on the classpath.

## :scroll: Usage

```java
import com.retailsvc.gcp.pubsub.ObjectToBytesMapper;
import com.retailsvc.gcp.pubsub.PooledPublisherFactory;

ObjectMapper jsonMapper = new ObjectMapper();
ObjectToBytesMapper objectMapper = v -> ByteBuffer.wrap(jsonMapper.writeValueAsBytes(v));

PubSubClientFactory factory = new PubSubClientFactory(objectMapper,
  PooledPublisherFactory.defaultPool());

PubSubClient pubSubClient = factory.create("example.entities.v1");

Object payload = ...
/*
 'payload' could be any of the supported types:
  - String, such as "{ .. }", "my text" etc.
  - ByteBuffer
  - InputStream
  - Any Jackson serializable type such as Record class, List etc.
*/
Map<String, String> attributes = Map.of("Tenant-Id", "...", "key", "value");
pubSubClient.publish(payload, attributes);
```

## :wrench: Local development environment

* JDK 21+
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

## :information_desk_person: Contribution

Contributions to the project are welcome, but must adhere to a few guidelines:

 * [Conventional commits](https://www.conventionalcommits.org/en/v1.0.0/) should be followed
 * Install and use a `editorconfig` plugin to use the project supplied settings
