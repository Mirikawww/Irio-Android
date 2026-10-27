package com.irio

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.composables.ui.components.Button
import com.composables.ui.components.ButtonSize
import com.composables.ui.components.ButtonStyle
import com.composables.ui.components.DropdownMenu
import com.composables.ui.components.DropdownMenuAlignment
import com.composables.ui.components.DropdownMenuItem
import com.irio.ScaledDropdownMenuPanel as DropdownMenuPanel
import com.composables.ui.components.Icon
import com.composables.ui.components.IconButton
import com.composables.ui.components.Text
import com.composables.ui.theme.backgroundColor
import com.composables.ui.theme.colors
import com.composables.ui.theme.mutedColor
import com.composables.ui.theme.onBackgroundColor
import com.composables.ui.theme.onPrimaryColor
import com.composeunstyled.theme.Theme
import com.composeunstyled.LocalContentColor
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.blur.hazeBlur
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@Composable
internal fun ChatConversationDrawer(state: ChatScreenState, drawerOpenProgress: Float) =
    with(state) {
        val density = LocalDensity.current
        val background = Theme[colors][backgroundColor]
        val content = Theme[colors][onBackgroundColor]
        val muted = Theme[colors][mutedColor]
        val pageOffset = drawerWidth * drawerOpenProgress
        val drawerWidthPx = with(density) { drawerWidth.toPx() }
        val drawerShadowPx = with(density) { 8.dp.toPx() }
        val drawerProgressState = rememberUpdatedState(drawerProgress)
        val groupedConversationRows =
            remember(conversations, ThemeStore.language) { groupedConversations(conversations) }
        val conversationDrawerRows =
            remember(groupedConversationRows) {
                buildList {
                    groupedConversationRows.forEach { (label, items) ->
                        add(ConversationDrawerRow.Header("header-$label", label))
                        items.forEach { conversation ->
                            add(ConversationDrawerRow.Conversation(conversation))
                        }
                    }
                }
            }

        Column(
            modifier =
                Modifier.fillMaxHeight()
                    .width(drawerWidth)
                    .offset(x = pageOffset - drawerWidth)
                    .graphicsLayer {
                        shadowElevation = drawerShadowPx * drawerOpenProgress
                        clip = false
                    }
                    .zIndex(1f)
                    .background(background)
                    .absorbDrawerBackgroundTaps()
                    .statusBarsPadding()
                    .drawerSwipe(
                        drawerWidthPx = drawerWidthPx,
                        openDirection = false,
                        allowTapToClose = false,
                        progress = { drawerProgressState.value },
                        onStart = {
                            drawerDragging = true
                            drawerStartProgress = drawerProgressState.value
                        },
                        onDrag = { drawerProgress = it },
                        onSettle = { settleDrawer(drawerProgressState.value) },
                        onTapClose = {},
                    )
                    .padding(start = 4.dp, top = 8.dp, end = 12.dp)
        ) {
            Box(modifier = Modifier.weight(1f).clipToBounds()) {
                AndroidComposeRecyclerList(
                    items = conversationDrawerRows,
                    key = { it.key },
                    modifier = Modifier.fillMaxSize().clipToBounds(),
                    contentPadding = PaddingValues(bottom = 112.dp),
                    itemSpacing = 2.dp,
                ) {
                    when (val row = it) {
                        is ConversationDrawerRow.Header -> {
                            Text(
                                row.text,
                                modifier =
                                    Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
                                color = muted,
                                fontSize = 12.sp,
                            )
                        }
                        is ConversationDrawerRow.Conversation -> {
                            val conversation = row.summary
                            val generating = conversation.id in generatingIds
                            DropdownMenu(
                                expanded = conversationAction?.id == conversation.id,
                                onExpandedChange = { expanded ->
                                    conversationAction = if (expanded) conversation else null
                                },
                                modifier = Modifier.fillMaxWidth(),
                                alignment = DropdownMenuAlignment.Start,
                                panel = {
                                    DropdownMenuPanel(modifier = Modifier.widthIn(min = 140.dp)) {
                                        DropdownMenuItem(
                                            onClick = {
                                                togglePinned(conversation)
                                                conversationAction = null
                                            }
                                        ) {
                                            Text(if (conversation.pinned) tr("取消置顶") else tr("置顶"), color = LocalContentColor.current)
                                        }
                                        DropdownMenuItem(
                                            onClick = {
                                                regenerateConversationTitle(conversation.id)
                                                conversationAction = null
                                            }
                                        ) {
                                            Text(
                                                tr(
                                                    if (titleGeneration.isRunning(conversation.id))
                                                        "正在生成标题"
                                                    else "重新生成标题"
                                                ),
                                                color =
                                                    if (titleGeneration.isRunning(conversation.id))
                                                        muted
                                                    else content,
                                            )
                                        }
                                        DropdownMenuItem(
                                            onClick = {
                                                deleteConversation(conversation)
                                                conversationAction = null
                                            }
                                        ) {
                                            Text(tr("删除对话"), color = LocalContentColor.current)
                                        }
                                    }
                                },
                            ) {
                                ListRowButton(
                                    label = displayConversationTitle(conversation.title),
                                    style = ButtonStyle.Ghost,
                                    contentPadding =
                                        if (generating) {
                                            PaddingValues(start = 16.dp, end = 56.dp)
                                        } else DrawerRowPadding,
                                    icon = if (conversation.pinned) IrioIcons.Pin else null,
                                    active = conversation.id == currentConversation.id,
                                    modifier = Modifier.generatingRipple(generating),
                                    onClick = { openConversation(conversation) },
                                    onLongClick = { conversationAction = conversation },
                                )
                            }
                        }
                    }
                }
                Box(
                    modifier =
                        Modifier.align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .zIndex(1f)
                            .hazeBlur(
                                input = HazeInput.Sources(chatHazeState),
                                style = chatComposerHazeStyle(),
                            )
                            .absorbDrawerBackgroundTaps()
                            .padding(horizontal = 8.dp)
                ) {
                    Column(Modifier.fillMaxWidth()) {
                        Spacer(Modifier.height(ChatComposerHazeBleed))
                        Row(
                            modifier =
                                Modifier.fillMaxWidth()
                                    .windowInsetsPadding(
                                        WindowInsets.navigationBars
                                            .only(WindowInsetsSides.Bottom)
                                            .exclude(WindowInsets.ime)
                                    )
                                    .padding(bottom = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            ToolbarCapsule(
                                modifier = Modifier.size(48.dp),
                                glass = GlassSurface.SIDEBAR_BUTTON in ThemeStore.glassSurfaces,
                            ) {
                                IconButton(
                                    onClick = {
                                        context.startActivity(
                                            Intent(context, SettingsActivity::class.java)
                                        )
                                    },
                                    style = ButtonStyle.Ghost,
                                    buttonSize = ButtonSize.Large,
                                    shape = ToolbarCapsuleShape,
                                    modifier = Modifier.fillMaxSize(),
                                ) {
                                    Icon(
                                        imageVector = IrioIcons.Settings,
                                        contentDescription = tr("设置"),
                                        tint = content,
                                    )
                                }
                            }
                            Spacer(Modifier.weight(1f))
                            Button(
                                onClick = { newConversation() },
                                style = ButtonStyle.Primary,
                                buttonSize = ButtonSize.Large,
                                shape = ToolbarCapsuleShape,
                                contentColor = Theme[colors][onPrimaryColor],
                                contentPadding = PaddingValues(horizontal = 16.dp),
                            ) {
                                Icon(
                                    imageVector = IrioIcons.SquarePen,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                )
                                Text(tr("新对话"), color = LocalContentColor.current)
                            }
                        }
                    }
                }
            }
        }
    }

private sealed interface ConversationDrawerRow {
    val key: String

    data class Header(override val key: String, val text: String) : ConversationDrawerRow

    data class Conversation(val summary: ConversationSummary) : ConversationDrawerRow {
        override val key: String = summary.id
    }
}

private fun groupedConversations(
    conversations: List<ConversationSummary>
): List<Pair<String, List<ConversationSummary>>> {
    val pinned = conversations.filter { it.pinned }
    val rest = conversations.filterNot { it.pinned }
    return buildList {
        if (pinned.isNotEmpty()) add(tr("置顶") to pinned)
        addAll(rest.groupBy { conversationDateLabel(it.updatedAt) }.toList())
    }
}

private fun conversationDateLabel(updatedAt: Long): String {
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = updatedAt }
    val today = now.clone() as Calendar
    today.set(Calendar.HOUR_OF_DAY, 0)
    today.set(Calendar.MINUTE, 0)
    today.set(Calendar.SECOND, 0)
    today.set(Calendar.MILLISECOND, 0)
    val yesterday = (today.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
    return when {
        then.timeInMillis >= today.timeInMillis -> tr("今天")
        then.timeInMillis >= yesterday.timeInMillis -> tr("昨天")
        now.get(Calendar.YEAR) == then.get(Calendar.YEAR) ->
            if (ThemeStore.language == AppLanguage.EN)
                SimpleDateFormat("MMM d", Locale.ENGLISH).format(Date(updatedAt))
            else SimpleDateFormat("M月d日", Locale.CHINA).format(Date(updatedAt))
        else ->
            if (ThemeStore.language == AppLanguage.EN)
                SimpleDateFormat("MMMM d, yyyy", Locale.ENGLISH).format(Date(updatedAt))
            else SimpleDateFormat("yyyy年M月d日", Locale.CHINA).format(Date(updatedAt))
    }
}

private val DrawerRowPadding = PaddingValues(start = 16.dp, end = 12.dp)
