# Investigation Cases

1. Attach to a target JVM you are authorized to inspect. Open **Investigation Cases**, click **New investigation**, enter a name, and click **Start recording**.
2. Perform the action you want to study. Start Live Tracer or other diagnostics explicitly if you need them. Case recording collects available observations; it never enables probes, exception recording, or file interception on its own.
3. Filter the catalog by class name or loader, select definitions, then click **Capture selected classes**. The catalog is refreshed at Stop. Use **Refresh catalog** to select a class that loaded after Start. You can add the current Investigation Assistant report and CFG coverage explicitly.
4. Click **Stop recording**, then **Save case**. If the target disconnects, the observations and classes already captured remain available as a partial case.
5. Use **Open offline** to reopen the file. Use **Saved classes** for decompilation, method Xrefs and static CFG; **Search** for classes, members, string literals, mappings, notes and observations; and **Timeline and traces** for runtime history, exceptions, the call graph and saved analysis. **Notes and bookmarks** can be edited and saved back to the case. Offline analysis does not connect to a JVM or re-execute any recorded action.

## Coverage and limits

The archive records timeline events that JPI publishes while recording and reads the retained Live Tracer history once at Stop. A trace baseline excludes earlier calls. Because the agent history is bounded and can be cleared, a recording may have gaps. The call graph is cumulative for the live inspection session. The exception report is a point-in-time snapshot of the existing analyzer, not a new exception recording. No polling or automatic probe install is needed for recording.

The class catalog preserves definition IDs and loader labels. Only explicitly captured class bytes support offline decompilation, CFG, and symbol or string analysis. Static Xrefs scan saved bytecode, and symbolic caller matches do not prove classloader resolution. Use **Save analysis in case** to keep an offline analysis report; imported CFG coverage preserves observed counts separately from a regenerated static CFG.

Recordings expire after 15 minutes and store at most 25,000 catalog entries, 512 saved classes, 4 MiB per class, 48 MiB of class bytecode, 6,000 timeline events and 6,000 traces. Additional section byte budgets can reduce coverage. The archive is a versioned ZIP with bounded entries and a 96 MiB uncompressed limit. It is read directly without extracting files. Unknown versions and invalid archive entries are rejected. The older Session Snapshot ZIP is a separate export.

## Privacy

By default JPI omits runtime arguments, return values, exception text and stacks, thread names, and free-form timeline details. With **Include sensitive runtime details**, bounded portions of these values can be saved. Class and method metadata, scalar timing and call IDs, the target fingerprint, and customized mappings (including mapping comments) can still be present by default. Selected class files and reports you add explicitly can contain secrets. The archive is not encrypted, so review it before sharing.

## Evidence Graph

Open **Evidence Graph** in the sidebar for evidence already available in the current session. **Refresh graph** does not start recordings or probes. For a saved case, use **Investigation Cases > Open offline > Evidence Graph**. Search for a method, event, or ID, select a node to reveal incoming and outgoing connections, then select a connection to see its source and basis. Double-click or press Enter to follow it. Definitions with saved bytecode can be opened from the graph.

- **STATIC:** An instruction in saved bytecode references a method or field. This does not establish the runtime target or loader.
- **OBSERVED:** Recorded trace parent or call ID, field writer, exception location, aggregate call-graph edge, or the explicit scope of a saved report. An aggregate edge does not identify a particular invocation.
- **TIME_CORRELATED:** The nearest recorded call start, within 2 seconds on the same recorded thread or 350 ms with no thread identity. Ties are not linked. This does not establish causation.
- **INFERRED:** A candidate, for example when a JFR exception site has one matching owner in the saved catalog but JFR did not identify the loader.

Live mode uses the existing Timeline and the most recently fetched Exception Analyzer and Call Graph snapshots. Saved cases also use retained trace history, saved bytecode, and pinned reports. CFG transitions, Field Writes and Trace Value enter through their existing timeline publishers; pinned CFG and Investigation reports are report nodes, not proof of data flow. The graph is a bounded view, with at most 5,000 nodes and 8,000 links, plus byte and scan-time budgets. Earlier timeline entries may have aged out before you refresh.

New archives store the graph in `analysis/evidence-graph.json` (case version 2). JPI still opens version 1 cases and builds their graph from the material they contain. No offline graph action runs application code. The graph follows the case's runtime privacy settings and can still contain sensitive data from selected bytecode or explicitly included evidence.
