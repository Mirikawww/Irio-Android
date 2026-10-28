package com.irio

/** One immutable send snapshot, shared by compression preflight and generation. Never global. */
internal class GenerationRequestPreparation(
    source: List<ChatMessage>,
    private val estimateRequest: (List<ChatRequestMessage>) -> Int = ::estimateRequestTokens,
    private val estimateTools: (List<ChatMessage>) -> Int = ::estimateToolContextTokens,
) {
    private val source = source.toList()
    val messages: List<ChatRequestMessage> by lazy { modelRequestMessages(this.source) }
    val requestTokens: Int by lazy { estimateRequest(messages) }
    val toolContextTokens: Int by lazy { estimateTools(this.source) }

    fun compressionPlan(
        enabled: Boolean,
        triggerPercent: Int,
        isImageModel: () -> Boolean,
        contextWindow: () -> Int,
    ): CompressionPlan? {
        // Disabled compression, image models and unknown windows must not initialize the tokenizer.
        if (!enabled || isImageModel()) return null
        val window = contextWindow()
        if (window <= 0) return null
        if (!shouldAutoCompress(true, window, triggerPercent, requestTokens)) return null
        val covered = if (source.lastOrNull()?.fromUser == true) source.dropLast(1) else source
        return planCompression(covered)
    }
}
