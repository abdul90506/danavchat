package com.danav.chat.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.danav.chat.ui.theme.LocalChatColors
import kotlin.math.PI
import kotlin.math.sin

/** A subtle, locally drawn chat doodle wallpaper; no network assets or bitmap required. */
@Composable
fun ChatWallpaper(modifier: Modifier = Modifier) {
    val tint = LocalChatColors.current.pattern
    Canvas(modifier.fillMaxSize()) {
        val unit = 76.dp.toPx()
        val stroke = 1.15.dp.toPx()
        val rows = (size.height / unit).toInt() + 2
        val cols = (size.width / unit).toInt() + 2
        for (r in -1..rows) for (c in -1..cols) {
            val x = c * unit + if (r % 2 == 0) 0f else unit * .47f
            val y = r * unit
            when ((r * 7 + c * 11).mod(8)) {
                0 -> drawCircle(tint, radius = unit * .16f, center = Offset(x + unit * .47f, y + unit * .48f), style = Stroke(stroke))
                1 -> {
                    drawRoundRect(tint, topLeft = Offset(x + unit*.22f,y + unit*.28f), size = Size(unit*.52f,unit*.34f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(unit*.1f), style = Stroke(stroke))
                    drawLine(tint,Offset(x+unit*.33f,y+unit*.38f),Offset(x+unit*.62f,y+unit*.38f),stroke)
                    drawLine(tint,Offset(x+unit*.33f,y+unit*.48f),Offset(x+unit*.55f,y+unit*.48f),stroke)
                }
                2 -> {
                    val pts = listOf(0.5f to .19f, .56f to .42f, .78f to .5f, .56f to .57f, .5f to .81f, .44f to .57f, .22f to .5f, .44f to .42f)
                    for (i in pts.indices) { val a=pts[i];val b=pts[(i+1)%pts.size];drawLine(tint,Offset(x+unit*a.first,y+unit*a.second),Offset(x+unit*b.first,y+unit*b.second),stroke,cap=StrokeCap.Round) }
                }
                3 -> {
                    val wiggle = sin((r+c).toDouble()*PI/3).toFloat()*unit*.035f
                    drawRoundRect(tint, topLeft=Offset(x+unit*.26f,y+unit*.25f+wiggle),size=Size(unit*.46f,unit*.48f),cornerRadius=androidx.compose.ui.geometry.CornerRadius(unit*.18f),style=Stroke(stroke))
                    drawCircle(tint,unit*.035f,Offset(x+unit*.5f,y+unit*.44f))
                }
                4 -> {
                    drawArc(tint, 210f, 300f, false, Offset(x+unit*.25f,y+unit*.25f), Size(unit*.5f,unit*.5f), style=Stroke(stroke,cap=StrokeCap.Round))
                    drawCircle(tint,unit*.035f,Offset(x+unit*.58f,y+unit*.49f))
                }
                5 -> {
                    drawRoundRect(tint,topLeft=Offset(x+unit*.24f,y+unit*.3f),size=Size(unit*.5f,unit*.33f),cornerRadius=androidx.compose.ui.geometry.CornerRadius(unit*.08f),style=Stroke(stroke))
                    drawLine(tint,Offset(x+unit*.36f,y+unit*.63f),Offset(x+unit*.47f,y+unit*.72f),stroke)
                }
                6 -> {
                    drawCircle(tint,unit*.19f,Offset(x+unit*.5f,y+unit*.5f),style=Stroke(stroke))
                    drawCircle(tint,unit*.035f,Offset(x+unit*.43f,y+unit*.47f));drawCircle(tint,unit*.035f,Offset(x+unit*.57f,y+unit*.47f))
                    drawArc(tint,20f,140f,false,Offset(x+unit*.42f,y+unit*.45f),Size(unit*.16f,unit*.12f),style=Stroke(stroke))
                }
                else -> {
                    drawLine(tint,Offset(x+unit*.28f,y+unit*.3f),Offset(x+unit*.72f,y+unit*.7f),stroke)
                    drawLine(tint,Offset(x+unit*.72f,y+unit*.3f),Offset(x+unit*.28f,y+unit*.7f),stroke)
                }
            }
        }
    }
}
