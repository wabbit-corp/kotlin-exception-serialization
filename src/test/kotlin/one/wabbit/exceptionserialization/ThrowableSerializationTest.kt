package one.wabbit.exceptionserialization

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ThrowableSerializationTest {
    // -- helpers --------------------------------------------------------------

    private fun frame(
        className: String,
        method: String = "m",
        file: String? = "X.kt",
        line: Int = 1,
    ) = StackTraceElement(className, method, file, line)

    private fun nativeFrame(className: String, method: String = "m", file: String? = null) =
        StackTraceElement(className, method, file, -2) // -2 => isNativeMethod == true

    private fun JsonObject.stack(): JsonArray = this["stackTrace"]!!.jsonArray

    private fun JsonObject.hasKey(key: String): Boolean = this.containsKey(key)

    private fun causeChain(length: Int): Throwable {
        require(length >= 1)
        val root = RuntimeException("E0")
        var current = root
        for (i in 1 until length) {
            val next = RuntimeException("E$i")
            current.initCause(next)
            current = next
        }
        return root
    }

    private class OomThrowable(msg: String?) : RuntimeException(msg) {
        @Suppress("RedundantOverride")
        override fun getStackTrace(): Array<StackTraceElement> =
            throw OutOfMemoryError("simulated OOME")
    }

    // -- tests ----------------------------------------------------------------

    @Test
    fun suffixTrimming_removes_terminal_framework_frames_only() {
        val t =
            RuntimeException("boom").apply {
                stackTrace =
                    arrayOf(
                        frame("app.A", "f", "A.kt", 10),
                        frame(
                            "java.util.concurrent.ForkJoinTask",
                            "doExec",
                            "ForkJoinTask.java",
                            100,
                        ),
                        frame("java.lang.Thread", "run", "Thread.java", 748),
                    )
            }

        val json = t.toJsonObject()
        val stk = json.stack()
        assertEquals(1, stk.size, "Expected only non-framework frames after suffix trim")
        assertEquals("app.A", stk[0].jsonObject["className"]!!.jsonPrimitive.content)
    }

    @Test
    fun suffixTrimming_keeps_midstack_framework_frames() {
        val t =
            RuntimeException("boom").apply {
                stackTrace =
                    arrayOf(
                        frame("app.Top", "top", "Top.kt", 1),
                        frame("java.util.concurrent.Foo", "bar", "Foo.java", 2),
                        frame("app.Bottom", "bot", "Bottom.kt", 3),
                        frame("java.lang.Thread", "run", "Thread.java", 748),
                    )
            }

        val json = t.toJsonObject()
        val stk = json.stack()
        assertEquals(3, stk.size)
        assertEquals("app.Top", stk[0].jsonObject["className"]!!.jsonPrimitive.content)
        assertEquals(
            "java.util.concurrent.Foo",
            stk[1].jsonObject["className"]!!.jsonPrimitive.content,
            "mid-stack framework frame should remain",
        )
        assertEquals("app.Bottom", stk[2].jsonObject["className"]!!.jsonPrimitive.content)
    }

    @Test
    fun maxFrames_limits_after_trimming() {
        val t =
            RuntimeException("boom").apply {
                stackTrace =
                    arrayOf(
                        frame("app.F1"),
                        frame("app.F2"),
                        frame("app.F3"),
                        frame("app.F4"),
                        frame("app.F5"),
                    )
            }
        val json = t.toJsonObject(ThrowableJsonOptions(maxFrames = 3, trimFrameworkSuffix = false))
        val stk = json.stack()
        assertEquals(3, stk.size)
        assertEquals("app.F1", stk[0].jsonObject["className"]!!.jsonPrimitive.content)
        assertEquals("app.F3", stk[2].jsonObject["className"]!!.jsonPrimitive.content)
    }

    @Test
    fun frame_fields_lineNumber_and_isNative_emitted_selectively() {
        val t =
            RuntimeException("boom").apply {
                stackTrace =
                    arrayOf(
                        frame("C1", "m", "C1.kt", -1), // unknown line -> omit lineNumber
                        nativeFrame("C2", "m", null), // native -> isNative=true, fileName=null
                        frame(
                            "C3",
                            "m",
                            "C3.kt",
                            42,
                        ), // normal -> lineNumber present, isNative omitted
                    )
            }

        val json = t.toJsonObject(ThrowableJsonOptions(trimFrameworkSuffix = false))
        val stk = json.stack()
        assertEquals(3, stk.size)

        val f0 = stk[0].jsonObject
        assertEquals("C1", f0["className"]!!.jsonPrimitive.content)
        assertFalse(f0.hasKey("lineNumber"), "lineNumber should be omitted when < 0")
        assertFalse(f0.hasKey("isNative"))

        val f1 = stk[1].jsonObject
        assertEquals("C2", f1["className"]!!.jsonPrimitive.content)
        assertTrue(f1["fileName"] is JsonNull, "fileName should be JsonNull when original is null")
        assertTrue(
            f1["isNative"]!!.jsonPrimitive.boolean,
            "isNative should be true for native frames",
        )
        assertFalse(f1.hasKey("lineNumber"), "native frames shouldn’t carry a numeric line")

        val f2 = stk[2].jsonObject
        assertEquals("C3", f2["className"]!!.jsonPrimitive.content)
        assertEquals(42, f2["lineNumber"]!!.jsonPrimitive.int)
        assertFalse(f2.hasKey("isNative"))
    }

    @Test
    fun cause_serializes_and_truncates_at_maxDepth() {
        val root = causeChain(length = 3) // E0 -> E1 -> E2
        val json = root.toJsonObject(ThrowableJsonOptions(maxDepth = 1))

        val causeLevel1 = json["cause"]!!.jsonObject
        assertTrue(
            causeLevel1["truncated"]!!.jsonPrimitive.boolean,
            "First cause object should be marked truncated at depth cap",
        )
        assertTrue(
            causeLevel1["stackTrace"]!!.jsonArray.isEmpty(),
            "Truncated node should have empty stackTrace",
        )
        assertEquals(
            JsonNull,
            causeLevel1["cause"],
            "Truncated node should not expand further causes",
        )
        assertTrue(causeLevel1["suppressed"]!!.jsonArray.isEmpty())
    }

    @Test
    fun suppressed_is_bounded_and_adds_omission_marker() {
        val root = RuntimeException("root")
        val s1 = IllegalStateException("s1")
        val s2 = IllegalArgumentException("s2")
        root.addSuppressed(s1)
        root.addSuppressed(s2)

        val json = root.toJsonObject(ThrowableJsonOptions(maxSuppressed = 1))
        val sup = json["suppressed"]!!.jsonArray
        assertEquals(2, sup.size, "One entry + omission marker expected")
        assertEquals(
            "java.lang.IllegalStateException",
            sup[0].jsonObject["className"]!!.jsonPrimitive.content,
        )
        val omitted = sup[1].jsonObject["omitted"]!!.jsonPrimitive.int
        assertEquals(1, omitted, "Should report exactly one omitted suppressed throwable")
    }

    @Test
    fun cycle_in_cause_is_detected_and_does_not_recurse_forever() {
        val a: Throwable = RuntimeException("A")
        val b: Throwable = RuntimeException("B")
        a.initCause(b)
        b.initCause(a) // create cycle

        val json = a.toJsonObject()

        val bJson = json["cause"]!!.jsonObject
        val cycleNode = bJson["cause"]!!.jsonObject
        assertTrue(
            cycleNode["cycle"]!!.jsonPrimitive.boolean,
            "Cycle marker expected when revisiting a seen Throwable",
        )
        // sanity: the cycle node should be a small stub
        assertTrue(cycleNode["stackTrace"]!!.jsonArray.isEmpty())
        assertEquals(JsonNull, cycleNode["cause"])
        assertTrue(cycleNode["suppressed"]!!.jsonArray.isEmpty())
    }

    @Test
    fun cycle_in_suppressed_is_detected() {
        val d = RuntimeException("D")
        val e = RuntimeException("E")
        d.addSuppressed(e)
        e.addSuppressed(d) // suppressed cycle

        val json = d.toJsonObject()
        val sup0 = json["suppressed"]!!.jsonArray[0].jsonObject // E
        val deep = sup0["suppressed"]!!.jsonArray[0].jsonObject // cycle stub of D
        assertTrue(
            deep["cycle"]!!.jsonPrimitive.boolean,
            "Cycle in suppressed chain should be marked",
        )
    }

    @Test
    fun null_message_serializes_as_jsonNull_and_cause_absent_serializes_as_null() {
        val t = RuntimeException(null as String?)
        val json = t.toJsonObject()
        assertTrue(json["message"] is JsonNull, "Null message should be JsonNull")
        assertEquals(JsonNull, json["cause"], "Absent cause should be JsonNull")
    }

    @Test
    fun trimFrameworkSuffix_false_keeps_suffix_frames() {
        val t =
            RuntimeException("boom").apply {
                stackTrace =
                    arrayOf(
                        frame("app.A", "f", "A.kt", 10),
                        frame("java.lang.Thread", "run", "Thread.java", 748),
                    )
            }

        val json = t.toJsonObject(ThrowableJsonOptions(trimFrameworkSuffix = false))
        val stk = json.stack()
        assertEquals(2, stk.size)
        assertEquals("java.lang.Thread", stk[1].jsonObject["className"]!!.jsonPrimitive.content)
    }

    @Test
    fun custom_exclude_prefixes_respected() {
        val t =
            RuntimeException("boom").apply {
                stackTrace =
                    arrayOf(
                        frame("app.A"),
                        frame("java.util.concurrent.ForkJoinTask"),
                        frame("java.lang.Thread"),
                    )
            }
        val opts =
            ThrowableJsonOptions(
                excludeSuffixPrefixes = setOf("java.lang.") // do NOT exclude java.util.concurrent
            )
        val json = t.toJsonObject(opts)
        val stk = json.stack()
        assertEquals(2, stk.size)
        assertEquals("app.A", stk[0].jsonObject["className"]!!.jsonPrimitive.content)
        assertEquals(
            "java.util.concurrent.ForkJoinTask",
            stk[1].jsonObject["className"]!!.jsonPrimitive.content,
            "Should retain the last java.util.concurrent.* frame when only java.lang.* is excluded",
        )
    }

    @Test
    fun outOfMemory_fallback_minimal_payload() {
        val t = OomThrowable("boom")
        val json = t.toJsonObject()
        assertEquals(t.javaClass.name, json["className"]!!.jsonPrimitive.content)
        assertEquals("boom", json["message"]!!.jsonPrimitive.content)
        assertFalse(json.hasKey("stackTrace"), "Fallback should not include stackTrace")
        assertFalse(json.hasKey("cause"), "Fallback should not include cause")
        assertFalse(json.hasKey("suppressed"), "Fallback should not include suppressed")
    }
}
