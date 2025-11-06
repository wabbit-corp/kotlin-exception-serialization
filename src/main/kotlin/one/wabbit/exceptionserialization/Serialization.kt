package one.wabbit.exceptionserialization

import java.util.IdentityHashMap
import kotlin.math.min
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private fun String?.asJson(): JsonElement = this?.let { JsonPrimitive(it) } ?: JsonNull

val DEFAULT_EXCLUDED_SUFFIX_PREFIXES = setOf("java.lang.", "java.util.concurrent.", "io.netty.")

@Serializable
data class ThrowableJsonOptions(
    val excludeSuffixPrefixes: Set<String> = DEFAULT_EXCLUDED_SUFFIX_PREFIXES,
    val maxDepth: Int = 16,
    val maxFrames: Int = 256,
    val maxSuppressed: Int = 32,
    val trimFrameworkSuffix: Boolean = true,
)

private fun JsonArrayBuilder.addFrames(frames: List<StackTraceElement>) {
    for (s in frames) {
        add(
            buildJsonObject {
                put("className", s.className.asJson())
                put("methodName", s.methodName.asJson())
                put("fileName", s.fileName.asJson())
                if (s.lineNumber >= 0) put("lineNumber", s.lineNumber)
                if (s.isNativeMethod) put("isNative", true)
            }
        )
    }
}

fun Throwable.toJsonObject(options: ThrowableJsonOptions = ThrowableJsonOptions()): JsonObject {
    val seen = IdentityHashMap<Throwable, Boolean>()

    fun framesOf(t: Throwable): List<StackTraceElement> {
        val list = t.stackTrace.asList()
        var end = list.size
        if (options.trimFrameworkSuffix) {
            while (
                end > 0 &&
                    options.excludeSuffixPrefixes.any { prefix ->
                        list[end - 1].className.startsWith(prefix)
                    }
            ) {
                end--
            }
        }
        return list.subList(0, min(end, options.maxFrames))
    }

    fun visit(t: Throwable, depth: Int): JsonObject {
        val prefix = "  ".repeat(depth)
        if (seen.put(t, true) != null) {
            return buildJsonObject {
                put("className", t.javaClass.name.asJson())
                put("message", t.message.asJson())
                put("stackTrace", buildJsonArray {}) // empty to avoid loops
                put("cause", JsonNull)
                put("suppressed", buildJsonArray {})
                put("cycle", JsonPrimitive(true))
            }
        }
        if (depth >= options.maxDepth) {
            return buildJsonObject {
                put("className", t.javaClass.name.asJson())
                put("message", t.message.asJson())
                put("stackTrace", buildJsonArray {}) // intentionally truncated
                put("cause", JsonNull)
                put("suppressed", buildJsonArray {})
                put("truncated", JsonPrimitive(true))
            }
        }

        val frames = framesOf(t)

        return buildJsonObject {
            put("className", t.javaClass.name.asJson())
            put("message", t.message.asJson())
            put("stackTrace", buildJsonArray { addFrames(frames) })

            // cause
            val cause = t.cause
            if (cause != null) {
                put("cause", visit(cause, depth + 1))
            } else {
                put("cause", JsonNull)
            }

            // suppressed (bounded)
            val sup = t.suppressed
            put(
                "suppressed",
                buildJsonArray {
                    for (i in 0 until min(sup.size, options.maxSuppressed)) {
                        add(visit(sup[i], depth + 1))
                    }
                    if (sup.size > options.maxSuppressed) {
                        add(
                            buildJsonObject {
                                put("omitted", JsonPrimitive(sup.size - options.maxSuppressed))
                            }
                        )
                    }
                },
            )
        }
    }

    return try {
        visit(this, 0)
    } catch (_: OutOfMemoryError) {
        // last-ditch minimal payload
        buildJsonObject {
            put("className", this@toJsonObject.javaClass.name.asJson())
            put("message", this@toJsonObject.message.asJson())
        }
    }
}
