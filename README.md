# ADB Relay JVM

A minimal **Kotlin/JVM** WebSocket relay server that pairs a `dev` client and a `device` client sharing the same session token, then forwards binary frames between them byte-for-byte. Compatible with the **Remote ADB bridge** flow used by **android-ide-extension** (`tools/remote-adb-relay/relay.cjs`).

- **Java 11** — Gradle toolchain + Kotlin `jvmTarget 11`
- **Stack** — Ktor (Netty) + WebSockets, kotlinx-serialization (handshake JSON)

---

## Use as a standalone server

### Run from source

```bash
./gradlew run
```

### Build a distribution

```bash
./gradlew installDist
./build/install/adb-relay-jvm/bin/adb-relay-jvm
```

### Configuration

| Environment variable | Default     | Description           |
|----------------------|-------------|-----------------------|
| `RELAY_HOST`         | `0.0.0.0`   | Bind address          |
| `RELAY_PORT`         | `18765`     | TCP listen port       |

Or pass the port as the first CLI argument:

```bash
./build/install/adb-relay-jvm/bin/adb-relay-jvm 9000
```

Binds to `0.0.0.0` by default so LAN devices (e.g. **adb-relay-android** over Wi-Fi) can reach `ws://<your-PC-LAN-IP>:18765`. For local-only testing set `RELAY_HOST=127.0.0.1`.

---

## Use as a library

### Gradle

```kotlin
// build.gradle.kts
dependencies {
    implementation("org.androidgradletools:adbrelay-jvm:0.1.1")
}
```

### Maven

```xml
<dependency>
    <groupId>org.androidgradletools</groupId>
    <artifactId>adbrelay-jvm</artifactId>
    <version>0.1.1</version>
</dependency>
```

### Embed in an existing Ktor application

```kotlin
import org.androidgradletools.adbrelay.relay.adbRelayModule

fun Application.module() {
    // ... your other modules
    adbRelayModule()
}
```

### Run as a standalone server programmatically

```kotlin
import org.androidgradletools.adbrelay.relay.AdbRelayServer

fun main() {
    AdbRelayServer(host = "0.0.0.0", port = 18765).start(wait = true)
}
```

Stop it later:

```kotlin
val server = AdbRelayServer()
server.start(wait = false)
// ...
server.stop()
```

### Required runtime dependencies

When using as a library, your project must include these on the classpath:

```kotlin
implementation(platform("io.ktor:ktor-bom:2.3.12"))
implementation("io.ktor:ktor-server-core")
implementation("io.ktor:ktor-server-netty")
implementation("io.ktor:ktor-server-websockets")
implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
```

---

## Protocol

Clients connect via WebSocket to `/`. The first message must be a UTF-8 JSON handshake:

```json
{ "v": 1, "role": "dev", "token": "<shared-token>" }
```

```json
{ "v": 1, "role": "device", "token": "<shared-token>" }
```

- One `dev` and one `device` with the same `token` are paired together.
- All binary frames received before pairing are buffered and flushed FIFO once paired.
- After pairing, binary frames are forwarded byte-for-byte without parsing.
- On disconnect, the peer's socket is closed and pending entries are cleaned up.

Full wire-level spec: [`prompts/specs.md`](prompts/specs.md).

---

## Extending to other languages

To implement a compatible relay in another stack (Python, Go, Node.js, etc.), use the normative spec with an LLM or your own checklist. Suggested prompt:

> Implement a standalone **ADB WebSocket relay server** that strictly follows the protocol in the attached `specs.md`: WebSocket on `/`, first message UTF-8 JSON handshake `{ "v": 1, "role": "dev" | "device", "token": "<string>" }`, reject a binary first frame, pair one `dev` and one `device` per identical `token`, buffer all binary WebSocket messages until pairing, flush buffers FIFO to the peer when paired, then forward each binary payload byte-for-byte without parsing mux. On disconnect, remove pending entries and close the paired socket. Make pairing map updates thread-safe. Include a small CLI (host, port, optional TLS notes in comments).

Attach [`prompts/specs.md`](prompts/specs.md) as the single source of truth.

Reference implementations:
- **JVM (this repo):** `src/main/kotlin/org/androidgradletools/adbrelay/relay/`
- **Node (upstream):** `android-ide-extension/tools/remote-adb-relay/relay.cjs`
- **Python:** [aioue/adb-relay-python](https://github.com/aioue/adb-relay-python) — standard library only. Its `tests/test_conformance.py` checks the section 10 compliance list and can be pointed at any relay with `ADB_RELAY_URL`.
