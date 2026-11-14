package com.irio

import android.content.Context
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

internal object UserLocationTool {
    const val NAME = "get_location"
    private const val DESCRIPTION = "Get the user's current device location when needed to answer a location-dependent request. " +
        "If location permission has not been granted, the app asks the user at call time. " +
        "The user may allow approximate location or deny access. Returns latitude, longitude, accuracy, " +
        "timestamp and permission precision, or an explicit error. No parameters. Never infer a location on failure."

    fun schemaFor(protocol: ProviderProtocol): JSONObject {
        val parameters = JSONObject().put("type", "object").put("properties", JSONObject())
            .put("required", JSONArray()).put("additionalProperties", false)
        val function = JSONObject().put("name", NAME).put("description", DESCRIPTION)
        return when (protocol) {
            ProviderProtocol.CHAT_COMPLETIONS, ProviderProtocol.MINIMAX_VIDEO -> JSONObject()
                .put("type", "function").put("function", function.put("parameters", parameters))
            ProviderProtocol.RESPONSES -> function.put("type", "function").put("parameters", parameters).put("strict", true)
            ProviderProtocol.ANTHROPIC -> function.put("input_schema", parameters)
            ProviderProtocol.GEMINI -> GeminiProtocol.declaration(NAME, DESCRIPTION, parameters)
            ProviderProtocol.GEMINI_INTERACTIONS -> GeminiInteractionsProtocol.declaration(NAME, DESCRIPTION, parameters)
        }
    }
}

internal class UserLocationToolProvider(
    private val locate: suspend (Context, ChatRequestCancellation) -> ToolExecutionResult = UserLocationAccess::get,
) : ToolProvider {
    override val id = "user-location"
    override fun definitions(protocol: ProviderProtocol) = listOf(UserLocationTool.schemaFor(protocol))
    override fun handles(name: String) = name == UserLocationTool.NAME

    override suspend fun execute(call: ChatToolCall, context: ToolExecutionContext): ToolExecutionResult {
        context.onUpdate(ChatStreamUpdate(toolActive = true, toolName = UserLocationTool.NAME))
        var result = ToolExecutionResult(tr("获取用户位置失败"), outcome = ToolOutcome.FAILED)
        try {
            result = if (context.appContext == null) {
                ToolExecutionResult(tr("定位当前不可用。"), outcome = ToolOutcome.FAILED)
            } else locate(context.appContext, context.cancellation)
            return result
        } catch (cancelled: CancellationException) {
            result = ToolExecutionResult(tr("获取用户位置已停止"), outcome = ToolOutcome.STOPPED)
            throw cancelled
        } finally {
            context.onUpdate(ChatStreamUpdate(toolActive = false, toolName = UserLocationTool.NAME,
                toolContent = result.text, toolOutcome = result.outcome))
        }
    }
}
