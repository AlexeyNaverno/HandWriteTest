import com.handwrite.core.handwriting.GlyphNormalizerTest
import com.handwrite.core.handwriting.PenVectorizerTest

/**
 * Dev harness: runs the unit tests without Gradle.
 *
 * The same test bodies are executed by `./gradlew test` in CI (and by F-Droid's build), but in this
 * sandbox Maven Central is not reachable, so they are called directly here:
 *
 *   java -cp build/dev/classes:... TestRunnerKt
 */
fun main() {
    var failed = 0
    var passed = 0
    fun run(name: String, body: () -> Unit) {
        try {
            body()
            passed++
            println("PASS  $name")
        } catch (e: Throwable) {
            failed++
            println("FAIL  $name -> ${e.message}")
        }
    }
    val pv = PenVectorizerTest()
    run("pen: single stroke stays one movement") { pv.singleStroke() }
    run("pen: width profile follows the pen") { pv.widthProfile() }
    run("pen: a crossing is passed through") { pv.crossing() }
    run("pen: empty tile gives no strokes") { pv.empty() }
    run("pen: two blobs are two strokes") { pv.twoBlobs() }

    val gn = GlyphNormalizerTest()
    run("norm: small letters are exactly one x-height") { gn.smallLetters() }
    run("norm: ascender keeps proportion") { gn.ascender() }
    run("norm: descender keeps its tail") { gn.descender() }
    run("norm: absurd measurements are clamped") { gn.clamped() }

    val lt = com.handwrite.core.handwriting.LigatureTest()
    run("lig: a learned pair replaces two letters") { lt.layout() }
    run("lig: pair rules") { lt.rules() }

    println()
    println("$passed passed, $failed failed")
    if (failed > 0) kotlin.system.exitProcess(1)
}
