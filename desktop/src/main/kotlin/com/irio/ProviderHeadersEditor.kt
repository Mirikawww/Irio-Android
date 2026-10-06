package com.irio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.composables.ui.components.Button
import com.composables.ui.components.ButtonStyle
import com.composables.ui.components.Text
import com.composables.ui.theme.colors
import com.composables.ui.theme.destructiveColor
import com.composables.ui.theme.onBackgroundColor
import com.composeunstyled.theme.Theme

internal class ProviderHeaderFields(header: ProviderHeader) {
    val name = TextFieldState(header.name)
    val value = TextFieldState(header.value)
}

internal class ProviderHeadersState(initialHeaders: List<ProviderHeader>) {
    val rows = mutableStateListOf<ProviderHeaderFields>().apply {
        addAll(initialHeaders.map(::ProviderHeaderFields))
    }
    var validationRequested by mutableStateOf(false)
        private set

    fun headers(): List<ProviderHeader> = rows.map {
        ProviderHeader(name = it.name.text.toString(), value = it.value.text.toString())
    }

    val validationError: String?
        get() = if (validationRequested) validateProviderHeaders(headers()) else null

    fun validatedHeaders(): List<ProviderHeader>? {
        validationRequested = true
        val headers = headers()
        return headers.takeIf { validateProviderHeaders(it) == null }
    }

    fun reset() {
        rows.clear()
        validationRequested = false
    }

    companion object {
        val Saver = listSaver<ProviderHeadersState, String>(
            save = { state -> state.headers().flatMap { listOf(it.name, it.value) } },
            restore = { values ->
                ProviderHeadersState(values.chunked(2).map { ProviderHeader(it[0], it[1]) })
            },
        )
    }
}

@Composable
internal fun rememberProviderHeadersState(
    initialHeaders: List<ProviderHeader> = emptyList(),
): ProviderHeadersState = rememberSaveable(saver = ProviderHeadersState.Saver) {
    ProviderHeadersState(initialHeaders)
}

@Composable
internal fun ProviderHeadersEditor(state: ProviderHeadersState, enabled: Boolean = true) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(tr("自定义请求头"), color = Theme[colors][onBackgroundColor])
        state.rows.forEach { row ->
            key(row) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ProviderField(
                        tr("请求头名称"), row.name, KeyboardType.Ascii,
                        readOnly = !enabled, trailingSpace = false,
                    )
                    ProviderField(
                        tr("请求头值"), row.value, KeyboardType.Password,
                        mask = true, readOnly = !enabled, trailingSpace = false,
                    )
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Button(
                            onClick = { state.rows.remove(row) },
                            enabled = enabled,
                            style = ButtonStyle.Destructive,
                        ) { Text(tr("删除请求头")) }
                    }
                }
            }
        }
        state.validationError?.let { error ->
            Text(tr(error), color = Theme[colors][destructiveColor])
        }
        Button(
            onClick = { state.rows.add(ProviderHeaderFields(ProviderHeader("", ""))) },
            enabled = enabled,
            style = ButtonStyle.Secondary,
        ) { Text(tr("添加请求头")) }
    }
}
