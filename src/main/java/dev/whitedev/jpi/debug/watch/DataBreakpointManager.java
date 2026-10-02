package dev.whitedev.jpi.debug.watch;

import com.sun.jdi.Value;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.event.ModificationWatchpointEvent;
import com.sun.jdi.request.EventRequest;
import com.sun.jdi.request.ModificationWatchpointRequest;
import dev.whitedev.jpi.debug.BasicExpressionEvaluator;
import dev.whitedev.jpi.debug.BreakpointSpec;
import dev.whitedev.jpi.debug.ValueFormatter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class DataBreakpointManager {
    private final VirtualMachine vm;
    private final Map<Long, Entry> entries = new LinkedHashMap<>();
    private final ValueFormatter formatter = new ValueFormatter();
    private long identifiers;

    public DataBreakpointManager(VirtualMachine vm) { this.vm = vm; }

    public synchronized View add(String expression, BasicExpressionEvaluator.FieldTarget target, String condition,
                                 BreakpointSpec.SuspendPolicy policy) {
        if (!vm.canWatchFieldModification()) throw new IllegalStateException("Target JVM does not support field modification watchpoints");
        if (entries.size() >= 100) throw new IllegalStateException("Limit of 100 active data breakpoints reached");
        WatchCondition predicate = condition == null || condition.isBlank() ? null : WatchCondition.parse(condition);
        if (predicate != null) predicate.matches(target.field().isStatic() ? target.field().declaringType().getValue(target.field())
                : target.object().getValue(target.field()));
        long objectId = target.object() == null ? -1 : target.object().uniqueID();
        ModificationWatchpointRequest request = vm.eventRequestManager().createModificationWatchpointRequest(target.field());
        long id = ++identifiers;
        try {
            request.putProperty("jpi.data.id", id);
            request.setSuspendPolicy(policy == BreakpointSpec.SuspendPolicy.ALL ? EventRequest.SUSPEND_ALL : EventRequest.SUSPEND_EVENT_THREAD);
            Entry entry = new Entry(id, expression, target.field().declaringType().name() + '.' + target.field().name(),
                    objectId, predicate, condition == null ? "" : condition.trim(), policy, request);
            entries.put(id, entry);
            request.enable();
            return entry.view();
        } catch (RuntimeException error) {
            entries.remove(id);
            vm.eventRequestManager().deleteEventRequest(request);
            throw error;
        }
    }

    public synchronized Hit handle(ModificationWatchpointEvent event) {
        Object property = event.request().getProperty("jpi.data.id");
        Entry entry = property instanceof Long id ? entries.get(id) : null;
        if (entry == null || !entry.enabled) return null;
        if (entry.objectId >= 0 && (event.object() == null || event.object().uniqueID() != entry.objectId)) return null;
        try {
            Value before = event.valueCurrent(), after = event.valueToBe();
            boolean stop = entry.condition == null ? WatchCondition.changed(before, after) : entry.condition.matches(after);
            if (!stop) return null;
            entry.hits++;
            entry.lastWrite = formatter.format(before) + " -> " + formatter.format(after) + " (pending write)";
            return new Hit(entry.id, entry.expression + ": " + entry.lastWrite);
        } catch (RuntimeException error) {
            entry.error = "Condition unavailable: " + error.getMessage();
            entry.enabled = false;
            entry.request.disable();
            return new Hit(entry.id, entry.error + ". Data breakpoint disabled; target remains suspended for inspection.");
        }
    }

    public synchronized List<View> snapshot() { return entries.values().stream().map(Entry::view).toList(); }

    public synchronized void setEnabled(long id, boolean enabled) {
        Entry entry = require(id);
        entry.request.setEnabled(enabled);
        entry.enabled = enabled;
    }

    public synchronized void remove(long id) {
        Entry entry = entries.remove(id);
        if (entry != null) vm.eventRequestManager().deleteEventRequest(entry.request);
    }

    public synchronized void clear() { for (long id : List.copyOf(entries.keySet())) remove(id); }

    private Entry require(long id) {
        Entry entry = entries.get(id);
        if (entry == null) throw new IllegalArgumentException("Data breakpoint no longer exists");
        return entry;
    }

    private static final class Entry {
        final long id, objectId;
        final String expression, field, text;
        final WatchCondition condition;
        final BreakpointSpec.SuspendPolicy policy;
        final ModificationWatchpointRequest request;
        long hits;
        boolean enabled = true;
        String lastWrite = "", error = "";
        Entry(long id, String expression, String field, long objectId, WatchCondition condition, String text,
              BreakpointSpec.SuspendPolicy policy, ModificationWatchpointRequest request) {
            this.id = id; this.expression = expression; this.field = field; this.objectId = objectId;
            this.condition = condition; this.text = text; this.policy = policy; this.request = request;
        }
        View view() { return new View(id, expression, field, objectId, text.isEmpty() ? "Value changes" : text,
                policy, enabled, hits, lastWrite, error); }
    }

    public record View(long id, String expression, String field, long objectId, String condition,
                       BreakpointSpec.SuspendPolicy suspendPolicy, boolean enabled, long hits, String lastWrite, String error) {}
    public record Hit(long id, String details) {}
}
