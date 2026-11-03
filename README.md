# Yomu

A lightweight JSON5 library for Java mod configuration files. Supports Java 8 and later, with no external runtime dependencies in the core module.

- JSON5 and strict JSON reading, writing, and streaming APIs
- Configuration editing that preserves comments and unknown fields, with safe saves through a temporary file
- An optional Java object mapping module (mapper)

## Installation

```kotlin
repositories {
    mavenLocal()
}

dependencies {
    implementation("com.sumirelabs:yomu:<version>")
    // Add this for Java object mapping
    implementation("com.sumirelabs:yomu-mapper:<version>")
}
```

## Usage

```java
import com.sumirelabs.yomu.Json5Object;
import com.sumirelabs.yomu.Yomu;

Json5Object config = Yomu.readObject("{enabled: true, interval: 20,}");
int interval = config.getInt("interval");
config.with("interval", 40);
String text = Yomu.stringify(config);
```

Use the Document API to write configuration changes back while preserving existing comments and unknown fields.

```java
import com.sumirelabs.yomu.Json5Document;
import java.nio.file.Path;
import java.nio.file.Paths;

Path path = Paths.get("config.json5");
Json5Document document = Json5Document.read(path);
document.getObject().with("interval", 40);
document.writeAtomicallyTo(path);
```

The Document API keeps the entire document in memory. Ordinary reading and writing do not preserve source comments, and strict JSON output omits comments. Handle `IOException` when using file APIs.

For Java object mapping, use `new Json5Mapper().read(text, Config.class)` with `com.sumirelabs.yomu.mapper.Json5Mapper`.

## Benchmarks

[Latest Java 8 / 25 comparison](BENCHMARKS.md)

## License

[Blue Oak Model License 1.0.0](LICENSE.md)
