# MCP analysis server

JPI exposes the JVM selected in its desktop window through a local STDIO MCP server. This mode requires Java 21 or newer and a packaged JPI JAR. It does not attach automatically or choose a process for the client.

## Connect a client

Configure your MCP client to launch JPI as a subprocess:

```json
{
  "mcpServers": {
    "jpi": {
      "command": "java",
      "args": ["-jar", "C:/tools/jpi.jar", "--mcp"]
    }
  }
}
```

Use the absolute path to your Java executable when `java` is not on the client's PATH. Start the MCP connection, then select a target and click Attach in the JPI window it opens. Open **AI / MCP** under Advanced to see server status, the selected target, and recent calls.

Use an agent from the same JPI build as the controller. A JVM still running an older JPI agent may need a restart before the new read-only thread snapshot operation is available.

The client-launched window owns this MCP session. A JPI window started separately has its own session. STDIO cannot connect to an already running desktop process. Closing the client input ends the transport and closes its JPI window; the target application is not terminated. Closing that JPI window also stops its MCP transport. Headless environments expose the transport without a window and have no way to select a target in this MVP.

The transport implements newline-delimited UTF-8 JSON-RPC and negotiates MCP 2025-11-25, 2025-06-18, or 2024-11-05. Standard output contains protocol messages only. Diagnostics and other controller console output go to standard error. No TCP or HTTP listener is opened for MCP.

## Tools

| Tool | Result |
| --- | --- |
| `jpi_get_session` | Session metadata, or `connected: false` |
| `jpi_search_classes` | Loaded definitions with canonical class IDs |
| `jpi_get_class` | Class hierarchy, fields, and method signatures |
| `jpi_get_method` | Exact method metadata and Code attribute byte length |
| `jpi_search_constants` | Constant Search results with method-level string users from existing Xrefs |
| `jpi_decompile_class` | Source from CFR, Vineflower, or Procyon |
| `jpi_decompile_method` | Exact method in a class shell with lambda helpers |
| `jpi_find_xrefs` | Existing static and observed method or class references |
| `jpi_build_cfg` | Basic blocks and control-flow edges |
| `jpi_build_call_graph` | Bounded outgoing exploration through the Xrefs engine |
| `jpi_get_threads` | Snapshot without enabling CPU or contention monitoring |
| `jpi_get_exceptions` | Already collected Exception Analyzer events |
| `jpi_get_target_fingerprint` | Existing Target Fingerprinting result |
| `jpi_investigate` | Existing Investigation Assistant ranking and evidence |

The `jpi://session` resource returns the same session metadata and also works without a target. JVM flags that the backend cannot determine are returned as `null`, not guessed. Tools needing a target return `NO_ACTIVE_SESSION` when none is selected.

Constant Search distinguishes method-level string literals from general constant-pool UTF8 matches. The latter can represent names or descriptors and may have no method attribution. Escaped control characters are identified explicitly; literal string values linked to methods are returned decoded. Exception results retain the existing analyzer's aggregate thread counts and throw locations rather than inventing unavailable thread names or messages.

For example, search for a class first:

```json
{"query":"AuthService","limit":50}
```

Use the returned `classId` in subsequent calls:

```json
{"classId":"c:18ab42","methodName":"login","descriptor":"(Ljava/lang/String;)Z"}
```

JPI retains its actual definition IDs rather than constructing new IDs from class names. Duplicate class names in different classloaders remain distinct. A static reference with no unique loaded definition has no resolved `classId`; its candidate IDs are included and the graph does not expand it. Already observed runtime references may be aggregated by name across classloaders, as in the desktop Xrefs view.

Loader IDs come from the existing ClassLoader Explorer snapshot. Captured-only definitions whose loader is no longer known can have a `null` loader ID while retaining their captured class ID and loader label.

## Read-only behavior

Every backend request passes through `JpiMcpFacade` and an explicit allowlist. The transport cannot expose arbitrary protocol operations. There are no MCP tools for attach, native loading, execution, patching, field writes, memory access, breakpoint changes, or recording and probe installation.

Investigation reads the existing trace history instead of consuming the live event queue used by the desktop. It does not remove evidence from Live Tracer or enable a new recording.

Reading class bytes can ask JPI to capture bytes through its existing instrumentation path. As with desktop decompilation, that can invoke other installed transformers during retransformation. MCP supplies no replacement bytecode. Decompilers run as bounded local helper processes in the controller environment; they never execute the inspected methods in the target.

Each tool pins the active session before entering the analysis queue. Requests check that identity before accessing JPI and before returning data. Long analyses include a snapshot fingerprint in their context. A different target or reconnection causes `SESSION_CHANGED`, even if the PID is reused. Loading more classes during one session does not itself mean the target changed. Fingerprints describe the loaded inventory at capture time and are not guaranteed stable build identifiers.

Source, constants, messages, and symbol names originate from the target. Clients should treat them as untrusted analysis data rather than instructions. Investigation scores are deterministic ranking heuristics, not probabilities or proof of an execution path.

## Bounds and errors

- Class search: 1 to 512 query characters and at most 500 matches.
- Constant search and Investigation: 2 to 200 characters, matching the existing engines.
- Constant search: at most 500 results; class metadata: at most 2,000 methods and 2,000 fields.
- Call graph: at most depth 8, 2,000 nodes, 8,000 edges, and 128 expanded requests.
- Threads: at most 2,048 entries and the existing analyzer's 24 stack frames per thread, below the MVP ceiling of 128.
- Exceptions: at most 500 entries, without starting JFR exception recording.
- Investigation: at most 100 ranked results, with the existing assistant's smaller exploration budgets.
- Class input: 4 MiB; decompiled source: 2 MiB; serialized tool data: 4 MiB.
- Input frame: 1 MiB with at most 64 levels of JSON nesting; analysis queue: 16 waiting requests.
- Each tool has a 30 second budget, including queue time. Client cancellation returns a controlled error. An outstanding socket request can finish after cancellation, but its result is discarded; cancelled work cannot issue the next backend request. Controller helper processes are terminated on cancellation or timeout.

Result limits set `truncated: true`. Large collections are reduced to fit the result budget. Xrefs and searches also have their existing scan bounds, so an empty result is not proof that no usage exists. CFG indexes are instruction indexes, not raw byte offsets. Bytecode-unavailable and target errors become controlled tool errors, without Java stack traces in protocol responses.

Errors contain `code`, `message`, and `retryable`. Tool failures use MCP `isError: true` with both text and structured content. Malformed JSON-RPC requests use protocol errors. Failed tools do not stop the MCP connection.

## Audit

Calls are recorded in `~/.jpi/mcp/audit.jsonl`, with timestamp, client name, tool, target PID, session ID, duration, status, result count, and error code. The log records start and completion, excludes arguments and analysis contents, rotates at 2 MiB, and retains three previous files. The desktop shows the latest 100 completed calls. If the audit log cannot be opened, a call fails before target analysis starts.

## Verification

```text
mvn --batch-mode --no-transfer-progress verify
```

Tests exercise initialization, tool discovery, missing sessions, protocol error recovery, output bounds, session changes, argument validation, monitoring flags, and the packaged STDIO entry point. Integration coverage runs all 14 tools against a local fixture JVM and checks all three embedded decompilers.

Protocol references: [STDIO transport](https://modelcontextprotocol.io/specification/2026-07-28/basic/transports) and [MCP tools](https://modelcontextprotocol.io/specification/2026-07-28/server/tools).
