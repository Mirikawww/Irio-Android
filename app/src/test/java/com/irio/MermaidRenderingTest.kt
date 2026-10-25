package com.irio

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidEngine
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.MermaidRenderContext
import com.swithun.cmpmermaid.core.MermaidTheme
import com.swithun.cmpmermaid.core.MermaidThemePreset
import com.swithun.cmpmermaid.core.SceneText
import com.swithun.cmpmermaid.core.TextMetricProvider
import com.swithun.cmpmermaid.core.TextMetrics
import com.swithun.cmpmermaid.core.TextMetricsRequest
import org.junit.Assert.*
import org.junit.Test

class MermaidRenderingTest {
    private val metrics = object : TextMetricProvider {
        override fun measure(request: TextMetricsRequest): TextMetrics {
            val lines = request.text.lines()
            return TextMetrics(lines.maxOf { it.length }.coerceAtLeast(1) * request.fontSize * 0.6f,
                lines.size * request.fontSize * 1.4f)
        }
    }

    @Test fun representativeDiagramsProduceNativeScenesInBothThemes() {
        val sources = listOf(
            "flowchart LR\n  A[开始] --> B{选择}\n  B -->|是| C[结束]",
            "sequenceDiagram\n  participant Alice\n  participant Bob\n  Alice->>Bob: 你好\n  Bob-->>Alice: Hello",
            "classDiagram\n  Animal <|-- Cat\n  Animal : +String name\n  Cat : +meow()",
            "stateDiagram-v2\n  [*] --> Ready\n  Ready --> Done\n  Done --> [*]",
            "erDiagram\n  CUSTOMER ||--o{ ORDER : places\n  CUSTOMER {\n    string name\n  }",
            "pie title Budget\n  \"Food\" : 40\n  \"Rent\" : 60",
            "gantt\n  title Plan\n  dateFormat YYYY-MM-DD\n  section Work\n  Build :a1, 2026-10-01, 3d",
            "mindmap\n  root((计划))\n    设计\n    测试",
        )
        for (theme in listOf(MermaidThemePreset.Default, MermaidThemePreset.Dark)) {
            for (source in sources) {
                val result = MermaidEngine().render(source, MermaidRenderContext(metrics, MermaidTheme.preset(theme)))
                assertTrue("${source.lineSequence().first()} ($theme): $result", result is GMResult.Ok)
                val scene = (result as GMResult.Ok).value
                assertTrue(scene.width.isFinite() && scene.width > 0f)
                assertTrue(scene.height.isFinite() && scene.height > 0f)
                assertTrue(scene.elements.isNotEmpty())
            }
        }
    }

    @Test fun cjkLabelsSurviveParsingAndLayout() {
        val result = MermaidEngine().render("flowchart LR\n  A[开始] --> B[结束]", MermaidRenderContext(metrics)) as GMResult.Ok
        val labels = result.value.elements.filterIsInstance<SceneText>().map { it.text }
        assertTrue(labels.contains("开始"))
        assertTrue(labels.contains("结束"))
    }

    @Test fun unsupportedLayoutsAndMalformedInputReturnErrorsRatherThanBlankSuccess() {
        val unsupported = MermaidEngine().render("flowchart-elk LR\n  A --> B", MermaidRenderContext(metrics))
        assertTrue(unsupported is GMResult.Err)
        assertTrue((unsupported as GMResult.Err).error is MermaidError.UnsupportedFeature)
        val malformed = MermaidEngine().render("flowchart LR\n  A[unfinished", MermaidRenderContext(metrics))
        assertTrue(malformed is GMResult.Err)
    }
}
