package com.netment.hermespocket.ui

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * P2: Mermaid 图表渲染。
 * 检测 markdown 中的 ```mermaid 代码块，用 WebView 渲染。
 */
object MermaidRenderer {

    const val WRAPPER_HTML = """
<!DOCTYPE html><html><head>
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<script src="https://cdn.jsdelivr.net/npm/mermaid@10/dist/mermaid.min.js"></script>
<script>mermaid.initialize({startOnLoad:true,theme:'dark',securityLevel:'loose'});</script>
<style>body{margin:16px;background:#0F172A;}svg{max-width:100%;}</style>
</head><body><pre class="mermaid">
%s
</pre></body></html>
"""

    /**
     * 从 markdown 文本中提取 mermaid 代码块，返回 (before, mermaidCode, after) 三段式列表。
     */
    fun parseBlocks(text: String): List<MarkdownBlock> {
        val blocks = mutableListOf<MarkdownBlock>()
        val regex = Regex("```mermaid\\s*\\n([\\s\\S]*?)```")
        var lastEnd = 0
        regex.findAll(text).forEach { match ->
            if (match.range.first > lastEnd) {
                blocks.add(MarkdownBlock.MdText(text.substring(lastEnd, match.range.first)))
            }
            blocks.add(MarkdownBlock.MermaidCode(match.groupValues[1].trim()))
            lastEnd = match.range.last + 1
        }
        if (lastEnd < text.length) {
            blocks.add(MarkdownBlock.MdText(text.substring(lastEnd)))
        }
        return blocks.ifEmpty { listOf(MarkdownBlock.MdText(text)) }
    }
}

sealed class MarkdownBlock {
    data class MdText(val text: String) : MarkdownBlock()
    data class MermaidCode(val code: String) : MarkdownBlock()
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MermaidWebView(code: String) {
    var webHeight by remember { mutableIntStateOf(200) }
    val density = LocalDensity.current

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = Color(0xFF0F172A),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
    ) {
        Column {
            Text("📊 图表", color = Color(0xFF60A5FA), fontSize = 11.sp,
                modifier = Modifier.padding(start = 12.dp, top = 8.dp))
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        )
                        settings.javaScriptEnabled = true
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                view?.evaluateJavascript("document.body.scrollHeight") { result ->
                                    result?.toIntOrNull()?.let { h ->
                                        webHeight = with(density) { h.dp.roundToPx() } + 16
                                    }
                                }
                            }
                        }
                        setBackgroundColor(0xFF0F172A.toInt())
                        loadDataWithBaseURL(null, MermaidRenderer.WRAPPER_HTML.format(code), "text/html", "UTF-8", null)
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(with(density) { webHeight.toDp() })
            )
        }
    }
}
