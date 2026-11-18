# Class transformation coordination

Live Tracer, CFG block tracing, Field Writes, API Hooks, and File Monitor compose on the same runtime class definition. Stopping a probe removes its layer without removing the others. A class with the same name defined by another classloader is not the same target. Field Writes instruments only writer definitions that resolve the selected declaring class.

JPI starts from the class bytecode before its temporary probes, or from the latest user patch. It applies CFG counters first so their block IDs match the static graph, then field-write probes, method tracing, API hooks, and file monitoring. It checks the composed class schema and sends the result to the JVM for verification. The coordinator commits the new state only after redefinition succeeds, using Java Instrumentation or the bound JVMTI backend with redefinition support.

Patch history and normal class inspection use the definition without temporary probes. Explicit JVMTI class capture can show runtime bytecode. Rollback changes the user bytecode and reapplies the probes that are still active.

If a patch or rollback changes a traced CFG's block topology or a monitored layer's number of sites, JPI rejects that definition change. The previous definition and its probes stay active. Stop the named probe, retry the change, and then start a new probe if needed. Existing counters and events span compatible changes. Bulk hook operations can skip individual definitions and report the first failure.

The coordinator retains at most 128 MiB of base and original bytecode for active definitions. It rejects individual transformation inputs and outputs larger than 24 MiB; the active backend can impose stricter limits. This coordination covers JPI-managed changes, not independent class redefinitions performed by another tool.

## Verification

Run the integration test with a bundled native build to require both backends:

```powershell
mvn --batch-mode --no-transfer-progress verify '-Dit.test=ClassTransformationIT' '-Djpi.native.required=true'
```

The fixture checks all six start and stop orders for Live Tracer, CFG, and Field Writes, independent classloaders, compatible patch and rollback, rejection of an incompatible patch, and concurrent API and file observation.
