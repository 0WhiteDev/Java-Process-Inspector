# Search Everything

Press **Ctrl+Shift+F** from a workspace view, or select **Search Everything** in the sidebar. Enter at least two characters. Search starts after a short typing delay; Enter or **Search / Refresh** runs it immediately. Select a category to narrow the results, then double-click a row or press Enter to open it.

| Category | Searched data | Open result |
| --- | --- | --- |
| Classes | Original names of loaded definitions | Decompile that exact definition |
| Methods | Original owner, name, and descriptor | Method Xrefs |
| Fields | Original owner, name, and descriptor | Field Xrefs |
| Strings | Literal strings in available method bytecode | Decompile their declaring definition |
| Mappings | Customized aliases, original locations, tags, colors, and disabled entries | Select the workspace entry |
| Timeline events | Recorded summaries, details, threads, subjects, and values | Select the event in Runtime timeline |
| Notes | Comments attached to workspace entries | Select the annotated entry |
| Network events | Recorded network timeline events | Select the event in Runtime timeline |
| Exceptions | Existing Exception Analyzer types, counts, and creation locations | Select the exception type |

Search is case-insensitive. Separate words must all appear in the searchable data for a result. Exact title matches appear first, followed by prefixes, substrings, and location matches. Descriptors are JVM descriptors. User aliases are searchable under Mappings, while target symbol categories retain original runtime names.

Mapping and note results work without an attached JVM. They use the current deobfuscation workspace, including imported entries. Notes are workspace comments, not a separate notebook. Network and timeline results search activity already collected by JPI; opening global search does not start tracing, exception recording, or packet capture. Refresh to include newly recorded activity or edited mappings.

## Bounds and coverage

The desktop keeps at most 200 visible results per category. A `+` beside a count means that more matches may exist because the result cap or a scan limit was reached. Counts are observations within the available data, not guarantees about the entire JVM.

Each target scan considers at most 100,000 loaded definitions and reads available bytecode from at most 5,000 classes, with a 64 MiB bytecode budget and a cooperative five-second scan budget. Individual class files are capped at 4 MiB. Classes matching the query and non-bootstrap definitions are prioritized for bytecode analysis. Unavailable bytecode and scan limits are reported in result details. String excerpts are limited to 4,096 characters. Arrays and primitive types are omitted from symbol results.

The agent uses captured bytecode or existing class resources. Search does not request retransformation to fill missing bytecode and does not modify target classes. Dynamically loaded definitions can appear on the next refresh when their bytecode is available. Duplicate names in separate classloaders keep separate canonical class IDs, so opening a target result selects its exact definition.

Typing coalesces pending queries: only one target scan runs at a time and the latest query replaces intermediate requests. Results from an earlier query or session are discarded. An in-flight socket read may finish before the next query can start, but it does not run on Swing's event thread. Changing categories filters the bounded results already received rather than starting another target scan.

The controller and target agent must be from the same JPI build because Search Everything adds a protocol operation. Restart targets still using an older JPI agent before testing a new build.

## Verification

```text
mvn --batch-mode --no-transfer-progress verify
```

Tests cover duplicate classloader definitions, literal strings, per-category limits, unavailable bytecode, workspace and event correlation, offline navigation, query coalescing, stale responses, and real JVM inspection.
