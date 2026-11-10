package com.handwrite.core.handwriting

/*
 * Minimal assertion helpers.
 *
 * The real test suite uses kotlin.test/JUnit; these two functions exist so the same test bodies can
 * also be executed by tools/dev (see tools/dev/TestRunner.kt) in environments where Maven Central is
 * not reachable - which is exactly the environment the APK is built in.
 */

fun assertEquals(expected: Int, actual: Int, message: String = "") {
    if (expected != actual) throw AssertionError("$message: expected $expected, got $actual")
}

fun assertEquals(expected: Float, actual: Float, message: String = "") {
    if (kotlin.math.abs(expected - actual) > 1e-4f) throw AssertionError("$message: expected $expected, got $actual")
}

fun assertTrue(value: Boolean, message: String = "") {
    if (!value) throw AssertionError(message.ifEmpty { "expected true" })
}
