import unittest

from check_gametest_count import (RuntimeEvidenceError, SourceSyntaxError, filter_selected_cases,
                                  parse_java_tests, selected_namespaces, verify_runtime_log)


class GameTestCountTest(unittest.TestCase):
    def test_filters_by_override_and_holder_fallback_and_ignores_optional(self):
        source = '''
            @GameTestHolder("holder") class Fixture {
              private static final String EXTRA = "extra";
              @GameTest(template = "settlement_test") public static void fallback(GameTestHelper helper) {}
              @GameTest(templateNamespace = EXTRA, template = "settlement_test") public static void override(GameTestHelper helper) {}
              @GameTest(required = false, template = "settlement_test") public static void optional(GameTestHelper helper) {}
            }
        '''
        cases = parse_java_tests(source, "Fixture.java", "dynamicvehicles")
        self.assertEqual([case.namespace for case in cases], ["holder", "extra", "holder"])
        self.assertEqual([case.required for case in cases], [True, True, False])
        self.assertEqual([case.method for case in filter_selected_cases(cases, {"holder", "extra"})],
                         ["fallback", "override", "optional"])
        self.assertEqual([case.method for case in filter_selected_cases(cases, {"holder"})], ["fallback", "optional"])

    def test_empty_template_override_falls_back_to_holder(self):
        source = '''@GameTestHolder("holder") class Fixture {
            @GameTest(templateNamespace = "", template = "x") public static void fallback(GameTestHelper helper) {}
        }'''
        self.assertEqual(parse_java_tests(source, "Fixture.java", "dynamicvehicles")[0].namespace, "holder")

    def test_multiple_holders_and_nonstatic_declarations_fail_closed(self):
        multiple = '''@GameTestHolder("disabled") class A { @GameTest(template = "x") public static void a(GameTestHelper h) {} }
            @GameTestHolder("dynamicvehicles") class B { @GameTest(template = "x") public static void b(GameTestHelper h) {} }'''
        nonstatic = '''@GameTestHolder("dynamicvehicles") class Fixture {
            @GameTest(template = "x") public void wrong(GameTestHelper h) {}
            public static void later(GameTestHelper h) {}
        }'''
        with self.assertRaises(SourceSyntaxError): parse_java_tests(multiple, "Multiple.java", "dynamicvehicles")
        with self.assertRaises(SourceSyntaxError): parse_java_tests(nonstatic, "Nonstatic.java", "dynamicvehicles")

    def test_build_namespace_resolution_uses_default_expression(self):
        build = '''
            def m478Profiles = [ native: 'native_ns', interaction: 'interaction_ns' ]
            def m454GameTestNamespaces = [ 'income_a', 'income_b' ]
            def gameTestNamespaces = m478Profile == null
                    ? ([project.mod_id] + m478Profiles.values() + m454GameTestNamespaces).join(',')
                    : m478Profiles.get(m478Profile)
            neoForge { runs { gameTestServer { type = "gameTestServer"; systemProperty 'neoforge.enabledGameTestNamespaces', gameTestNamespaces } } }
        '''
        self.assertEqual(selected_namespaces(build, "dynamicvehicles"),
                         {"dynamicvehicles", "native_ns", "interaction_ns", "income_a", "income_b"})

    def test_build_binding_must_match_game_test_server_execution(self):
        build = '''
            def m478Profiles = [ native: 'native_ns' ]
            def m454GameTestNamespaces = [ 'income_a' ]
            def gameTestNamespaces = m478Profile == null
                    ? ([project.mod_id] + m478Profiles.values() + m454GameTestNamespaces).join(',')
                    : m478Profiles.get(m478Profile)
            neoForge { runs { gameTestServer { type = "gameTestServer"; systemProperty 'neoforge.enabledGameTestNamespaces', 'different_namespace' } } }
        '''
        with self.assertRaises(SourceSyntaxError): selected_namespaces(build, "dynamicvehicles")

    def test_build_rejects_complete_binding_expression_and_namespace_list_mutation(self):
        good = '''
            def m478Profiles = [ native: 'native_ns' ]
            def m454GameTestNamespaces = [ 'income_a' ]
            def gameTestNamespaces = m478Profile == null
                    ? ([project.mod_id] + m478Profiles.values() + m454GameTestNamespaces).join(',')
                    : m478Profiles.get(m478Profile)
            neoForge { runs { gameTestServer { type = "gameTestServer"; systemProperty 'neoforge.enabledGameTestNamespaces', gameTestNamespaces } } }
        '''
        self.assertEqual(selected_namespaces(good, "dynamicvehicles"), {"dynamicvehicles", "native_ns", "income_a"})
        with self.assertRaises(SourceSyntaxError):
            selected_namespaces(good.replace("gameTestNamespaces }", "gameTestNamespaces + ',dynamicvehicles_m454_wandering_claim' }"),
                                "dynamicvehicles")
        with self.assertRaises(SourceSyntaxError):
            selected_namespaces(good.replace("def gameTestNamespaces", "m454GameTestNamespaces << 'dynamicvehicles_m454_wandering_claim'\n            def gameTestNamespaces"),
                                "dynamicvehicles")

    def test_unknown_annotation_expression_fails_closed(self):
        source = '''@GameTestHolder("holder") class Fixture {
            @GameTest(templateNamespace = unknownNamespace, template = "x") public static void test(GameTestHelper helper) {}
        }'''
        with self.assertRaises(SourceSyntaxError):
            parse_java_tests(source, "Fixture.java", "dynamicvehicles")

    def test_malformed_required_value_fails_closed(self):
        source = '''@GameTestHolder("holder") class Fixture {
            @GameTest(required = REQUIRED, template = "x") public static void test(GameTestHelper helper) {}
        }'''
        with self.assertRaises(SourceSyntaxError):
            parse_java_tests(source, "Fixture.java", "dynamicvehicles")

    def test_runtime_evidence_requires_complete_matching_summary(self):
        with self.assertRaises(RuntimeEvidenceError):
            verify_runtime_log("All 2 required tests passed", 2)
        with self.assertRaises(RuntimeEvidenceError):
            verify_runtime_log("==== 2 GAME TESTS COMPLETE IN 1.0 s ====\nAll 1 required tests passed", 2)
        with self.assertRaises(RuntimeEvidenceError):
            verify_runtime_log("Failed to start the minecraft server\n==== 2 GAME TESTS COMPLETE IN 1.0 s ====\nAll 2 required tests passed", 2)
        with self.assertRaises(RuntimeEvidenceError):
            verify_runtime_log("==== 2 GAME TESTS COMPLETE IN 1.0 s ====\n==== 2 GAME TESTS COMPLETE IN 1.0 s ====\nAll 2 required tests passed", 2)
        with self.assertRaises(RuntimeEvidenceError):
            verify_runtime_log("==== 0 GAME TESTS COMPLETE IN 1.0 s ====\nAll 321 required tests passed", 321)
        with self.assertRaises(RuntimeEvidenceError):
            verify_runtime_log("All 2 required tests passed\n==== 2 GAME TESTS COMPLETE IN 1.0 s ====", 2)
        with self.assertRaises(RuntimeEvidenceError):
            verify_runtime_log("==== 2 GAME TESTS COMPLETE IN 1.0 s ====\n1 required tests failed :(\nAll 2 required tests passed", 2)
        self.assertEqual(verify_runtime_log("==== 2 GAME TESTS COMPLETE IN 1.0 s ====\nAll 2 required tests passed", 2), 2)


if __name__ == "__main__":
    unittest.main()
