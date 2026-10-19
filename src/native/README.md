# Native backend

The JVMTI agent, memory operations and process injectors used by JPI.
Injectors support Windows and Linux x86_64 and require OS access to the target process.

## Layout

- `agent/` - JVMTI agent and communication bootstrap
- `memory/` - memory inspection and patching
- `injectors/linux/` - ptrace backend and assembly worker
- `injectors/windows/` - CreateRemoteThread backend
- `tests/` - Linux recovery tests and loader fixtures
- `cmake/` - library checksum generation

## Build

Requires JDK headers, CMake 3.24 or newer and a C++17 compiler.
Set `JAVA_HOME` to the JDK used for the build.
Run from the repository root:

```sh
cmake -S src/native -B target/native-build -DCMAKE_BUILD_TYPE=Release
cmake --build target/native-build --config Release
mvn package
```

Libraries and SHA-256 files go to `target/native-resources/native/`.
Maven includes them in `target/jpi.jar`.
Build on each platform to produce its libraries.

## Class archives

In **Native JVMTI > Class interception**, enable automatic saving and enter an
existing directory on the target machine. Each run creates a new folder with
numbered `.class` files and `manifest.tsv`. The manifest records sequence,
event kind, byte count and the class name encoded as modified UTF-8 hex.

Writing runs on a separate thread with a queue limited to 256 events and 16 MiB.
The configured quota covers class bytes, with at most 100,000 class files per run.
Class bytes are limited to 4 MiB per event. Check `archive_dropped` and
`archive_errors` for gaps. The archive receives events
before preview-buffer eviction, but capture is not lossless during overload.
Stop and disconnect wait for the queue to drain. Slow target storage can delay
completion. Previously written files remain on disk.
Captured bytes are the input seen by our JVMTI hook, not necessarily the final
bytes after another agent transforms a class.

For Linux recovery tests, add `-DJPI_BUILD_TEST_FIXTURES=ON` to the configure command, then run:

```sh
ctest --test-dir target/native-build --output-on-failure
```

Test fixtures stay in `target/native-test-fixtures/` and are not bundled in the JAR.
If an existing CMake build points to the old source location, use a new build directory.
