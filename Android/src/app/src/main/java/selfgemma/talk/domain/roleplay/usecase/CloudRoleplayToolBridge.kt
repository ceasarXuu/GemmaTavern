package selfgemma.talk.domain.roleplay.usecase

import com.google.ai.edge.litertlm.ToolProvider
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import selfgemma.talk.domain.cloudllm.CloudToolCall
import selfgemma.talk.domain.cloudllm.CloudToolSpec

internal data class LocalCloudTool(
  val spec: CloudToolSpec,
  val execute: (String) -> String,
)

internal class CloudRoleplayToolBridge {
  private val gson = Gson()

  fun collectLocalTools(providers: List<ToolProvider>): List<LocalCloudTool> {
    return providers.flatMap { provider ->
      val providerTools = provider.provideJsonToolsByReflection()
      providerTools.mapNotNull { (fallbackName, tool) -> tool.toLocalCloudTool(fallbackName) }
    }
  }

  fun executeLocalToolCalls(
    toolCalls: List<CloudToolCall>,
    localTools: List<LocalCloudTool>,
  ): String {
    val toolsByName = localTools.associateBy { tool -> tool.spec.name }
    val lines =
      toolCalls.take(MAX_TOOL_CALLS_PER_TURN).map { call ->
        val result =
          runCatching {
            toolsByName[call.name]?.execute?.invoke(call.argumentsJson)
              ?: """{"error":"Local tool is not registered for this turn."}"""
          }.getOrElse { error ->
            """{"error":${gson.toJson(error.message ?: "Local tool execution failed.")}}"""
          }
        "- ${call.name}: $result"
      }
    return "Local tool results:\n${lines.joinToString(separator = "\n")}"
  }

  @Suppress("UNCHECKED_CAST")
  private fun ToolProvider.provideJsonToolsByReflection(): Map<String, Any> {
    val method =
      javaClass.methods.firstOrNull { method ->
        method.name.startsWith("provideTools") && Map::class.java.isAssignableFrom(method.returnType)
      } ?: return emptyMap()
    return runCatching {
      method.isAccessible = true
      method.invoke(this) as? Map<String, Any>
    }.getOrNull().orEmpty()
  }

  private fun Any.toLocalCloudTool(fallbackName: String): LocalCloudTool? {
    val description = readToolDescription() ?: return null
    val schema = description.extractToolSchema(fallbackName)
    return LocalCloudTool(
      spec =
        CloudToolSpec(
          name = schema.name,
          description = schema.description,
          parametersJson = schema.parameters.toString(),
        ),
      execute = { argsJson ->
        val args = argsJson.parseJsonObjectOrEmpty()
        val executeMethod = javaClass.methods.firstOrNull { method -> method.name == "execute" }
          ?: error("Tool execute method missing.")
        formatToolResult(executeMethod.invoke(this, args))
      },
    )
  }

  private fun Any.readToolDescription(): JsonObject? {
    val method = javaClass.methods.firstOrNull { method -> method.name == "getToolDescription" } ?: return null
    return runCatching {
      method.isAccessible = true
      method.invoke(this) as? JsonObject
    }.getOrNull()
  }

  private fun JsonObject.extractToolSchema(fallbackName: String): ToolSchema {
    val function = obj("function")
    val declaration = array("function_declarations")?.firstOrNull()?.asJsonObject
    val source = function ?: declaration ?: this
    return ToolSchema(
      name = source.string("name").ifBlank { fallbackName },
      description = source.string("description"),
      parameters = source.obj("parameters") ?: source.obj("input_schema") ?: JsonObject().objectSchema(),
    )
  }

  private fun String.parseJsonObjectOrEmpty(): JsonObject {
    return runCatching { JsonParser.parseString(this).asJsonObject }.getOrElse { JsonObject() }
  }

  private fun JsonObject.obj(name: String): JsonObject? = get(name)?.takeIf(JsonElement::isJsonObject)?.asJsonObject

  private fun JsonObject.array(name: String) = get(name)?.takeIf(JsonElement::isJsonArray)?.asJsonArray

  private fun JsonObject.string(name: String): String = get(name)?.takeIf { !it.isJsonNull }?.asString.orEmpty()

  private fun JsonObject.objectSchema(): JsonObject = apply {
    addProperty("type", "object")
    add("properties", JsonObject())
  }

  private fun formatToolResult(value: Any?): String {
    return when (value) {
      null -> "null"
      is JsonElement -> value.toString()
      is String -> value
      else -> gson.toJson(value)
    }
  }

  private data class ToolSchema(
    val name: String,
    val description: String,
    val parameters: JsonObject,
  )

  private companion object {
    const val MAX_TOOL_CALLS_PER_TURN = 4
  }
}
