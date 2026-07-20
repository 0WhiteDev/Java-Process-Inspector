package dev.whitedev.jpi.ui.analysis;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class CfgGraphModel {
    final String className;
    final String methodName;
    final String descriptor;
    final int complexity;
    final int deadBlocks;
    final List<Block> blocks;
    final List<Edge> edges;
    final Map<String, Block> byId;
    long totalExecutions;
    boolean traceActive;

    private CfgGraphModel(String className, String methodName, String descriptor,
                          int complexity, int deadBlocks, List<Block> blocks, List<Edge> edges) {
        this.className = className;
        this.methodName = methodName;
        this.descriptor = descriptor;
        this.complexity = complexity;
        this.deadBlocks = deadBlocks;
        this.blocks = Collections.unmodifiableList(blocks);
        this.edges = Collections.unmodifiableList(edges);
        Map<String, Block> indexed = new LinkedHashMap<String, Block>();
        for (Block block : blocks) indexed.put(block.id, block);
        byId = Collections.unmodifiableMap(indexed);
        for (Edge edge : edges) {
            Block from = indexed.get(edge.from);
            Block to = indexed.get(edge.to);
            if (from != null) from.outgoing.add(edge);
            if (to != null) to.incoming.add(edge);
        }
    }

    static CfgGraphModel parse(String raw) {
        String className = "";
        String methodName = "";
        String descriptor = "";
        int complexity = 0;
        int dead = 0;
        List<Block> blocks = new ArrayList<Block>();
        List<Edge> edges = new ArrayList<Edge>();
        for (String line : raw.split("\n")) {
            if (line.isEmpty()) continue;
            String[] values = line.split("\t", -1);
            if ("G".equals(values[0]) && values.length == 8) {
                className = decoded(values[1]);
                methodName = decoded(values[2]);
                descriptor = decoded(values[3]);
                complexity = integer(values[6]);
                dead = integer(values[7]);
            } else if ("B".equals(values[0]) && values.length == 10) {
                blocks.add(new Block(values[1], integer(values[2]), integer(values[3]),
                        integer(values[4]), integer(values[5]), Boolean.parseBoolean(values[6]),
                        values[7], decoded(values[8]), decoded(values[9])));
            } else if ("E".equals(values[0]) && values.length == 5) {
                edges.add(new Edge(values[1], values[2], values[3], decoded(values[4])));
            }
        }
        if (className.isEmpty() || methodName.isEmpty() || blocks.isEmpty()) {
            throw new IllegalArgumentException("The target returned an incomplete CFG");
        }
        return new CfgGraphModel(className, methodName, descriptor, complexity, dead, blocks, edges);
    }

    Snapshot applySnapshot(String raw) {
        Map<Integer, Long> counts = new LinkedHashMap<Integer, Long>();
        boolean active = false;
        long startedAt = 0L;
        long stoppedAt = 0L;
        long total = 0L;
        String probeId = "";
        for (String line : raw.split("\n")) {
            if (line.isEmpty()) continue;
            String[] values = line.split("\t", -1);
            if ("S".equals(values[0]) && values.length == 6) {
                probeId = values[1];
                active = Boolean.parseBoolean(values[2]);
                startedAt = longValue(values[3]);
                stoppedAt = longValue(values[4]);
                total = longValue(values[5]);
            } else if ("H".equals(values[0]) && values.length == 3) {
                counts.put(Integer.valueOf(integer(values[1])), Long.valueOf(longValue(values[2])));
            }
        }
        for (int index = 0; index < blocks.size(); index++) {
            Long value = counts.get(Integer.valueOf(index));
            blocks.get(index).executions = value == null ? 0L : value.longValue();
        }
        totalExecutions = total;
        traceActive = active;
        return new Snapshot(probeId, active, startedAt, stoppedAt, total);
    }

    String details(Block block) {
        if (block == null) return "Select a basic block to inspect its instructions and control-flow relations.";
        StringBuilder output = new StringBuilder();
        output.append(block.id).append("\n");
        output.append("Instructions: ").append(block.startInstruction).append(" to ")
                .append(block.endInstruction).append("\n");
        output.append("Source lines: ").append(block.lineRange()).append("\n");
        output.append("Reachable: ").append(block.reachable ? "yes" : "no").append("\n");
        output.append("Executions: ").append(block.executions).append("\n");
        output.append("Immediate dominator: ")
                .append(block.immediateDominator.isEmpty() ? "none" : block.immediateDominator).append("\n");
        output.append("Dominators: ").append(block.dominators.isEmpty() ? "none" : block.dominators).append("\n");
        output.append("Predecessors: ").append(relations(block.incoming, true)).append("\n");
        output.append("Successors: ").append(relations(block.outgoing, false)).append("\n\n");
        output.append(block.instructions);
        return output.toString();
    }

    private static String relations(List<Edge> edges, boolean incoming) {
        if (edges.isEmpty()) return "none";
        StringBuilder output = new StringBuilder();
        for (Edge edge : edges) {
            if (output.length() > 0) output.append(", ");
            output.append(incoming ? edge.from : edge.to);
            if (!edge.label.isEmpty()) output.append(" [").append(edge.label).append(']');
            else if (!edge.kind.isEmpty()) output.append(" [").append(edge.kind.toLowerCase()).append(']');
        }
        return output.toString();
    }

    private static String decoded(String value) {
        if (value.isEmpty()) return "";
        return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
    }

    private static int integer(String value) {
        return Integer.parseInt(value);
    }

    private static long longValue(String value) {
        return Long.parseLong(value);
    }

    static final class Block {
        final String id;
        final int startInstruction;
        final int endInstruction;
        final int startLine;
        final int endLine;
        final boolean reachable;
        final String immediateDominator;
        final String dominators;
        final String instructions;
        final List<Edge> incoming = new ArrayList<Edge>();
        final List<Edge> outgoing = new ArrayList<Edge>();
        long executions;

        Block(String id, int startInstruction, int endInstruction, int startLine, int endLine,
              boolean reachable, String immediateDominator, String dominators, String instructions) {
            this.id = id;
            this.startInstruction = startInstruction;
            this.endInstruction = endInstruction;
            this.startLine = startLine;
            this.endLine = endLine;
            this.reachable = reachable;
            this.immediateDominator = immediateDominator;
            this.dominators = dominators;
            this.instructions = instructions;
        }

        String lineRange() {
            if (startLine < 0 && endLine < 0) return "not available";
            if (startLine == endLine || endLine < 0) return String.valueOf(startLine);
            return startLine + " to " + endLine;
        }

        String firstInstruction() {
            int separator = instructions.indexOf('\n');
            return separator < 0 ? instructions : instructions.substring(0, separator);
        }
    }

    static final class Edge {
        final String from;
        final String to;
        final String kind;
        final String label;

        Edge(String from, String to, String kind, String label) {
            this.from = from;
            this.to = to;
            this.kind = kind;
            this.label = label;
        }
    }

    static final class Snapshot {
        final String probeId;
        final boolean active;
        final long startedAt;
        final long stoppedAt;
        final long total;

        Snapshot(String probeId, boolean active, long startedAt, long stoppedAt, long total) {
            this.probeId = probeId;
            this.active = active;
            this.startedAt = startedAt;
            this.stoppedAt = stoppedAt;
            this.total = total;
        }
    }
}

