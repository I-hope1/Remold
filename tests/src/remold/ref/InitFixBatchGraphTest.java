package remold.ref;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InitFixBatchGraphTest {
	private static InitFixBatchGraph.FieldKey field(String host, String name) {
		return new InitFixBatchGraph.FieldKey(host, name);
	}

	@Test
	void ordersHostsByFieldPrerequisitesWithoutCollapsingIndependentFields() {
		var a = field("L1:A", "a");
		var b = field("L1:B", "b");
		var unrelated = field("L1:B", "unrelated");
		Map<InitFixBatchGraph.FieldKey, Set<InitFixBatchGraph.FieldKey>> edges = new LinkedHashMap<>();
		edges.put(a, Set.of(b));
		edges.put(unrelated, Set.of());
		edges.put(b, Set.of());

		var graph = InitFixBatchGraph.graph(edges);
		assertTrue(graph.cycleMembers().isEmpty());
		assertEquals(java.util.List.of("L1:B", "L1:A"),
		 graph.topologicalHosts(new LinkedHashSet<>(Set.of("L1:A", "L1:B"))));
	}

	@Test
	void detectsFieldCyclesAndContradictoryHostOrdering() {
		var a = field("L1:A", "a");
		var b = field("L1:B", "b");
		Map<InitFixBatchGraph.FieldKey, Set<InitFixBatchGraph.FieldKey>> edges = new LinkedHashMap<>();
		edges.put(a, Set.of(b));
		edges.put(b, Set.of(a));

		var graph = InitFixBatchGraph.graph(edges);
		assertEquals(Set.of(a, b), graph.cycleMembers());
		assertTrue(graph.topologicalHosts(Set.of("L1:A", "L1:B")).isEmpty());
	}

	@Test
	void identifiesConflictingInterleavingsWithoutAFieldCycle() {
		var a1 = field("A", "a1");
		var a2 = field("A", "a2");
		var b1 = field("B", "b1");
		var b2 = field("B", "b2");
		Map<InitFixBatchGraph.FieldKey, Set<InitFixBatchGraph.FieldKey>> edges = new LinkedHashMap<>();
		edges.put(a1, Set.of(b1));
		edges.put(b2, Set.of(a2));
		edges.put(a2, Set.of());
		edges.put(b1, Set.of());
		var graph = InitFixBatchGraph.graph(edges);
		assertTrue(graph.cycleMembers().isEmpty());
		assertEquals(Set.of(a1, b2), graph.conflictingHostReaders());
	}

	@Test
	void sameNamedHostsInDifferentLoadersRemainDistinctNodes() {
		var first = field("L1:pkg.Host", "value");
		var second = field("L2:pkg.Host", "value");
		assertEquals(2, InitFixBatchGraph.graph(Map.of(first, Set.of(), second, Set.of())).nodes().size());
	}
}
