# Commands and keyboard workflow

Press **Ctrl+P** or select **Command Palette** in the sidebar. Type a command or view name, use Up and Down to choose a result, then press Enter. Escape closes the palette. All built-in views and currently registered plugin tabs have navigation commands.

The palette displays the original JVM symbol selected in the active view. It captures that selection before moving focus to its search field. Commands that need a method, field, target session, or paused debugger remain visible with a reason when unavailable. Availability is checked again when executing. A target or JDWP reconnection invalidates commands captured for the previous session.

## Shortcuts

| Shortcut | Action |
| --- | --- |
| Ctrl+P | Command palette |
| Ctrl+Shift+F | Search Everything |
| Ctrl+B | Add or remove a method breakpoint for the selected method |
| Ctrl+T | Start Live Tracer for the selected method |
| Ctrl+G | Open Call graph, filtered by the selected symbol when available |
| Ctrl+Alt+X | Open static Xrefs for the selected class, method, or field |
| Ctrl+Shift+C | Copy the qualified JVM identifier |
| F7 | Step into |
| F8 | Step over |
| Shift+F8 | Step out |
| F9 | Continue execution |

These bindings apply to the main JPI window, including its editors. They take precedence over editor bindings with the same keys. Dialogs keep their own input behavior. Holding a key does not repeatedly trigger a command. Keyboard handlers are removed when the JPI window closes.

Debugger commands require a JDWP connection. Stepping also requires a suspended debugger and selected thread; it uses the mode already selected in the Debugger view. F9 resumes the current stopped execution through the existing Continue action. These commands operate on the separately connected debugger JVM, so connect JDWP to the application you intend to debug. Method breakpoints retain the existing debugger's class-name scope, including matching definitions from multiple classloaders.

## Trace commands

The palette includes **Trace selected method**, **Trace field writes**, **Start difference trace (baseline)**, **Trace CFG blocks**, and **Open Live tracer**.

Starting a trace uses the selected module's current limits and capture settings. Method tracing installs a probe for the exact selected definition. Field tracing uses its definition ID when available. CFG tracing analyzes the method before installing a block probe. Difference tracing records the baseline with CFG branch capture enabled. Compatible probes can share a definition; see [Class transformation coordination](class-transformations.md).

Select a method row, method selector, method-body editor, CFG, trace event, Xrefs result, or debugger frame to provide a method context. A class list selection provides a class context. Class source alone does not infer a method from the text caret. Search Everything's query field deliberately has no selected-symbol context; focus a result row before using a symbol command there. Copying original mapping identifiers also works without an attached target.

## Qualified identifiers

Symbol context menus include **Copy qualified JVM identifier**. The same action is available in the palette and through Ctrl+Shift+C. It copies original runtime names and exact descriptors rather than user aliases:

```text
com/foo/Auth
com/foo/Auth.validate(Ljava/lang/String;)Z
com/foo/Auth.token:Ljava/lang/String;
```

Classloader IDs remain attached to analysis selections, but are not included in this textual JVM identifier. A literal or event without a known JVM symbol has no qualified identifier to copy. Code editor popups retain their editor actions and expose JPI actions in a submenu.
