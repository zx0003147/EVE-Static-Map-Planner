"""Optimize an open, directed route through up to 50 EVE solar systems."""

from __future__ import annotations

import argparse
from collections import deque
from dataclasses import dataclass
import json
import sys
import traceback
from typing import Any, Iterable, Sequence

from mcp_client import McpClientError, fetch_normal_route_graph


OUTPUT_SCHEMA_VERSION = 1
GRAPH_SCHEMA_VERSION = 1
MAX_TARGETS = 50
EXACT_THRESHOLD = 15
HEURISTIC_LOCAL_STARTS = 8
HEURISTIC_MAX_PASSES = 25


class OptimizerError(Exception):
    """A stable, structured optimizer failure."""

    def __init__(
        self,
        code: str,
        message: str,
        exit_code: int = 4,
        details: dict[str, Any] | None = None,
    ) -> None:
        super().__init__(message)
        self.code = code
        self.message = message
        self.exit_code = exit_code
        self.details = details or {}


@dataclass(frozen=True)
class NormalizedRequest:
    start_system_id: int
    target_system_ids: tuple[int, ...]
    use_ansiblex: bool
    input_target_count: int
    start_was_target: bool


@dataclass(frozen=True)
class RouteGraph:
    names: dict[int, str]
    adjacency: dict[int, tuple[int, ...]]
    edge_count: int


def _is_integer(value: Any) -> bool:
    return isinstance(value, int) and not isinstance(value, bool)


def normalize_request(document: Any) -> NormalizedRequest:
    if not isinstance(document, dict):
        raise OptimizerError("INVALID_INPUT", "Input must be a JSON object.", 2)

    start = document.get("startSystemId")
    if not _is_integer(start):
        raise OptimizerError("INVALID_INPUT", "startSystemId must be an integer.", 2)

    raw_targets = document.get("targetSystemIds")
    if not isinstance(raw_targets, list):
        raise OptimizerError("INVALID_INPUT", "targetSystemIds must be an array of integers.", 2)
    invalid_indexes = [index for index, value in enumerate(raw_targets) if not _is_integer(value)]
    if invalid_indexes:
        raise OptimizerError(
            "INVALID_INPUT",
            "Every targetSystemIds value must be an integer.",
            2,
            {"invalidTargetIndexes": invalid_indexes},
        )

    use_ansiblex = document.get("useAnsiblex")
    if not isinstance(use_ansiblex, bool):
        raise OptimizerError("INVALID_INPUT", "useAnsiblex must be a Boolean.", 2)

    start_was_target = start in raw_targets
    unique_targets = tuple(sorted({target for target in raw_targets if target != start}))
    if not unique_targets:
        raise OptimizerError(
            "INVALID_INPUT",
            "At least one unique target different from startSystemId is required.",
            2,
        )
    if len(unique_targets) > MAX_TARGETS:
        raise OptimizerError(
            "TARGET_LIMIT_EXCEEDED",
            f"At most {MAX_TARGETS} unique targets are supported after removing startSystemId.",
            2,
            {"uniqueTargetCount": len(unique_targets), "maximumTargetCount": MAX_TARGETS},
        )

    return NormalizedRequest(start, unique_targets, use_ansiblex, len(raw_targets), start_was_target)


def parse_graph_snapshot(document: Any, expected_use_ansiblex: bool | None = None) -> RouteGraph:
    if not isinstance(document, dict):
        raise OptimizerError("MALFORMED_GRAPH_RESPONSE", "Graph snapshot must be a JSON object.")

    schema_version = document.get("schemaVersion")
    if not _is_integer(schema_version) or schema_version != GRAPH_SCHEMA_VERSION:
        raise OptimizerError(
            "UNSUPPORTED_GRAPH_SCHEMA",
            f"Unsupported graph schema version: expected {GRAPH_SCHEMA_VERSION}, got {schema_version!r}.",
        )

    snapshot_use_ansiblex = document.get("useAnsiblex")
    if not isinstance(snapshot_use_ansiblex, bool):
        raise OptimizerError("MALFORMED_GRAPH_RESPONSE", "Graph useAnsiblex must be a Boolean.")
    if expected_use_ansiblex is not None and snapshot_use_ansiblex != expected_use_ansiblex:
        raise OptimizerError(
            "MALFORMED_GRAPH_RESPONSE",
            "Graph useAnsiblex does not match the requested value.",
        )

    nodes = document.get("nodes")
    edges = document.get("edges")
    if not isinstance(nodes, list) or not isinstance(edges, list):
        raise OptimizerError("MALFORMED_GRAPH_RESPONSE", "Graph nodes and edges must be arrays.")

    names: dict[int, str] = {}
    for index, node in enumerate(nodes):
        if not isinstance(node, dict):
            raise OptimizerError("MALFORMED_GRAPH_RESPONSE", f"Graph node {index} is not an object.")
        system_id = node.get("systemId")
        system_name = node.get("systemName")
        if not _is_integer(system_id) or not isinstance(system_name, str) or not system_name:
            raise OptimizerError(
                "MALFORMED_GRAPH_RESPONSE",
                f"Graph node {index} must contain integer systemId and non-empty systemName.",
            )
        if system_id in names:
            raise OptimizerError("MALFORMED_GRAPH_RESPONSE", f"Duplicate graph systemId: {system_id}.")
        names[system_id] = system_name

    adjacency_sets: dict[int, set[int]] = {system_id: set() for system_id in names}
    for index, edge in enumerate(edges):
        if not isinstance(edge, dict):
            raise OptimizerError("MALFORMED_GRAPH_RESPONSE", f"Graph edge {index} is not an object.")
        from_id = edge.get("fromSystemId")
        to_id = edge.get("toSystemId")
        edge_type = edge.get("type")
        if not _is_integer(from_id) or not _is_integer(to_id) or edge_type not in {"STARGATE", "ANSIBLEX"}:
            raise OptimizerError("MALFORMED_GRAPH_RESPONSE", f"Graph edge {index} has invalid fields.")
        if from_id not in names or to_id not in names:
            raise OptimizerError(
                "MALFORMED_GRAPH_RESPONSE",
                f"Graph edge {index} references a system missing from nodes.",
            )
        adjacency_sets[from_id].add(to_id)

    adjacency = {system_id: tuple(sorted(neighbors)) for system_id, neighbors in adjacency_sets.items()}
    return RouteGraph(names, adjacency, sum(len(neighbors) for neighbors in adjacency.values()))


def directed_bfs(
    adjacency: dict[int, Sequence[int]],
    source: int,
    relevant_system_ids: Iterable[int] | None = None,
) -> dict[int, int]:
    distances = {source: 0}
    queue = deque([source])
    remaining = set(relevant_system_ids) if relevant_system_ids is not None else None
    if remaining is not None:
        remaining.discard(source)

    while queue and (remaining is None or remaining):
        current = queue.popleft()
        next_distance = distances[current] + 1
        for neighbor in adjacency.get(current, ()):
            if neighbor in distances:
                continue
            distances[neighbor] = next_distance
            queue.append(neighbor)
            if remaining is not None:
                remaining.discard(neighbor)
    return distances


def build_distance_matrix(
    graph: RouteGraph,
    relevant_system_ids: Sequence[int],
) -> tuple[list[list[int | None]], int]:
    relevant = set(relevant_system_ids)
    matrix: list[list[int | None]] = []
    for source in relevant_system_ids:
        distances = directed_bfs(graph.adjacency, source, relevant)
        matrix.append([distances.get(destination) for destination in relevant_system_ids])
    return matrix, len(relevant_system_ids)


def held_karp_open_path(
    start_costs: Sequence[int | None],
    between: Sequence[Sequence[int | None]],
) -> list[int]:
    target_count = len(start_costs)
    state_count = 1 << target_count
    costs: list[list[int | None] | None] = [None] * state_count
    predecessors: list[list[int | None] | None] = [None] * state_count

    for target in range(target_count):
        initial_cost = start_costs[target]
        if initial_cost is None:
            continue
        mask = 1 << target
        costs[mask] = [None] * target_count
        predecessors[mask] = [None] * target_count
        costs[mask][target] = initial_cost

    for mask in range(1, state_count):
        state_costs = costs[mask]
        if state_costs is None:
            continue
        for last, current_cost in enumerate(state_costs):
            if current_cost is None:
                continue
            remaining = (state_count - 1) ^ mask
            while remaining:
                bit = remaining & -remaining
                next_target = bit.bit_length() - 1
                remaining ^= bit
                step = between[last][next_target]
                if step is None:
                    continue
                next_mask = mask | bit
                if costs[next_mask] is None:
                    costs[next_mask] = [None] * target_count
                    predecessors[next_mask] = [None] * target_count
                candidate = current_cost + step
                existing = costs[next_mask][next_target]
                previous = predecessors[next_mask][next_target]
                if existing is None or candidate < existing or (
                    candidate == existing and (previous is None or last < previous)
                ):
                    costs[next_mask][next_target] = candidate
                    predecessors[next_mask][next_target] = last

    full_mask = state_count - 1
    final_costs = costs[full_mask]
    if final_costs is None:
        raise OptimizerError("NO_FEASIBLE_ROUTE", "No directed route can explicitly visit every target.", 5)
    possible_ends = [(cost, target) for target, cost in enumerate(final_costs) if cost is not None]
    if not possible_ends:
        raise OptimizerError("NO_FEASIBLE_ROUTE", "No directed route can explicitly visit every target.", 5)

    _, last = min(possible_ends)
    order: list[int] = []
    mask = full_mask
    while True:
        order.append(last)
        previous = predecessors[mask][last] if predecessors[mask] is not None else None
        mask ^= 1 << last
        if previous is None:
            break
        last = previous
    order.reverse()
    return order


def _route_cost(
    order: Sequence[int],
    start_costs: Sequence[int | None],
    between: Sequence[Sequence[int | None]],
) -> int | None:
    if not order:
        return 0
    total = start_costs[order[0]]
    if total is None:
        return None
    for first, second in zip(order, order[1:]):
        step = between[first][second]
        if step is None:
            return None
        total += step
    return total


def _nearest_neighbor(
    seed: int,
    target_system_ids: Sequence[int],
    between: Sequence[Sequence[int | None]],
) -> list[int] | None:
    order = [seed]
    remaining = set(range(len(target_system_ids)))
    remaining.remove(seed)
    current = seed
    while remaining:
        choices = [
            (between[current][candidate], target_system_ids[candidate], candidate)
            for candidate in remaining
            if between[current][candidate] is not None
        ]
        if not choices:
            return None
        _, _, current = min(choices)
        order.append(current)
        remaining.remove(current)
    return order


def _local_search(
    initial: Sequence[int],
    target_system_ids: Sequence[int],
    start_costs: Sequence[int | None],
    between: Sequence[Sequence[int | None]],
) -> tuple[list[int], int]:
    route = list(initial)
    current_cost = _route_cost(route, start_costs, between)
    if current_cost is None:
        raise OptimizerError("NO_FEASIBLE_ROUTE", "Heuristic received an infeasible initial route.", 5)

    for _ in range(HEURISTIC_MAX_PASSES):
        best_route: list[int] | None = None
        best_cost = current_cost
        best_ids: tuple[int, ...] | None = None

        for from_index in range(len(route)):
            reduced = route[:from_index] + route[from_index + 1 :]
            for insert_index in range(len(route)):
                candidate = reduced[:insert_index] + [route[from_index]] + reduced[insert_index:]
                if candidate == route:
                    continue
                candidate_cost = _route_cost(candidate, start_costs, between)
                if candidate_cost is None or candidate_cost >= best_cost:
                    continue
                candidate_ids = tuple(target_system_ids[index] for index in candidate)
                if candidate_cost < best_cost or best_ids is None or candidate_ids < best_ids:
                    best_route, best_cost, best_ids = candidate, candidate_cost, candidate_ids

        for first in range(len(route) - 1):
            for second in range(first + 1, len(route)):
                candidate = route.copy()
                candidate[first], candidate[second] = candidate[second], candidate[first]
                candidate_cost = _route_cost(candidate, start_costs, between)
                if candidate_cost is None or candidate_cost >= best_cost:
                    continue
                candidate_ids = tuple(target_system_ids[index] for index in candidate)
                if candidate_cost < best_cost or best_ids is None or candidate_ids < best_ids:
                    best_route, best_cost, best_ids = candidate, candidate_cost, candidate_ids

        if best_route is None:
            break
        route, current_cost = best_route, best_cost

    return route, current_cost


def heuristic_open_path(
    target_system_ids: Sequence[int],
    start_costs: Sequence[int | None],
    between: Sequence[Sequence[int | None]],
) -> list[int]:
    initial_candidates: list[tuple[int, tuple[int, ...], list[int]]] = []
    seed_order = sorted(
        range(len(target_system_ids)),
        key=lambda target: (start_costs[target] if start_costs[target] is not None else sys.maxsize, target_system_ids[target]),
    )
    for seed in seed_order:
        if start_costs[seed] is None:
            continue
        route = _nearest_neighbor(seed, target_system_ids, between)
        if route is None:
            continue
        cost = _route_cost(route, start_costs, between)
        if cost is not None:
            initial_candidates.append((cost, tuple(target_system_ids[index] for index in route), route))

    if not initial_candidates:
        raise OptimizerError("NO_FEASIBLE_ROUTE", "Heuristic could not build a directed route through every target.", 5)

    finalists: list[tuple[int, tuple[int, ...], list[int]]] = []
    for _, _, initial in sorted(initial_candidates)[:HEURISTIC_LOCAL_STARTS]:
        route, cost = _local_search(initial, target_system_ids, start_costs, between)
        finalists.append((cost, tuple(target_system_ids[index] for index in route), route))
    return min(finalists)[2]


def validate_coverage(required: Sequence[int], ordered: Sequence[int]) -> None:
    required_set = set(required)
    ordered_set = set(ordered)
    missing = sorted(required_set - ordered_set)
    extra = sorted(ordered_set - required_set)
    duplicates = sorted(system_id for system_id in ordered_set if ordered.count(system_id) > 1)
    if required_set != ordered_set or len(required) != len(ordered):
        raise OptimizerError(
            "COVERAGE_VALIDATION_FAILED",
            "Optimized route failed the required target coverage check.",
            6,
            {
                "missingSystemIds": missing,
                "extraSystemIds": extra,
                "duplicateSystemIds": duplicates,
            },
        )


def optimize_normalized(request: NormalizedRequest, snapshot: Any) -> dict[str, Any]:
    graph = parse_graph_snapshot(snapshot, request.use_ansiblex)
    if request.start_system_id not in graph.names:
        raise OptimizerError(
            "START_SYSTEM_NOT_FOUND",
            f"startSystemId {request.start_system_id} is missing from the graph.",
            4,
        )
    missing_targets = sorted(system_id for system_id in request.target_system_ids if system_id not in graph.names)
    if missing_targets:
        raise OptimizerError(
            "TARGET_SYSTEM_NOT_FOUND",
            "One or more target systems are missing from the graph.",
            4,
            {"missingTargetSystemIds": missing_targets},
        )

    relevant = (request.start_system_id,) + request.target_system_ids
    matrix, bfs_runs = build_distance_matrix(graph, relevant)
    unreachable = [
        system_id
        for index, system_id in enumerate(request.target_system_ids, start=1)
        if matrix[0][index] is None
    ]
    if unreachable:
        raise OptimizerError(
            "UNREACHABLE_TARGETS",
            "One or more required targets cannot be reached from startSystemId.",
            5,
            {"unreachableSystemIds": unreachable},
        )

    start_costs = matrix[0][1:]
    between = [row[1:] for row in matrix[1:]]
    if len(request.target_system_ids) <= EXACT_THRESHOLD:
        order_indexes = held_karp_open_path(start_costs, between)
        method = "EXACT_HELD_KARP"
        guaranteed_optimal = True
    else:
        order_indexes = heuristic_open_path(request.target_system_ids, start_costs, between)
        method = "HEURISTIC"
        guaranteed_optimal = False

    ordered_ids = [request.target_system_ids[index] for index in order_indexes]
    validate_coverage(request.target_system_ids, ordered_ids)

    matrix_index = {system_id: index for index, system_id in enumerate(relevant)}
    segments: list[dict[str, int]] = []
    previous = request.start_system_id
    total_jumps = 0
    for system_id in ordered_ids:
        jumps = matrix[matrix_index[previous]][matrix_index[system_id]]
        if jumps is None:
            raise OptimizerError("INTERNAL_OPTIMIZATION_FAILURE", "Optimizer emitted an unreachable segment.", 6)
        segments.append({"fromSystemId": previous, "toSystemId": system_id, "jumps": jumps})
        total_jumps += jumps
        previous = system_id

    return {
        "schemaVersion": OUTPUT_SCHEMA_VERSION,
        "success": True,
        "startSystemId": request.start_system_id,
        "inputTargetCount": request.input_target_count,
        "uniqueTargetCount": len(request.target_system_ids),
        "startWasTarget": request.start_was_target,
        "useAnsiblex": request.use_ansiblex,
        "optimization": {"method": method, "guaranteedOptimal": guaranteed_optimal},
        "orderedTargets": [
            {"systemId": system_id, "systemName": graph.names[system_id]} for system_id in ordered_ids
        ],
        "segments": segments,
        "totalJumps": total_jumps,
        "coverage": {
            "required": len(request.target_system_ids),
            "visited": len(ordered_ids),
            "missingSystemIds": [],
        },
        "stats": {
            "graphNodes": len(graph.names),
            "graphEdges": graph.edge_count,
            "bfsRuns": bfs_runs,
            "mcpGraphCalls": 1,
        },
    }


def optimize_with_snapshot(request_document: Any, snapshot: Any) -> dict[str, Any]:
    """Test-friendly entry point that converts expected failures to stable JSON."""

    try:
        return optimize_normalized(normalize_request(request_document), snapshot)
    except OptimizerError as error:
        return error_result(error.code, error.message, error.details)


def error_result(code: str, message: str, details: dict[str, Any] | None = None) -> dict[str, Any]:
    result: dict[str, Any] = {
        "schemaVersion": OUTPUT_SCHEMA_VERSION,
        "success": False,
        "error": code,
        "message": message,
    }
    if details:
        result.update(details)
    return result


def _load_input(path: str | None) -> Any:
    try:
        if path:
            with open(path, "r", encoding="utf-8") as source:
                return json.load(source)
        text = sys.stdin.read()
        if not text.strip():
            raise OptimizerError("INVALID_INPUT", "No JSON input was provided.", 2)
        return json.loads(text)
    except OptimizerError:
        raise
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        raise OptimizerError("INVALID_INPUT", f"Could not read input JSON: {error}", 2) from error


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", metavar="FILE", help="Read request JSON from FILE; default is stdin.")
    parser.add_argument("--mcp-command", metavar="PATH", help="Explicit eve-map-mcp executable path.")
    parser.add_argument("--mcp-locator", metavar="FILE", help="Explicit schema-1 MCP locator JSON path.")
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    args = _parser().parse_args(argv)
    try:
        request = normalize_request(_load_input(args.input))
        snapshot = fetch_normal_route_graph(request.use_ansiblex, args.mcp_command, args.mcp_locator)
        result = optimize_normalized(request, snapshot)
        exit_code = 0
    except OptimizerError as error:
        result = error_result(error.code, error.message, error.details)
        exit_code = error.exit_code
    except McpClientError as error:
        result = error_result(error.code, error.message)
        exit_code = error.exit_code
    except Exception as error:  # Defensive CLI boundary: stdout must remain machine-readable.
        traceback.print_exc(file=sys.stderr)
        result = error_result("INTERNAL_OPTIMIZATION_FAILURE", f"Unexpected optimizer failure: {error}")
        exit_code = 6

    print(json.dumps(result, ensure_ascii=False, separators=(",", ":")))
    return exit_code


if __name__ == "__main__":
    raise SystemExit(main())
