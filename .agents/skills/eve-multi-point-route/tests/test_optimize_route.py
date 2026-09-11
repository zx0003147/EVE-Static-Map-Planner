from __future__ import annotations

from pathlib import Path
import sys
import unittest


SCRIPTS = Path(__file__).resolve().parents[1] / "scripts"
sys.path.insert(0, str(SCRIPTS))

from mcp_client import extract_tool_payload  # noqa: E402
from optimize_route import (  # noqa: E402
    OptimizerError,
    directed_bfs,
    normalize_request,
    optimize_with_snapshot,
    parse_graph_snapshot,
    validate_coverage,
)


def snapshot(system_ids: list[int], directed_edges: list[tuple[int, int]], use_ansiblex: bool = False) -> dict:
    return {
        "schemaVersion": 1,
        "projection": "OFFICIAL_2D",
        "useAnsiblex": use_ansiblex,
        "nodes": [
            {"systemId": system_id, "systemName": f"System {system_id}", "official2dX": None, "official2dY": None}
            for system_id in system_ids
        ],
        "edges": [
            {"fromSystemId": first, "toSystemId": second, "type": "STARGATE"}
            for first, second in directed_edges
        ],
    }


class DirectedGraphTests(unittest.TestCase):
    def test_directed_bfs_does_not_invent_reverse_edge(self) -> None:
        adjacency = {1: (2,), 2: ()}
        self.assertEqual(1, directed_bfs(adjacency, 1)[2])
        self.assertNotIn(1, directed_bfs(adjacency, 2))

    def test_snapshot_parallel_edges_are_deduplicated(self) -> None:
        graph = parse_graph_snapshot(snapshot([1, 2], [(1, 2), (1, 2)]), False)
        self.assertEqual((2,), graph.adjacency[1])
        self.assertEqual(1, graph.edge_count)


class InputAndCoverageTests(unittest.TestCase):
    def test_duplicate_targets_and_start_target_are_normalized(self) -> None:
        request = normalize_request(
            {"startSystemId": 1, "targetSystemIds": [3, 1, 2, 3, 1], "useAnsiblex": True}
        )
        self.assertEqual((2, 3), request.target_system_ids)
        self.assertEqual(5, request.input_target_count)
        self.assertTrue(request.start_was_target)

    def test_coverage_rejects_missing_and_duplicate_targets(self) -> None:
        with self.subTest("missing"):
            with self.assertRaises(OptimizerError):
                validate_coverage([1, 2, 3], [1, 2])
        with self.subTest("duplicate"):
            with self.assertRaises(OptimizerError):
                validate_coverage([1, 2, 3], [1, 2, 2])


class SolverTests(unittest.TestCase):
    def test_exact_solver_finds_open_path_without_return_to_start(self) -> None:
        graph = snapshot([0, 1, 2, 3], [(0, 1), (1, 2), (2, 3)])
        result = optimize_with_snapshot(
            {"startSystemId": 0, "targetSystemIds": [3, 1, 2], "useAnsiblex": False},
            graph,
        )
        self.assertTrue(result["success"])
        self.assertEqual("EXACT_HELD_KARP", result["optimization"]["method"])
        self.assertTrue(result["optimization"]["guaranteedOptimal"])
        self.assertEqual([1, 2, 3], [item["systemId"] for item in result["orderedTargets"]])
        self.assertEqual(3, result["totalJumps"])

    def test_heuristic_visits_twenty_targets_exactly_once(self) -> None:
        graph = snapshot(list(range(21)), [(index, index + 1) for index in range(20)])
        result = optimize_with_snapshot(
            {"startSystemId": 0, "targetSystemIds": list(reversed(range(1, 21))), "useAnsiblex": False},
            graph,
        )
        ordered = [item["systemId"] for item in result["orderedTargets"]]
        self.assertTrue(result["success"])
        self.assertEqual("HEURISTIC", result["optimization"]["method"])
        self.assertFalse(result["optimization"]["guaranteedOptimal"])
        self.assertEqual(list(range(1, 21)), ordered)
        self.assertEqual(20, len(set(ordered)))
        self.assertEqual({"required": 20, "visited": 20, "missingSystemIds": []}, result["coverage"])
        self.assertEqual(21, result["stats"]["bfsRuns"])

    def test_unreachable_target_is_a_structured_failure(self) -> None:
        graph = snapshot([0, 1, 2], [(0, 1)])
        result = optimize_with_snapshot(
            {"startSystemId": 0, "targetSystemIds": [1, 2], "useAnsiblex": False},
            graph,
        )
        self.assertFalse(result["success"])
        self.assertEqual("UNREACHABLE_TARGETS", result["error"])
        self.assertEqual([2], result["unreachableSystemIds"])


class ContractTests(unittest.TestCase):
    def test_unsupported_graph_schema_is_rejected(self) -> None:
        graph = snapshot([0, 1], [(0, 1)])
        graph["schemaVersion"] = 2
        with self.assertRaisesRegex(OptimizerError, "Unsupported graph schema version"):
            parse_graph_snapshot(graph, False)

    def test_mcp_structured_snapshot_is_extracted_without_text_fallback(self) -> None:
        graph = snapshot([0, 1], [(0, 1)])
        self.assertIs(graph, extract_tool_payload({"isError": False, "structuredContent": graph}))


if __name__ == "__main__":
    unittest.main()
