package com.irio

import androidx.compose.foundation.text.contextmenu.builder.item
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuItem
import androidx.compose.foundation.text.contextmenu.modifier.appendTextContextMenuComponents
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.selection.rememberSelectionState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier

private data object QuoteMenuKey

internal fun quoteMenuItem(
    label: String,
    selectedText: () -> String,
    onQuote: (String) -> Unit,
): TextContextMenuItem? {
    if (selectedText().isBlank()) return null
    return TextContextMenuItem(key = QuoteMenuKey, label = label) {
        // 手柄调整后重新读选区，不使用打开菜单时的快照，也不借用剪贴板。
        val excerpt = selectedText()
        close()
        if (excerpt.isNotBlank()) onQuote(excerpt)
    }
}

@Composable
internal fun QuoteSelectionContainer(
    onQuote: (String) -> Unit,
    content: @Composable () -> Unit,
) {
    val selection = rememberSelectionState()
    val currentOnQuote = rememberUpdatedState(onQuote)
    val label = tr("引用")
    SelectionContainer(
        state = selection,
        modifier = Modifier.appendTextContextMenuComponents {
            quoteMenuItem(
                label = label,
                selectedText = { selection.selectedTexts.joinToString("\n") { it.text } },
                onQuote = { excerpt ->
                    selection.clear()
                    currentOnQuote.value(excerpt)
                },
            )?.let { quote ->
                item(key = quote.key, label = quote.label, onClick = quote.onClick)
            }
        },
        content = content,
    )
}
