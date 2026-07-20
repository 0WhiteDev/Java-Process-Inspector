package dev.whitedev.jpi.agent.cfg;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;


import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

public final class BytecodeCfgAnalyzer {
    private static final int MAX_BLOCKS = 5000;
    private static final int MAX_EDGES = 20000;
    private static final int MAX_SUMMARY_INSTRUCTIONS = 80;
    private static final String RUNTIME = Type.getInternalName(CfgRuntime.class);
    private static final String HIT_DESCRIPTOR = "(Ljava/lang/String;I)V";
    private static final String[] OPCODE_NAMES = opcodeNames();

    private BytecodeCfgAnalyzer() {}

    public static String analyze(byte[] bytecode, String methodName, String descriptor) throws IOException {
        return graph(bytecode, methodName, descriptor).encode();
    }

    static Instrumented instrument(byte[] bytecode, String methodName, String descriptor,
                                   String probeId) throws IOException {
        Graph graph = graph(bytecode, methodName, descriptor);
        for (Block block : graph.blocks) {
            InsnList hit = new InsnList();
            hit.add(new LdcInsnNode(probeId));
            pushInt(hit, block.index);
            hit.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "hit", HIT_DESCRIPTOR, false));
            graph.method.instructions.insertBefore(block.first, hit);
        }
        ClassWriter writer = new ClassWriter(graph.reader, ClassWriter.COMPUTE_MAXS);
        graph.owner.accept(writer);
        return new Instrumented(writer.toByteArray(), graph.blocks.size());
    }

    private static Graph graph(byte[] bytecode, String methodName, String descriptor) throws IOException {
        try {
            ClassReader reader = new ClassReader(bytecode);
            ClassNode owner = new ClassNode(Opcodes.ASM9);
            reader.accept(owner, 0);
            MethodNode method = find(owner, methodName, descriptor);
            if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0
                    || method.instructions == null || method.instructions.size() == 0) {
                throw new IOException("Selected method has no executable bytecode");
            }

            List<AbstractInsnNode> instructions = new ArrayList<AbstractInsnNode>();
            List<Integer> lines = new ArrayList<Integer>();
            IdentityHashMap<AbstractInsnNode, Integer> ordinals = new IdentityHashMap<AbstractInsnNode, Integer>();
            int line = -1;
            for (AbstractInsnNode current = method.instructions.getFirst(); current != null; current = current.getNext()) {
                if (current instanceof LineNumberNode) line = ((LineNumberNode) current).line;
                if (current.getOpcode() < 0) continue;
                ordinals.put(current, instructions.size());
                instructions.add(current);
                lines.add(Integer.valueOf(line));
            }
            if (instructions.isEmpty()) throw new IOException("Selected method has no executable bytecode");

            TreeSet<Integer> leaders = new TreeSet<Integer>();
            leaders.add(Integer.valueOf(0));
            for (int index = 0; index < instructions.size(); index++) {
                AbstractInsnNode instruction = instructions.get(index);
                if (instruction instanceof JumpInsnNode) {
                    addLeader(leaders, ordinals, executable(((JumpInsnNode) instruction).label));
                    addLeader(leaders, index + 1, instructions.size());
                } else if (instruction instanceof TableSwitchInsnNode) {
                    TableSwitchInsnNode table = (TableSwitchInsnNode) instruction;
                    addLeader(leaders, ordinals, executable(table.dflt));
                    for (LabelNode label : table.labels) addLeader(leaders, ordinals, executable(label));
                    addLeader(leaders, index + 1, instructions.size());
                } else if (instruction instanceof LookupSwitchInsnNode) {
                    LookupSwitchInsnNode lookup = (LookupSwitchInsnNode) instruction;
                    addLeader(leaders, ordinals, executable(lookup.dflt));
                    for (LabelNode label : lookup.labels) addLeader(leaders, ordinals, executable(label));
                    addLeader(leaders, index + 1, instructions.size());
                } else if (terminal(instruction.getOpcode())) {
                    addLeader(leaders, index + 1, instructions.size());
                }
            }
            for (TryCatchBlockNode region : method.tryCatchBlocks) {
                addLeader(leaders, ordinals, executable(region.start));
                addLeader(leaders, ordinals, executable(region.end));
                addLeader(leaders, ordinals, executable(region.handler));
            }
            if (leaders.size() > MAX_BLOCKS) {
                throw new IOException("Method CFG exceeds the " + MAX_BLOCKS + " basic block safety limit");
            }

            List<Integer> starts = new ArrayList<Integer>(leaders);
            List<Block> blocks = new ArrayList<Block>();
            Block[] byOrdinal = new Block[instructions.size()];
            for (int index = 0; index < starts.size(); index++) {
                int start = starts.get(index).intValue();
                int end = index + 1 < starts.size() ? starts.get(index + 1).intValue() - 1 : instructions.size() - 1;
                Block block = new Block(index, start, end, instructions.get(start),
                        lines.get(start).intValue(), lines.get(end).intValue());
                blocks.add(block);
                for (int ordinal = start; ordinal <= end; ordinal++) byOrdinal[ordinal] = block;
            }

            List<Edge> edges = new ArrayList<Edge>();
            Set<String> edgeKeys = new HashSet<String>();
            addEdge(edges, edgeKeys, "ENTRY", blocks.get(0).id, "ENTRY", "");
            for (Block block : blocks) {
                AbstractInsnNode last = instructions.get(block.end);
                int opcode = last.getOpcode();
                if (last instanceof JumpInsnNode) {
                    Block target = block(ordinals, byOrdinal, executable(((JumpInsnNode) last).label));
                    if (opcode == Opcodes.GOTO) {
                        addEdge(edges, edgeKeys, block.id, id(target), "JUMP", "goto");
                    } else if (opcode == Opcodes.JSR) {
                        addEdge(edges, edgeKeys, block.id, id(target), "CALL", "jsr");
                        addFallthrough(edges, edgeKeys, block, byOrdinal);
                    } else {
                        addEdge(edges, edgeKeys, block.id, id(target), "BRANCH", "true");
                        addFallthrough(edges, edgeKeys, block, byOrdinal, "false");
                    }
                } else if (last instanceof TableSwitchInsnNode) {
                    TableSwitchInsnNode table = (TableSwitchInsnNode) last;
                    addEdge(edges, edgeKeys, block.id,
                            id(block(ordinals, byOrdinal, executable(table.dflt))), "SWITCH", "default");
                    for (int key = table.min; key <= table.max; key++) {
                        LabelNode target = table.labels.get(key - table.min);
                        addEdge(edges, edgeKeys, block.id,
                                id(block(ordinals, byOrdinal, executable(target))), "SWITCH", String.valueOf(key));
                    }
                } else if (last instanceof LookupSwitchInsnNode) {
                    LookupSwitchInsnNode lookup = (LookupSwitchInsnNode) last;
                    addEdge(edges, edgeKeys, block.id,
                            id(block(ordinals, byOrdinal, executable(lookup.dflt))), "SWITCH", "default");
                    for (int index = 0; index < lookup.keys.size(); index++) {
                        addEdge(edges, edgeKeys, block.id,
                                id(block(ordinals, byOrdinal, executable(lookup.labels.get(index)))),
                                "SWITCH", String.valueOf(lookup.keys.get(index)));
                    }
                } else if (opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN) {
                    addEdge(edges, edgeKeys, block.id, "EXIT", "RETURN", "");
                } else if (opcode == Opcodes.ATHROW) {
                    addEdge(edges, edgeKeys, block.id, "EXIT", "THROW", "");
                } else if (opcode == Opcodes.RET) {
                    addEdge(edges, edgeKeys, block.id, "EXIT", "RETURN", "ret");
                } else {
                    addFallthrough(edges, edgeKeys, block, byOrdinal);
                }
            }

            for (TryCatchBlockNode region : method.tryCatchBlocks) {
                Integer startValue = ordinal(ordinals, executable(region.start));
                Integer endValue = ordinal(ordinals, executable(region.end));
                Block handler = block(ordinals, byOrdinal, executable(region.handler));
                if (startValue == null || handler == null) continue;
                int end = endValue == null ? instructions.size() : endValue.intValue();
                String caught = region.type == null ? "any" : region.type.replace('/', '.');
                for (Block block : blocks) {
                    if (block.end >= startValue.intValue() && block.start < end) {
                        addEdge(edges, edgeKeys, block.id, handler.id, "EXCEPTION", caught);
                    }
                }
            }
            if (edges.size() > MAX_EDGES) {
                throw new IOException("Method CFG exceeds the " + MAX_EDGES + " edge safety limit");
            }

            markReachable(blocks, edges);
            computeDominators(blocks, edges);
            Graph graph = new Graph(reader, owner, method, owner.name.replace('/', '.'), methodName,
                    descriptor, instructions, ordinals, byOrdinal, blocks, edges);
            for (Block block : blocks) block.summary = summary(graph, block);
            return graph;
        } catch (IOException error) {
            throw error;
        } catch (Throwable error) {
            throw new IOException("Could not build bytecode CFG: " + message(error), error);
        }
    }

    private static MethodNode find(ClassNode owner, String methodName, String descriptor) throws IOException {
        for (MethodNode method : owner.methods) {
            if (method.name.equals(methodName) && method.desc.equals(descriptor)) return method;
        }
        throw new IOException("Method is not present in the selected class: " + methodName + descriptor);
    }

    private static void markReachable(List<Block> blocks, List<Edge> edges) {
        Map<String, Block> byId = new LinkedHashMap<String, Block>();
        for (Block block : blocks) byId.put(block.id, block);
        ArrayDeque<Block> queue = new ArrayDeque<Block>();
        blocks.get(0).reachable = true;
        queue.add(blocks.get(0));
        while (!queue.isEmpty()) {
            Block current = queue.removeFirst();
            for (Edge edge : edges) {
                if (!edge.from.equals(current.id)) continue;
                Block target = byId.get(edge.to);
                if (target != null && !target.reachable) {
                    target.reachable = true;
                    queue.addLast(target);
                }
            }
        }
    }

    private static void computeDominators(List<Block> blocks, List<Edge> edges) {
        Set<Integer> reachable = new LinkedHashSet<Integer>();
        for (Block block : blocks) if (block.reachable) reachable.add(Integer.valueOf(block.index));
        List<Set<Integer>> dominators = new ArrayList<Set<Integer>>();
        for (Block block : blocks) {
            Set<Integer> values = new LinkedHashSet<Integer>();
            if (block.index == 0) values.add(Integer.valueOf(0));
            else if (block.reachable) values.addAll(reachable);
            dominators.add(values);
        }

        boolean changed;
        do {
            changed = false;
            for (Block block : blocks) {
                if (!block.reachable || block.index == 0) continue;
                List<Block> predecessors = predecessors(block, blocks, edges);
                Set<Integer> next = null;
                for (Block predecessor : predecessors) {
                    if (!predecessor.reachable) continue;
                    if (next == null) next = new LinkedHashSet<Integer>(dominators.get(predecessor.index));
                    else next.retainAll(dominators.get(predecessor.index));
                }
                if (next == null) next = new LinkedHashSet<Integer>();
                next.add(Integer.valueOf(block.index));
                if (!next.equals(dominators.get(block.index))) {
                    dominators.set(block.index, next);
                    changed = true;
                }
            }
        } while (changed);

        for (Block block : blocks) {
            block.dominators.addAll(dominators.get(block.index));
            if (!block.reachable || block.index == 0) continue;
            int best = -1;
            int bestDepth = -1;
            for (Integer candidate : block.dominators) {
                int value = candidate.intValue();
                if (value == block.index) continue;
                int depth = dominators.get(value).size();
                if (depth > bestDepth) {
                    best = value;
                    bestDepth = depth;
                }
            }
            if (best >= 0) block.immediateDominator = "B" + best;
        }
    }

    private static List<Block> predecessors(Block target, List<Block> blocks, List<Edge> edges) {
        List<Block> output = new ArrayList<Block>();
        for (Edge edge : edges) {
            if (!edge.to.equals(target.id) || !edge.from.startsWith("B")) continue;
            int index = Integer.parseInt(edge.from.substring(1));
            if (index >= 0 && index < blocks.size()) output.add(blocks.get(index));
        }
        return output;
    }

    private static String summary(Graph graph, Block block) {
        StringBuilder output = new StringBuilder();
        int shown = 0;
        for (int index = block.start; index <= block.end; index++) {
            if (shown >= MAX_SUMMARY_INSTRUCTIONS) {
                output.append("... ").append(block.end - index + 1).append(" more instructions");
                break;
            }
            if (output.length() > 0) output.append('\n');
            output.append(String.format("%04d  ", Integer.valueOf(index)))
                    .append(instruction(graph, graph.instructions.get(index)));
            shown++;
        }
        return output.toString();
    }

    private static String instruction(Graph graph, AbstractInsnNode instruction) {
        int opcode = instruction.getOpcode();
        String name = opcode >= 0 && opcode < OPCODE_NAMES.length && OPCODE_NAMES[opcode] != null
                ? OPCODE_NAMES[opcode] : "OP_" + opcode;
        if (instruction instanceof VarInsnNode) return name + " " + ((VarInsnNode) instruction).var;
        if (instruction instanceof IntInsnNode) return name + " " + ((IntInsnNode) instruction).operand;
        if (instruction instanceof IincInsnNode) {
            IincInsnNode value = (IincInsnNode) instruction;
            return name + " " + value.var + " " + value.incr;
        }
        if (instruction instanceof TypeInsnNode) return name + " " + ((TypeInsnNode) instruction).desc;
        if (instruction instanceof FieldInsnNode) {
            FieldInsnNode value = (FieldInsnNode) instruction;
            return name + " " + value.owner + "." + value.name + " " + value.desc;
        }
        if (instruction instanceof MethodInsnNode) {
            MethodInsnNode value = (MethodInsnNode) instruction;
            return name + " " + value.owner + "." + value.name + value.desc;
        }
        if (instruction instanceof InvokeDynamicInsnNode) {
            InvokeDynamicInsnNode value = (InvokeDynamicInsnNode) instruction;
            return name + " " + value.name + value.desc;
        }
        if (instruction instanceof JumpInsnNode) {
            return name + " -> " + id(block(graph.ordinals, graph.byOrdinal,
                    executable(((JumpInsnNode) instruction).label)));
        }
        if (instruction instanceof LdcInsnNode) {
            return name + " " + limited(String.valueOf(((LdcInsnNode) instruction).cst), 120);
        }
        if (instruction instanceof TableSwitchInsnNode) {
            TableSwitchInsnNode value = (TableSwitchInsnNode) instruction;
            return name + " " + value.min + ".." + value.max;
        }
        if (instruction instanceof LookupSwitchInsnNode) {
            return name + " " + ((LookupSwitchInsnNode) instruction).keys;
        }
        if (instruction instanceof MultiANewArrayInsnNode) {
            MultiANewArrayInsnNode value = (MultiANewArrayInsnNode) instruction;
            return name + " " + value.desc + " " + value.dims;
        }
        return name;
    }

    private static boolean terminal(int opcode) {
        return opcode == Opcodes.ATHROW || opcode == Opcodes.RET
                || opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN;
    }

    private static void addFallthrough(List<Edge> edges, Set<String> keys, Block block, Block[] byOrdinal) {
        addFallthrough(edges, keys, block, byOrdinal, "");
    }

    private static void addFallthrough(List<Edge> edges, Set<String> keys, Block block,
                                       Block[] byOrdinal, String label) {
        int next = block.end + 1;
        String kind = label.isEmpty() ? "FLOW" : "BRANCH";
        if (next < byOrdinal.length) addEdge(edges, keys, block.id, byOrdinal[next].id, kind, label);
        else addEdge(edges, keys, block.id, "EXIT", "EXIT", label);
    }

    private static void addEdge(List<Edge> edges, Set<String> keys, String from, String to,
                                String kind, String label) {
        if (from == null || to == null) return;
        String key = from + '\u0000' + to + '\u0000' + kind + '\u0000' + label;
        if (keys.add(key)) edges.add(new Edge(from, to, kind, label));
    }

    private static void addLeader(Set<Integer> leaders, IdentityHashMap<AbstractInsnNode, Integer> ordinals,
                                  AbstractInsnNode instruction) {
        Integer value = ordinal(ordinals, instruction);
        if (value != null) leaders.add(value);
    }

    private static void addLeader(Set<Integer> leaders, int index, int size) {
        if (index >= 0 && index < size) leaders.add(Integer.valueOf(index));
    }

    private static Integer ordinal(IdentityHashMap<AbstractInsnNode, Integer> ordinals,
                                   AbstractInsnNode instruction) {
        return instruction == null ? null : ordinals.get(instruction);
    }

    private static AbstractInsnNode executable(LabelNode label) {
        AbstractInsnNode current = label;
        while (current != null && current.getOpcode() < 0) current = current.getNext();
        return current;
    }

    private static Block block(IdentityHashMap<AbstractInsnNode, Integer> ordinals,
                               Block[] byOrdinal, AbstractInsnNode instruction) {
        Integer value = ordinal(ordinals, instruction);
        return value == null ? null : byOrdinal[value.intValue()];
    }

    private static String id(Block block) {
        return block == null ? null : block.id;
    }

    private static void pushInt(InsnList output, int value) {
        if (value >= 0 && value <= 5) output.add(new InsnNode(Opcodes.ICONST_0 + value));
        else if (value <= Byte.MAX_VALUE) output.add(new IntInsnNode(Opcodes.BIPUSH, value));
        else if (value <= Short.MAX_VALUE) output.add(new IntInsnNode(Opcodes.SIPUSH, value));
        else output.add(new LdcInsnNode(Integer.valueOf(value)));
    }

    private static String[] opcodeNames() {
        String[] output = new String[256];
        for (Field field : Opcodes.class.getFields()) {
            String name = field.getName();
            if (field.getType() != Integer.TYPE || !Modifier.isStatic(field.getModifiers())
                    || metadataConstant(name)) continue;
            try {
                int value = field.getInt(null);
                if (value >= 0 && value < output.length && output[value] == null) output[value] = name;
            } catch (IllegalAccessException ignored) {
            }
        }
        return output;
    }

    private static boolean metadataConstant(String name) {
        return name.startsWith("ACC_") || name.startsWith("ASM") || name.startsWith("V")
                || name.startsWith("H_") || name.startsWith("F_") || name.startsWith("T_")
                || name.startsWith("SOURCE_") || name.startsWith("JVM_")
                || "TOP".equals(name) || "INTEGER".equals(name) || "FLOAT".equals(name)
                || "DOUBLE".equals(name) || "LONG".equals(name) || "NULL".equals(name)
                || "UNINITIALIZED_THIS".equals(name);
    }

    private static String limited(String value, int maximum) {
        if (value.length() <= maximum) return value;
        return value.substring(0, maximum - 3) + "...";
    }

    private static String encoded(String value) {
        if (value == null || value.isEmpty()) return "";
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String message(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    static final class Instrumented {
        final byte[] bytecode;
        final int blockCount;

        Instrumented(byte[] bytecode, int blockCount) {
            this.bytecode = bytecode;
            this.blockCount = blockCount;
        }
    }

    private static final class Graph {
        final ClassReader reader;
        final ClassNode owner;
        final MethodNode method;
        final String className;
        final String methodName;
        final String descriptor;
        final List<AbstractInsnNode> instructions;
        final IdentityHashMap<AbstractInsnNode, Integer> ordinals;
        final Block[] byOrdinal;
        final List<Block> blocks;
        final List<Edge> edges;

        Graph(ClassReader reader, ClassNode owner, MethodNode method, String className,
              String methodName, String descriptor, List<AbstractInsnNode> instructions,
              IdentityHashMap<AbstractInsnNode, Integer> ordinals, Block[] byOrdinal,
              List<Block> blocks, List<Edge> edges) {
            this.reader = reader;
            this.owner = owner;
            this.method = method;
            this.className = className;
            this.methodName = methodName;
            this.descriptor = descriptor;
            this.instructions = instructions;
            this.ordinals = ordinals;
            this.byOrdinal = byOrdinal;
            this.blocks = blocks;
            this.edges = edges;
        }

        String encode() {
            int dead = 0;
            for (Block block : blocks) if (!block.reachable) dead++;
            int complexity = complexity();
            StringBuilder output = new StringBuilder();
            output.append('G').append('\t').append(encoded(className)).append('\t')
                    .append(encoded(methodName)).append('\t').append(encoded(descriptor)).append('\t')
                    .append(blocks.size()).append('\t').append(edges.size()).append('\t')
                    .append(complexity).append('\t').append(dead).append('\n');
            for (Block block : blocks) {
                StringBuilder dominators = new StringBuilder();
                for (Integer dominator : block.dominators) {
                    if (dominators.length() > 0) dominators.append(',');
                    dominators.append('B').append(dominator.intValue());
                }
                output.append('B').append('\t').append(block.id).append('\t')
                        .append(block.start).append('\t').append(block.end).append('\t')
                        .append(block.startLine).append('\t').append(block.endLine).append('\t')
                        .append(block.reachable).append('\t').append(block.immediateDominator).append('\t')
                        .append(encoded(dominators.toString())).append('\t')
                        .append(encoded(block.summary)).append('\n');
            }
            for (Edge edge : edges) {
                output.append('E').append('\t').append(edge.from).append('\t').append(edge.to).append('\t')
                        .append(edge.kind).append('\t').append(encoded(edge.label)).append('\n');
            }
            return output.toString();
        }

        int complexity() {
            int reachableBlocks = 0;
            int reachableEdges = 0;
            Set<String> reachable = new HashSet<String>();
            for (Block block : blocks) {
                if (block.reachable) {
                    reachableBlocks++;
                    reachable.add(block.id);
                }
            }
            for (Edge edge : edges) {
                boolean from = "ENTRY".equals(edge.from) || reachable.contains(edge.from);
                boolean to = "EXIT".equals(edge.to) || reachable.contains(edge.to);
                if (from && to) reachableEdges++;
            }
            return Math.max(1, reachableEdges - (reachableBlocks + 2) + 2);
        }
    }

    private static final class Block {
        final int index;
        final String id;
        final int start;
        final int end;
        final AbstractInsnNode first;
        final int startLine;
        final int endLine;
        final Set<Integer> dominators = new TreeSet<Integer>();
        boolean reachable;
        String immediateDominator = "";
        String summary = "";

        Block(int index, int start, int end, AbstractInsnNode first, int startLine, int endLine) {
            this.index = index;
            this.id = "B" + index;
            this.start = start;
            this.end = end;
            this.first = first;
            this.startLine = startLine;
            this.endLine = endLine;
        }
    }

    private static final class Edge {
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
}

