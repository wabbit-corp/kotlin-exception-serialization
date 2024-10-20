package one.wabbit.exceptionserialization

import kotlinx.serialization.json.*

private fun stringOrNull(s: String?): JsonElement =
    if (s == null) JsonNull else JsonPrimitive(s)

val DEFAULT_EXCLUDED_PREFIXES = setOf(
    "java.lang.",
    "java.util.concurrent.",
    "io.netty.",
)

fun Throwable.toJsonObject(excludePrefixes: Set<String> = DEFAULT_EXCLUDED_PREFIXES): JsonObject {
    val error = this
    val className = stringOrNull(error.javaClass.name)
    val message = stringOrNull(error.message)
    val causeObj = error.cause
    val cause = causeObj?.toJsonObject(excludePrefixes) ?: JsonNull
    val suppressed = JsonArray(error.suppressed.map { it.toJsonObject(excludePrefixes) })

    val stackTrace = JsonArray(
        error.stackTrace.toMutableList().dropLastWhile { s ->
            val elementClassName = s.className ?: ""
            excludePrefixes.any { elementClassName.startsWith(it) }
        }.map { s ->
            JsonObject(
                mapOf(
                    "className" to stringOrNull(s.className),
                    "methodName" to stringOrNull(s.methodName),
                    "fileName" to stringOrNull(s.fileName),
                    "lineNumber" to JsonPrimitive(s.lineNumber),
                    "isNative" to JsonPrimitive(s.isNativeMethod)
                )
            )
        }
    )

    return JsonObject(
        mapOf(
            "className" to className,
            "stackTrace" to stackTrace,
            "message" to message,
            "cause" to cause,
            "suppressed" to suppressed
        )
    )
}
