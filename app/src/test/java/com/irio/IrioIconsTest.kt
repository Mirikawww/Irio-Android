package com.irio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IrioIconsTest {
    @Test
    fun everyUsedLucideVectorCanBeParsed() {
        // Construct all lazy vectors: malformed SVG path syntax must not crash when a screen opens.
        val icons = listOf(
            IrioIcons.ArrowLeft, IrioIcons.ChevronLeft, IrioIcons.ChevronRight,
            IrioIcons.ChevronDown, IrioIcons.Volume2, IrioIcons.Plus,
            IrioIcons.FaceSlightlySmilingPlus, IrioIcons.ArrowUp, IrioIcons.Paperclip,
            IrioIcons.Sparkles, IrioIcons.Zap, IrioIcons.Check, IrioIcons.X,
            IrioIcons.Minimize2, IrioIcons.Monitor, IrioIcons.Copy, IrioIcons.Braces,
            IrioIcons.Trash, IrioIcons.MonitorSmartphone, IrioIcons.Download,
            IrioIcons.Pencil, IrioIcons.Quote, IrioIcons.FastForward, IrioIcons.Cpu,
            IrioIcons.Menu, IrioIcons.Mic, IrioIcons.Ellipsis, IrioIcons.Maximize2,
            IrioIcons.Palette, IrioIcons.Pause, IrioIcons.Smartphone, IrioIcons.Image,
            IrioIcons.Play, IrioIcons.Pin, IrioIcons.Speech, IrioIcons.RefreshCw,
            IrioIcons.Rewind, IrioIcons.Search, IrioIcons.Settings, IrioIcons.Bot,
            IrioIcons.Square, IrioIcons.SquareCheck, IrioIcons.Terminal, IrioIcons.Upload,
        )
        assertEquals(44, icons.size)
        assertEquals(icons.size, icons.map { it.name }.toSet().size)
        assertTrue(icons.all { it.name.startsWith("lucide:") })
        assertTrue(IrioIcons.ArrowLeft.autoMirror)
        assertTrue(IrioIcons.ChevronLeft.autoMirror)
        assertTrue(IrioIcons.ChevronRight.autoMirror)
    }
}
