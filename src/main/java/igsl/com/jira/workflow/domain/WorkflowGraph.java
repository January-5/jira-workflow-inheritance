package igsl.com.jira.workflow.domain;

import java.util.*;

/** Structural graph only. Conditions, validators and business functions are not interpreted. */
public final class WorkflowGraph {
    public static final String INITIAL = "@initial";
    private final Set<String> states;
    private final List<Edge> edges;
    private final Map<String, List<Edge>> outgoing = new LinkedHashMap<>();

    public WorkflowGraph(Set<String> states, List<Edge> edges) {
        if (states == null || states.isEmpty() || states.contains(INITIAL)) {
            throw new IllegalArgumentException("Workflow states are required; initial is reserved");
        }
        states.forEach(InheritanceRules::requireName);
        this.states = Collections.unmodifiableSet(new LinkedHashSet<>(states));
        this.edges = Collections.unmodifiableList(new ArrayList<>(Objects.requireNonNull(edges)));
        Set<String> identities = new HashSet<>();
        for (Edge edge : edges) {
            if ((!states.contains(edge.from) && !INITIAL.equals(edge.from)) || !states.contains(edge.to)) {
                throw new IllegalArgumentException("Transition endpoint does not exist: " + edge.id);
            }
            if (!identities.add(edge.from + "\u0000" + edge.id)) {
                throw new IllegalArgumentException("Duplicate transition identity at " + edge.from);
            }
            outgoing.computeIfAbsent(edge.from, ignored -> new ArrayList<>()).add(edge);
        }
    }

    public Set<String> states() { return states; }
    public List<Edge> edges() { return edges; }
    public List<Edge> outgoing(String state) {
        return Collections.unmodifiableList(outgoing.getOrDefault(state, Collections.emptyList()));
    }

    /** Stops at the first inherited node. Iterative traversal also supports extension cycles. */
    public Set<String> nextInherited(String source, Set<String> inherited) {
        Set<String> reached = new LinkedHashSet<>();
        Set<String> visitedExtensions = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        outgoing(source).forEach(edge -> queue.add(edge.to));
        while (!queue.isEmpty()) {
            String state = queue.removeFirst();
            if (inherited.contains(state)) {
                reached.add(state);
            } else if (visitedExtensions.add(state)) {
                outgoing(state).forEach(edge -> queue.addLast(edge.to));
            }
        }
        return reached;
    }

    public static void validateInheritance(WorkflowGraph parent, WorkflowGraph child) {
        if (!child.states.containsAll(parent.states)) {
            Set<String> missing = new LinkedHashSet<>(parent.states);
            missing.removeAll(child.states);
            throw new IllegalArgumentException("Inherited states cannot be removed or replaced: " + missing);
        }
        Set<String> sources = new LinkedHashSet<>(parent.states);
        sources.add(INITIAL);
        for (String source : sources) {
            Set<String> allowed = parent.nextInherited(source, parent.states);
            Set<String> actual = child.nextInherited(source, parent.states);
            if (!allowed.equals(actual)) {
                throw new IllegalArgumentException("Inherited paths differ at " + source
                        + "; required=" + allowed + "; actual=" + actual);
            }
        }
    }

    /** A bounded search: ambiguity/cycles require an explicit mapping instead of exponential enumeration. */
    public Edge uniqueTerminal(String from, String to, Set<String> inherited) {
        Set<String> expanded = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        List<Edge> terminals = new ArrayList<>();
        queue.add(from);
        while (!queue.isEmpty()) {
            String source = queue.removeFirst();
            if (!expanded.add(source)) {
                throw new IllegalArgumentException("Multiple or cyclic replacement paths require manual mapping");
            }
            for (Edge edge : outgoing(source)) {
                if (edge.to.equals(to)) terminals.add(edge);
                else if (!inherited.contains(edge.to)) queue.addLast(edge.to);
            }
        }
        if (terminals.size() != 1) {
            throw new IllegalArgumentException("Expected one replacement terminal transition; found " + terminals.size());
        }
        return terminals.get(0);
    }

    public static final class Edge {
        public final String id;
        public final String from;
        public final String to;
        public Edge(String id, String from, String to) {
            this.id = InheritanceRules.requireName(id);
            this.from = InheritanceRules.requireName(from);
            this.to = InheritanceRules.requireName(to);
        }
    }
}
