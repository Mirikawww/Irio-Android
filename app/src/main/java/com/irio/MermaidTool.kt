package com.irio

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidEngine
import com.swithun.cmpmermaid.core.MermaidRenderContext
import com.swithun.cmpmermaid.core.TextMetricProvider
import com.swithun.cmpmermaid.core.TextMetrics
import com.swithun.cmpmermaid.core.TextMetricsRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal object MermaidTool {
    const val NAME = "render_mermaid"
    const val MAX_SOURCE_LENGTH = 50_000
    private const val DESCRIPTION =
        "Render and display a Mermaid diagram to the user, inline at this point in the conversation. " +
            "Use for flowcharts, sequence diagrams, class/state/ER diagrams, Gantt, pie, mindmaps and other Mermaid diagrams. " +
            "Supply raw Mermaid source without Markdown fences. Rendering is native and offline. " +
            "Use classic look and Dagre layout; ELK, handDrawn and arbitrary themeCSS are not supported. " +
            "Invalid or unsupported source returns a diagnostic: correct the source and call again. " +
            "The diagram is the visible output; do not repeat its source in the normal reply or narrate the tool call."

    fun schemaFor(protocol: ProviderProtocol): JSONObject {
        val parameters = JSONObject().put("type", "object").put("properties", JSONObject()
            .put("source", JSONObject().put("type", "string").put("minLength", 1).put("maxLength", MAX_SOURCE_LENGTH)
                .put("description", "Complete Mermaid source, for example flowchart LR\\n  A[Start] --> B[Finish]. No Markdown fences.")))
            .put("required", JSONArray(listOf("source"))).put("additionalProperties", false)
        return when (protocol) {
            ProviderProtocol.CHAT_COMPLETIONS, ProviderProtocol.MINIMAX_VIDEO -> JSONObject().put("type", "function").put("function",
                JSONObject().put("name", NAME).put("description", DESCRIPTION).put("parameters", parameters))
            ProviderProtocol.RESPONSES -> JSONObject().put("type", "function").put("name", NAME)
                .put("description", DESCRIPTION).put("parameters", parameters).put("strict", true)
            ProviderProtocol.ANTHROPIC -> JSONObject().put("name", NAME).put("description", DESCRIPTION).put("input_schema", parameters)
            ProviderProtocol.GEMINI -> GeminiProtocol.declaration(NAME, DESCRIPTION, parameters)
            ProviderProtocol.GEMINI_INTERACTIONS -> GeminiInteractionsProtocol.declaration(NAME, DESCRIPTION, parameters)
        }
    }
}

internal class MermaidToolProvider : ToolProvider {
    override val id = "mermaid"
    override fun definitions(protocol: ProviderProtocol) = listOf(MermaidTool.schemaFor(protocol))
    override fun handles(name: String) = name == MermaidTool.NAME
    override fun systemInstructions(): String =
        "Mermaid diagrams are available through render_mermaid. When a diagram helps explain an answer, " +
            "call render_mermaid with complete Mermaid source to insert the diagram between text sections. " +
            "Prefer classic/Dagre syntax. The tool validates source and returns actionable errors for correction. " +
            "Do not duplicate the displayed diagram as a code block or describe the tool invocation."

    override suspend fun execute(call: ChatToolCall, context: ToolExecutionContext): ToolExecutionResult {
        if (context.cancellation.isCancelled()) return ToolExecutionResult("Mermaid rendering cancelled", outcome = ToolOutcome.STOPPED)
        val source = runCatching { JSONObject(call.arguments).getString("source").trim().replace("\r\n", "\n") }
            .getOrNull()?.takeIf { it.isNotBlank() && it.length <= MermaidTool.MAX_SOURCE_LENGTH }
            ?: return ToolExecutionResult("Invalid source: provide a nonblank Mermaid source string of at most ${MermaidTool.MAX_SOURCE_LENGTH} characters.",
                outcome = ToolOutcome.FAILED)
        val raw = mermaidCodeFenceSource(source) ?: source
        // Validate with the same parser/layout as the UI. Exact font metrics are supplied by Compose later.
        val result = withContext(Dispatchers.Default) {
            MermaidEngine().render(raw, MermaidRenderContext(object : TextMetricProvider {
                override fun measure(request: TextMetricsRequest): TextMetrics {
                    val lines = request.text.lines()
                    return TextMetrics(lines.maxOf { it.length }.coerceAtLeast(1) * request.fontSize * 0.6f,
                        lines.size * request.fontSize * 1.4f)
                }
            }))
        }
        if (context.cancellation.isCancelled()) return ToolExecutionResult("Mermaid rendering cancelled", outcome = ToolOutcome.STOPPED)
        if (result is GMResult.Err) return ToolExecutionResult(
            "Mermaid diagram rejected: ${result.error.message.take(2000)}\nCorrect the source and retry render_mermaid.",
            outcome = ToolOutcome.FAILED)
        context.onUpdate(ChatStreamUpdate(displayedMermaid = raw))
        return ToolExecutionResult("Mermaid diagram sent to the user. Do not repeat its source or narrate tool use.")
    }
}
