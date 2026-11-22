package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.EmeraldActive
import com.example.ui.theme.InterFamily
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.SpaceGroteskFamily

private data class ScriptBoardEntry(
    val tag: String,
    val accent: Color,
    val headline: String,
    val detail: String,
    val isDarkCard: Boolean = true
)

private val MultiScriptSamples = listOf(
    ScriptBoardEntry(
        tag = "JA · 日本語",
        accent = Color(0xFF38BDF8),
        headline = "東京駅 · 新幹線ホーム",
        detail = "本日の特急電車は定刻通り 14:20 に発車します"
    ),
    ScriptBoardEntry(
        tag = "ZH · 中文",
        accent = Color(0xFF34D399),
        headline = "北京首都国际机场 · 登机口 B12",
        detail = "请旅客提前二十分钟前往安检通道准备登机"
    ),
    ScriptBoardEntry(
        tag = "KO · 한국어",
        accent = Color(0xFFA78BFA),
        headline = "서울역 · 공항철도 직통열차",
        detail = "인천국제공항 제1여객터미널행 열차가 곧 도착합니다"
    ),
    ScriptBoardEntry(
        tag = "AR · العربية",
        accent = Color(0xFFFBBF24),
        headline = "مطار دبي الدولي · بوابة المغادرة",
        detail = "يرجى التوجه إلى البوابة رقم ٤ قبل موعد الإقلاع",
        isDarkCard = false
    ),
    ScriptBoardEntry(
        tag = "RU · Кириллица",
        accent = Color(0xFFF472B6),
        headline = "Московский вокзал · Платформа 4",
        detail = "Скоростной поезд отправляется через десять минут"
    ),
    ScriptBoardEntry(
        tag = "HI · देवनागरी",
        accent = Color(0xFFFB923C),
        headline = "नई दिल्ली रेलवे स्टेशन · गेट 7",
        detail = "एक्सप्रेस ट्रेन सेवा आज सामान्य समय पर चल रही है"
    ),
    ScriptBoardEntry(
        tag = "ES / FR · Latin",
        accent = ElectricCyan,
        headline = "Menú del día · Café con leche 4,20 €",
        detail = "Sortie Est · Embarquement immédiat porte B14",
        isDarkCard = false
    )
)

@Composable
fun MultiScriptTestBoard(
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF101218))
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Clean Header + Geometric Lens Test Emblem
        Surface(
            color = Color(0xFF171A24),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "World Transit Departure Info",
                        fontFamily = SpaceGroteskFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp,
                        color = Color.White
                    )
                    Text(
                        text = "Tap any word, circle the camera, or switch to Translate",
                        fontFamily = InterFamily,
                        fontSize = 12.sp,
                        color = Color(0xFF9CA3AF)
                    )
                }

                // Clean geometric camera object for Google Lens cutout testing
                Surface(
                    color = Color(0xFF0E1016),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.size(52.dp)
                ) {
                    Canvas(modifier = Modifier.fillMaxSize().padding(10.dp)) {
                        val w = size.width
                        val h = size.height
                        drawRoundRect(
                            color = Color.White,
                            topLeft = Offset(0f, h * 0.22f),
                            size = Size(w, h * 0.68f),
                            cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx()),
                            style = Stroke(width = 2.dp.toPx())
                        )
                        drawRoundRect(
                            color = Color.White,
                            topLeft = Offset(w * 0.30f, h * 0.08f),
                            size = Size(w * 0.40f, h * 0.14f),
                            cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx())
                        )
                        drawCircle(
                            color = ElectricCyan,
                            radius = minOf(w, h) * 0.20f,
                            center = Offset(w * 0.5f, h * 0.56f),
                            style = Stroke(width = 2.dp.toPx())
                        )
                        drawCircle(
                            color = EmeraldActive,
                            radius = 2.5.dp.toPx(),
                            center = Offset(w * 0.80f, h * 0.36f)
                        )
                    }
                }
            }
        }

        MultiScriptSamples.forEach { sample ->
            val bg = if (sample.isDarkCard) Color(0xFF171A24) else Color(0xFFF3F5F8)
            val titleCol = if (sample.isDarkCard) Color.White else Color(0xFF0F172A)
            val bodyCol = if (sample.isDarkCard) Color(0xFFCBD5E1) else Color(0xFF334155)
            val badgeBg = if (sample.isDarkCard) sample.accent.copy(alpha = 0.16f) else Color(0xFF0F172A)
            val badgeTextCol = if (sample.isDarkCard) sample.accent else Color.White

            Surface(
                color = bg,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Surface(
                        color = badgeBg,
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = sample.tag,
                            fontFamily = FontFamily.SansSerif,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 10.5.sp,
                            color = badgeTextCol,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                        )
                    }
                    Text(
                        text = sample.headline,
                        fontFamily = FontFamily.SansSerif,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.5.sp,
                        lineHeight = 21.sp,
                        color = titleCol
                    )
                    Text(
                        text = sample.detail,
                        fontFamily = FontFamily.SansSerif,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        color = bodyCol
                    )
                }
            }
        }
    }
}

@Composable
fun CompactMultiScriptPreview(
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .background(Color(0xFF10131C))
            .padding(14.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                listOf("JA", "ZH", "KO", "AR", "RU", "HI", "EN").forEachIndexed { idx, code ->
                    val col = when (idx % 3) {
                        0 -> ElectricCyan
                        1 -> EmeraldActive
                        else -> Color(0xFFA78BFA)
                    }
                    Surface(
                        color = Color(0xFF1A1F2E),
                        shape = RoundedCornerShape(6.dp),
                        border = BorderStroke(1.dp, col.copy(alpha = 0.45f))
                    ) {
                        Text(
                            text = code,
                            fontFamily = JetBrainsMonoFamily,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = col,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    color = Color(0xFF171B26),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, Color(0xFF282E40)),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = "東京駅 · 新幹線\n北京 · 서울역",
                        fontFamily = FontFamily.SansSerif,
                        fontSize = 11.5.sp,
                        lineHeight = 16.sp,
                        color = Color(0xFFE2E8F0),
                        modifier = Modifier.padding(8.dp)
                    )
                }
                Surface(
                    color = Color(0xFF171B26),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, Color(0xFF282E40)),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = "مطار دبي · بوابة ٤\nМосква · नई दिल्ली",
                        fontFamily = FontFamily.SansSerif,
                        fontSize = 11.5.sp,
                        lineHeight = 16.sp,
                        color = Color(0xFFE2E8F0),
                        modifier = Modifier.padding(8.dp)
                    )
                }
            }
        }

        // Subtle circle highlight badge in top right
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(34.dp)
                .clip(CircleShape)
                .background(Color(0x33FFFFFF)),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.size(22.dp)) {
                drawCircle(
                    color = Color.White,
                    style = Stroke(width = 2.dp.toPx())
                )
            }
        }
    }
}
