package dev.whitedev.jpi.debug;

import com.sun.jdi.AbsentInformationException;
import com.sun.jdi.Location;
import com.sun.jdi.Method;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.event.ClassPrepareEvent;
import com.sun.jdi.request.BreakpointRequest;
import com.sun.jdi.request.ClassPrepareRequest;
import com.sun.jdi.request.EventRequest;
import com.sun.jdi.request.EventRequestManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

public final class BreakpointManager {
    private final AtomicLong identifiers = new AtomicLong();
    private final VirtualMachine vm;
    private final EventRequestManager requests;
    private final Map<Long, Entry> entries = new LinkedHashMap<>();

    BreakpointManager(VirtualMachine vm) {
        this.vm = vm;
        requests = vm.eventRequestManager();
    }

    public synchronized BreakpointView add(BreakpointSpec spec) throws Exception {
        long id = identifiers.incrementAndGet();
        Entry entry = new Entry(id, spec);
        entries.put(id, entry);
        ClassPrepareRequest prepare = requests.createClassPrepareRequest();
        prepare.addClassFilter(spec.className());
        prepare.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
        prepare.putProperty("jpi.breakpoint.id", Long.valueOf(id));
        prepare.enable();
        entry.prepare = prepare;
        for (ReferenceType type : vm.classesByName(spec.className())) install(entry, type);
        return entry.view();
    }

    public synchronized void classPrepared(ClassPrepareEvent event) {
        for (Entry entry : entries.values()) {
            if (!entry.spec.className().equals(event.referenceType().name())) continue;
            try {
                install(entry, event.referenceType());
            } catch (Exception error) {
                entry.error = message(error);
            }
        }
    }

    public synchronized void setEnabled(long id, boolean enabled) {
        Entry entry = require(id);
        entry.spec = new BreakpointSpec(entry.spec.className(), entry.spec.methodName(), entry.spec.descriptor(),
                entry.spec.sourceLine(), entry.spec.codeIndex(), entry.spec.type(),
                entry.spec.suspendPolicy(), enabled);
        for (BreakpointRequest request : entry.breakpoints) request.setEnabled(enabled);
    }

    public synchronized void remove(long id) {
        Entry entry = entries.remove(id);
        if (entry == null) return;
        for (BreakpointRequest request : entry.breakpoints) requests.deleteEventRequest(request);
        if (entry.prepare != null) requests.deleteEventRequest(entry.prepare);
    }

    public synchronized List<BreakpointView> snapshot() {
        List<BreakpointView> values = new ArrayList<>();
        for (Entry entry : entries.values()) values.add(entry.view());
        return List.copyOf(values);
    }

    public synchronized void clear() {
        for (Long id : new ArrayList<>(entries.keySet())) remove(id.longValue());
    }

    private void install(Entry entry, ReferenceType type) throws Exception {
        List<Location> locations = locations(type, entry.spec);
        if (locations.isEmpty()) {
            entry.error = "Location is not available in " + type.name();
            return;
        }
        for (Location location : locations) {
            boolean duplicate = false;
            for (BreakpointRequest request : entry.breakpoints) {
                if (request.location().equals(location)) {
                    duplicate = true;
                    break;
                }
            }
            if (duplicate) continue;
            BreakpointRequest request = requests.createBreakpointRequest(location);
            request.setSuspendPolicy(entry.spec.suspendPolicy() == BreakpointSpec.SuspendPolicy.ALL
                    ? EventRequest.SUSPEND_ALL : EventRequest.SUSPEND_EVENT_THREAD);
            request.putProperty("jpi.breakpoint.id", Long.valueOf(entry.id));
            request.setEnabled(entry.spec.enabled());
            entry.breakpoints.add(request);
        }
        entry.error = "";
    }

    private static List<Location> locations(ReferenceType type, BreakpointSpec spec) throws Exception {
        if (spec.type() == BreakpointSpec.Type.LINE) {
            if (spec.sourceLine() == null || spec.sourceLine().intValue() < 1) {
                throw new IllegalArgumentException("Source line must be positive");
            }
            try {
                return type.locationsOfLine(spec.sourceLine().intValue());
            } catch (AbsentInformationException error) {
                throw new IllegalArgumentException("The class has no matching LineNumberTable", error);
            }
        }
        Method method = method(type, spec.methodName(), spec.descriptor());
        if (spec.type() == BreakpointSpec.Type.BYTECODE) {
            if (spec.codeIndex() == null || spec.codeIndex().longValue() < 0L) {
                throw new IllegalArgumentException("Bytecode index cannot be negative");
            }
            Location location = method.locationOfCodeIndex(spec.codeIndex().longValue());
            return location == null ? List.of() : List.of(location);
        }
        Location location = method.location();
        return location == null ? List.of() : Collections.singletonList(location);
    }

    private static Method method(ReferenceType type, String name, String descriptor) {
        for (Method method : type.methodsByName(name)) {
            if (descriptor.isEmpty() || descriptor.equals(method.signature())) return method;
        }
        throw new IllegalArgumentException("Method is not loaded: " + type.name() + "." + name + descriptor);
    }

    private Entry require(long id) {
        Entry entry = entries.get(Long.valueOf(id));
        if (entry == null) throw new IllegalArgumentException("Breakpoint does not exist: " + id);
        return entry;
    }

    private static String message(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    private static final class Entry {
        final long id;
        final List<BreakpointRequest> breakpoints = new ArrayList<>();
        BreakpointSpec spec;
        ClassPrepareRequest prepare;
        String error = "";

        Entry(long id, BreakpointSpec spec) {
            this.id = id;
            this.spec = spec;
        }

        BreakpointView view() {
            return new BreakpointView(id, spec, breakpoints.size(), error);
        }
    }

    public record BreakpointView(long id, BreakpointSpec spec, int installedLocations, String error) {
    }
}
