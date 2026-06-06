package com.netment.hermespocket.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.netment.hermespocket.network.HermesWebSocket

@Composable
fun ClarifyCard(
    prompt: HermesWebSocket.ClarifyPrompt,
    status: ClarifyStatus,
    selectedChoice: String? = null,
    onChoose: ((String) -> Unit)?
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = Color(0xFF1E293B),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .border(1.dp, if (status == ClarifyStatus.PENDING) Color(0xFF60A5FA) else Color(0xFF334155), RoundedCornerShape(14.dp))
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (status == ClarifyStatus.PENDING) "❓ 需要确认"
                    else "✅ 已选择：${selectedChoice ?: "已回复"}",
                    color = if (status == ClarifyStatus.PENDING) Color(0xFF60A5FA) else Color(0xFF4ADE80),
                    fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(6.dp))
            Text(prompt.question, color = Color.White, fontSize = 14.sp)
            Spacer(Modifier.height(10.dp))

            if (prompt.choices.isNotEmpty()) {
                prompt.choices.forEach { choice ->
                    val isSelected = selectedChoice == choice
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = when {
                            isSelected -> Color(0xFF2563EB).copy(alpha = 0.3f)
                            status == ClarifyStatus.PENDING -> Color(0xFF374151)
                            else -> Color(0xFF1E293B)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                            .border(
                                1.dp,
                                if (isSelected) Color(0xFF4ADE80) else Color.Transparent,
                                RoundedCornerShape(8.dp)
                            )
                            .then(
                                if (status == ClarifyStatus.PENDING && onChoose != null)
                                    Modifier.clickable { onChoose(choice) }
                                else Modifier
                            )
                    ) {
                        Row(
                            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (isSelected) {
                                Text("✓ ", color = Color(0xFF4ADE80), fontSize = 13.sp)
                            }
                            Text(choice,
                                color = if (isSelected) Color(0xFF4ADE80) else Color(0xFF93C5FD),
                                fontSize = 13.sp)
                        }
                    }
                }
                if (status == ClarifyStatus.PENDING) {
                    Text("或直接输入自定义回答", color = Color(0xFF64748B), fontSize = 11.sp,
                        modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
    }
}
