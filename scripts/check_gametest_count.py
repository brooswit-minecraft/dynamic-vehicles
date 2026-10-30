#!/usr/bin/env python3
"""Verify that the GameTest completion count covers the default selected suite.

NeoForge 21.1.250 filters automatic GameTest registration by a method's effective
template namespace: a nonempty ``@GameTest(templateNamespace = ...)`` wins,
otherwise the enclosing ``@GameTestHolder`` namespace is used.  Its completion
summary reports the total selected count (including optional tests), while
required failures are reported separately.
"""
import re
import sys
from dataclasses import dataclass
from pathlib import Path


class SourceSyntaxError(ValueError):
    """The deliberately small source subset used by the CI guard was not understood."""


class RuntimeEvidenceError(ValueError):
    """The GameTest log cannot prove a complete selected-suite execution."""


@dataclass(frozen=True)
class GameTestCase:
    source: str
    method: str
    namespace: str
    required: bool


def _strip_comments(text):
    """Remove comments while preserving quoted Java strings and source newlines."""
    out, index, state = [], 0, "code"
    while index < len(text):
        char = text[index]
        next_char = text[index + 1] if index + 1 < len(text) else ""
        if state == "code" and char == "/" and next_char == "/":
            state = "line_comment"; out.extend("  "); index += 2; continue
        if state == "code" and char == "/" and next_char == "*":
            state = "block_comment"; out.extend("  "); index += 2; continue
        if state == "line_comment":
            out.append("\n" if char == "\n" else " ")
            if char == "\n": state = "code"
            index += 1; continue
        if state == "block_comment":
            if char == "*" and next_char == "/":
                state = "code"; out.extend("  "); index += 2; continue
            out.append("\n" if char == "\n" else " "); index += 1; continue
        out.append(char)
        if state == "code" and char == '"': state = "string"
        elif state == "string" and char == "\\" and index + 1 < len(text):
            out.append(text[index + 1]); index += 2; continue
        elif state == "string" and char == '"': state = "code"
        index += 1
    if state == "block_comment":
        raise SourceSyntaxError("unterminated block comment")
    return "".join(out)


def _parenthesized(text, open_index, source):
    if open_index >= len(text) or text[open_index] != "(":
        raise SourceSyntaxError(f"{source}: expected '(' at annotation")
    depth, index, quote = 0, open_index, False
    while index < len(text):
        char = text[index]
        if quote:
            if char == "\\": index += 2; continue
            if char == '"': quote = False
        elif char == '"': quote = True
        elif char == "(": depth += 1
        elif char == ")":
            depth -= 1
            if depth == 0: return text[open_index + 1:index], index + 1
        index += 1
    raise SourceSyntaxError(f"{source}: unterminated annotation")


def _split_attributes(text, source):
    parts, start, depth, quote = [], 0, 0, False
    for index, char in enumerate(text):
        if quote:
            if char == "\\": continue
            if char == '"': quote = False
        elif char == '"': quote = True
        elif char in "({[": depth += 1
        elif char in ")]}": depth -= 1
        elif char == "," and depth == 0:
            parts.append(text[start:index].strip()); start = index + 1
    last = text[start:].strip()
    if last: parts.append(last)
    attributes = {}
    for part in parts:
        if not part: continue
        if "=" not in part:
            raise SourceSyntaxError(f"{source}: unsupported positional GameTest attribute '{part}'")
        key, value = (item.strip() for item in part.split("=", 1))
        if key in attributes:
            raise SourceSyntaxError(f"{source}: duplicate GameTest attribute '{key}'")
        attributes[key] = value
    return attributes


def _java_constants(text):
    constants = {}
    for name, value in re.findall(r"\b(?:public|private|protected)?\s*static\s+final\s+String\s+(\w+)\s*=\s*\"([^\"]*)\"\s*;", text):
        constants[name] = value
    return constants


def _resolve_namespace(expression, constants, mod_id, source):
    expression = expression.strip()
    literal = re.fullmatch(r'"([^\"]*)"', expression)
    if literal: return literal.group(1)
    if expression in constants: return constants[expression]
    if expression in {"DynamicVehiclesMod.MODID", "io.github.brooswitminecraft.dynamicvehicles.DynamicVehiclesMod.MODID"}:
        return mod_id
    raise SourceSyntaxError(f"{source}: unresolved namespace expression '{expression}'")


def parse_java_tests(text, source, mod_id):
    """Return a single holder class's tests, rejecting ambiguous ownership."""
    text = _strip_comments(text)
    constants = _java_constants(text)
    holders = list(re.finditer(r"@GameTestHolder\s*\(", text))
    tests = list(re.finditer(r"@GameTest\b", text))
    if not tests: return []
    if not holders:
        raise SourceSyntaxError(f"{source}: @GameTest has no enclosing @GameTestHolder")
    if len(holders) != 1:
        raise SourceSyntaxError(f"{source}: multiple GameTestHolder classes are unsupported")
    holder = holders[0]
    holder_args, holder_after = _parenthesized(text, text.find("(", holder.start()), source)
    holder_namespace = _resolve_namespace(holder_args.strip(), constants, mod_id, source)
    class_match = re.search(r"\bclass\s+\w+[^\{]*\{", text[holder_after:])
    if class_match is None:
        raise SourceSyntaxError(f"{source}: @GameTestHolder has no class")
    class_open = holder_after + class_match.end() - 1
    depth, index = 0, class_open
    while index < len(text):
        if text[index] == "{": depth += 1
        elif text[index] == "}":
            depth -= 1
            if depth == 0:
                class_after = index
                break
        index += 1
    else:
        raise SourceSyntaxError(f"{source}: unterminated GameTestHolder class")
    cases = []
    for match in tests:
        if not class_open < match.start() < class_after:
            raise SourceSyntaxError(f"{source}: @GameTest lies outside its holder class")
        if text[class_open + 1:match.start()].count("{") != text[class_open + 1:match.start()].count("}"):
            raise SourceSyntaxError(f"{source}: nested GameTest declarations are unsupported")
        after_name = match.end()
        if after_name >= len(text) or text[after_name] != "(":
            raise SourceSyntaxError(f"{source}: @GameTest must use explicit attributes")
        args, after_args = _parenthesized(text, after_name, source)
        attributes = _split_attributes(args, source)
        required_value = attributes.get("required", "true")
        if required_value not in {"true", "false"}:
            raise SourceSyntaxError(f"{source}: unresolved required value '{required_value}'")
        namespace = holder_namespace
        if "templateNamespace" in attributes:
            override = _resolve_namespace(attributes["templateNamespace"], constants, mod_id, source)
            if override:
                namespace = override
        method_match = re.match(r"\s*(?:(?:@\w+(?:\s*\([^)]*\))?)\s*)*"
                                r"((?:(?:public|protected|private|static|final|synchronized|abstract)\s+)+)void\s+(\w+)\s*\(",
                                text[after_args:class_after])
        if method_match is None or "static" not in method_match.group(1).split():
            raise SourceSyntaxError(f"{source}: @GameTest is not followed by a static test method")
        cases.append(GameTestCase(source, method_match.group(2), namespace, required_value == "true"))
    return cases


def _quoted_list(body, source):
    entries = re.findall(r"'([^']+)'", body)
    residual = re.sub(r"'[^']+'|\s|,", "", body)
    if not entries or residual:
        raise SourceSyntaxError(f"{source}: unsupported namespace list syntax")
    return entries


def _braced_block(text, start, source):
    open_index = text.find("{", start)
    if open_index < 0:
        raise SourceSyntaxError(f"{source}: missing block")
    depth, index = 0, open_index
    while index < len(text):
        if text[index] == "{": depth += 1
        elif text[index] == "}":
            depth -= 1
            if depth == 0: return text[open_index + 1:index]
        index += 1
    raise SourceSyntaxError(f"{source}: unterminated block")


def selected_namespaces(build_text, mod_id):
    """Resolve the exact default gameTestNamespaces expression used by build.gradle."""
    profile_match = re.search(r"def\s+m478Profiles\s*=\s*\[(.*?)\]", build_text, re.S)
    m454_match = re.search(r"def\s+m454GameTestNamespaces\s*=\s*\[(.*?)\]", build_text, re.S)
    assignments = list(re.finditer(r"\b(?:def\s+)?gameTestNamespaces\s*=", build_text))
    default_expression = re.search(
        r"def\s+gameTestNamespaces\s*=\s*m478Profile\s*==\s*null\s*\?\s*\(\[project\.mod_id\]\s*\+\s*m478Profiles\.values\(\)\s*\+\s*m454GameTestNamespaces\)\.join\('\,'\)",
        build_text, re.S)
    runs = list(re.finditer(r"\bgameTestServer\s*\{", build_text))
    if profile_match is None or m454_match is None or default_expression is None or len(assignments) != 1 or len(runs) != 1:
        raise SourceSyntaxError("build.gradle: unsupported default gameTestNamespaces expression")
    profile_entries = re.findall(r"\b\w+\s*:\s*'([^']+)'", profile_match.group(1))
    profile_residual = re.sub(r"\b\w+\s*:\s*'[^']+'|\s|,", "", profile_match.group(1))
    if not profile_entries or profile_residual:
        raise SourceSyntaxError("build.gradle: unsupported m478Profiles syntax")
    game_test_server = _braced_block(build_text, runs[0].start(), "build.gradle gameTestServer")
    bindings = [binding.strip() for binding in re.findall(
        r"systemProperty\s+'neoforge\.enabledGameTestNamespaces'\s*,\s*([^\n;}]+)", game_test_server)]
    if bindings != ["gameTestNamespaces"]:
        raise SourceSyntaxError("build.gradle: gameTestServer must bind neoforge.enabledGameTestNamespaces to gameTestNamespaces")
    m454_uses = list(re.finditer(r"\bm454GameTestNamespaces\b", build_text))
    if len(m454_uses) != 2 or m454_uses[0].start() != m454_match.start() + m454_match.group(0).find("m454GameTestNamespaces") \
            or m454_uses[1].start() != default_expression.start() + default_expression.group(0).rfind("m454GameTestNamespaces"):
        raise SourceSyntaxError("build.gradle: m454GameTestNamespaces must be declared once and consumed only by the default expression")
    return {mod_id, *profile_entries, *_quoted_list(m454_match.group(1), "build.gradle")}


def selected_cases(source_root, build_file, properties_file):
    properties = Path(properties_file).read_text()
    mod_match = re.search(r"^mod_id=([^\s#]+)", properties, re.M)
    if mod_match is None: raise SourceSyntaxError(f"{properties_file}: missing mod_id")
    mod_id = mod_match.group(1)
    enabled = selected_namespaces(Path(build_file).read_text(), mod_id)
    cases = []
    for path in sorted(Path(source_root).rglob("*.java")):
        cases.extend(parse_java_tests(path.read_text(), str(path), mod_id))
    selected = filter_selected_cases(cases, enabled)
    if not selected or not any(case.required for case in selected):
        raise SourceSyntaxError("no selected required GameTests")
    return selected


def filter_selected_cases(cases, enabled_namespaces):
    """Match NeoForge's namespace filter without treating dedicated aliases as default work."""
    return [case for case in cases if case.namespace in enabled_namespaces]


def verify_runtime_log(log_text, expected):
    if re.search(r"Failed to start the minecraft server", log_text, re.I):
        raise RuntimeEvidenceError("GameTest server startup failed")
    if re.search(r"\b\d+\s+required tests failed\b", log_text):
        raise RuntimeEvidenceError("required GameTest failure evidence")
    completed = list(re.finditer(r"=+\s*(\d+)\s+GAME TESTS COMPLETE\s+IN\s+", log_text))
    passed = list(re.finditer(r"All\s+(\d+)\s+required tests passed", log_text))
    if len(completed) != 1 or len(passed) != 1:
        raise RuntimeEvidenceError("missing or ambiguous completion/pass summary")
    if completed[0].end() > passed[0].start() or completed[0].group(1) != passed[0].group(1):
        raise RuntimeEvidenceError("completion/pass summaries are not one consistent terminal pair")
    if int(completed[0].group(1)) != expected:
        raise RuntimeEvidenceError(f"expected {expected} selected GameTests, log reports {completed[0].group(1)}")
    return int(completed[0].group(1))


def main(argv):
    if len(argv) != 2:
        raise SystemExit(f"usage: {argv[0]} <gametest.log>")
    root = Path(__file__).resolve().parent.parent
    try:
        selected = selected_cases(root / "src/main/java", root / "build.gradle", root / "gradle.properties")
        passed = verify_runtime_log(Path(argv[1]).read_text(), len(selected))
    except (OSError, SourceSyntaxError, RuntimeEvidenceError) as error:
        raise SystemExit(f"GameTest count guard failed: {error}") from error
    print(f"GameTest count guard passed: {passed} selected cases")


if __name__ == "__main__":
    main(sys.argv)
