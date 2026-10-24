package com.irio

/** Both direct image models and generate_image must forward the user's actual image attachments. */
internal fun imageGenerationContext(history: List<ChatRequestMessage>): ProviderApi.ImageGenContext? {
    val lastUserIndex = history.indexOfLast { it.role == "user" }
    if (lastUserIndex < 0) return null
    val referenceImages = history[lastUserIndex].attachments.filter(ChatAttachment::isImage)
        .distinctBy { it.filePath }
    val previous = history.take(lastUserIndex)
    val previousUser = previous.lastOrNull { it.role == "user" }
    val previousImages = if (previousUser == null) emptyList() else previous
        .filter { it.role == "assistant" }
        .flatMap { it.attachments.filter(ChatAttachment::isImage) }
        .takeLast(3)
    if (referenceImages.isEmpty() && previousImages.isEmpty()) return null
    return ProviderApi.ImageGenContext(previousUser?.text.orEmpty(), previousImages, referenceImages)
}

internal fun chatImageGenerationContext(messages: List<ChatMessage>): ProviderApi.ImageGenContext? =
    imageGenerationContext(messages.filter {
        !it.hidden && it.summaryState == SummaryState.NONE && !it.isError && !it.isPending
    }.map {
        ChatRequestMessage(
            role = if (it.fromUser) "user" else "assistant",
            text = it.requestText(),
            attachments = it.attachments,
        )
    })
