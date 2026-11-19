package remold.ref;

import java.util.*;

/** Pure batch-level field graph utilities used by InitFix host scheduling. */
public final class InitFixBatchGraph {
    private InitFixBatchGraph() { }

    public record FieldKey(String host, String field) {
        public FieldKey {
            host = Objects.requireNonNull(host, "host");
            field = Objects.requireNonNull(field, "field");
        }
    }

    public record Graph(Map<FieldKey, Set<FieldKey>> dependencies) {
        public Graph {
            Map<FieldKey, Set<FieldKey>> copy = new LinkedHashMap<>();
            for (Map.Entry<FieldKey, Set<FieldKey>> entry : dependencies.entrySet()) {
                copy.put(entry.getKey(), Set.copyOf(entry.getValue()));
            }
            dependencies = Collections.unmodifiableMap(copy);
        }

        public Set<FieldKey> nodes() { return dependencies.keySet(); }

        public Set<FieldKey> cycleMembers() {
            Map<FieldKey, Integer> index = new HashMap<>();
            Map<FieldKey, Integer> low = new HashMap<>();
            Deque<FieldKey> stack = new ArrayDeque<>();
            Set<FieldKey> onStack = new HashSet<>();
            Set<FieldKey> cycles = new LinkedHashSet<>();
            int[] next = {0};
            for (FieldKey node : dependencies.keySet()) {
                if (!index.containsKey(node)) tarjan(node, index, low, stack, onStack, cycles, next);
            }
            return Set.copyOf(cycles);
        }

        private void tarjan(FieldKey node, Map<FieldKey, Integer> index, Map<FieldKey, Integer> low,
                            Deque<FieldKey> stack, Set<FieldKey> onStack, Set<FieldKey> cycles, int[] next) {
            index.put(node, next[0]);
            low.put(node, next[0]++);
            stack.push(node);
            onStack.add(node);
            for (FieldKey dep : dependencies.getOrDefault(node, Set.of())) {
                if (!dependencies.containsKey(dep)) continue;
                if (!index.containsKey(dep)) {
                    tarjan(dep, index, low, stack, onStack, cycles, next);
                    low.put(node, Math.min(low.get(node), low.get(dep)));
                } else if (onStack.contains(dep)) {
                    low.put(node, Math.min(low.get(node), index.get(dep)));
                }
            }
            if (!Objects.equals(low.get(node), index.get(node))) return;
            List<FieldKey> component = new ArrayList<>();
            FieldKey member;
            do {
                member = stack.pop();
                onStack.remove(member);
                component.add(member);
            } while (!member.equals(node));
            if (component.size() > 1 || dependencies.getOrDefault(node, Set.of()).contains(node)) cycles.addAll(component);
        }

        public Set<FieldKey> conflictingHostReaders() {
            Map<String, Set<String>> hostDeps = new LinkedHashMap<>();
            for (FieldKey reader : dependencies.keySet()) {
                for (FieldKey prerequisite : dependencies.getOrDefault(reader, Set.of())) {
                    if (!reader.host().equals(prerequisite.host())) {
                        hostDeps.computeIfAbsent(reader.host(), ignored -> new LinkedHashSet<>()).add(prerequisite.host());
                    }
                }
            }
            Set<FieldKey> conflicts = new LinkedHashSet<>();
            for (Map.Entry<FieldKey, Set<FieldKey>> entry : dependencies.entrySet()) {
                for (FieldKey prerequisite : entry.getValue()) {
                    if (!entry.getKey().host().equals(prerequisite.host())
                        && reaches(hostDeps, prerequisite.host(), entry.getKey().host(), new HashSet<>())) {
                        conflicts.add(entry.getKey());
                    }
                }
            }
            return Set.copyOf(conflicts);
        }

        private static boolean reaches(Map<String, Set<String>> edges, String from, String target, Set<String> seen) {
            if (from.equals(target)) return true;
            if (!seen.add(from)) return false;
            for (String next : edges.getOrDefault(from, Set.of())) {
                if (reaches(edges, next, target, seen)) return true;
            }
            return false;
        }

        public List<String> topologicalHosts(Set<String> allowedHosts) {
            Map<String, Set<String>> deps = new LinkedHashMap<>();
            for (FieldKey node : dependencies.keySet()) {
                if (!allowedHosts.contains(node.host())) continue;
                Set<String> hostDeps = deps.computeIfAbsent(node.host(), ignored -> new LinkedHashSet<>());
                for (FieldKey dep : dependencies.getOrDefault(node, Set.of())) {
                    if (allowedHosts.contains(dep.host()) && !dep.host().equals(node.host())) hostDeps.add(dep.host());
                }
            }
            for (String host : allowedHosts) deps.computeIfAbsent(host, ignored -> new LinkedHashSet<>());
            List<String> result = new ArrayList<>();
            Set<String> visiting = new HashSet<>();
            Set<String> visited = new HashSet<>();
            for (String host : deps.keySet()) {
                if (!visitHost(host, deps, visiting, visited, result)) return List.of();
            }
            return result;
        }

        private static boolean visitHost(String host, Map<String, Set<String>> deps, Set<String> visiting,
                                         Set<String> visited, List<String> result) {
            if (visited.contains(host)) return true;
            if (!visiting.add(host)) return false;
            for (String dep : deps.getOrDefault(host, Set.of())) {
                if (!visitHost(dep, deps, visiting, visited, result)) return false;
            }
            visiting.remove(host);
            visited.add(host);
            result.add(host);
            return true;
        }
    }

    public static Graph graph(Map<FieldKey, ? extends Collection<FieldKey>> raw) {
        Map<FieldKey, Set<FieldKey>> out = new LinkedHashMap<>();
        raw.forEach((key, value) -> out.put(key, new LinkedHashSet<>(value)));
        return new Graph(out);
    }
}
