package com.panzhikun.metaldogshower

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

private val GuideNavy = Color(0xFF111638)
private val GuideOrange = Color(0xFFE96B3A)
private val GuideIvory = Color(0xFFF7F1E3)

private data class GuidePage(
    val title: String,
    val subtitle: String,
    val imageRes: Int,
    val imageDescription: String,
    val steps: List<String>,
)

@Composable
internal fun OnboardingFlow(onFinish: () -> Unit) {
    val guideTextColor = MaterialTheme.colorScheme.onBackground
    val guideMutedColor = MaterialTheme.colorScheme.onSurfaceVariant
    val pages = remember {
        listOf(
            GuidePage(
                title = "先录入两间浴室",
                subtitle = "每间浴室都要单独扫描墙上的二维码，避免控制错房间。",
                imageRes = R.drawable.guide_step_scan,
                imageDescription = "扫描二维码并分别保存为浴室一和浴室二的示意图",
                steps = listOf(
                    "在首页选择“浴室 1”，点击“扫码录入”。",
                    "竖屏对准浴室 1 墙上的二维码，再点击“识别并保存”。",
                    "切换到“浴室 2”，重复扫描和保存。",
                ),
            ),
            GuidePage(
                title = "短信登录并保留会话",
                subtitle = "两间浴室确认无误后，再使用官方短信验证码登录。",
                imageRes = R.drawable.guide_step_login,
                imageDescription = "输入手机号和六位验证码完成登录的示意图",
                steps = listOf(
                    "输入 11 位手机号，点击“发送验证码”。",
                    "填写收到的 6 位验证码，点击“登录并安全保存”。",
                    "以后正常打开应用会继续使用已保存的登录状态。",
                ),
            ),
            GuidePage(
                title = "同步手表并添加小组件",
                subtitle = "手机、Wear OS 手表和桌面小组件可以共享同一份浴室配置。",
                imageRes = R.drawable.guide_step_sync,
                imageDescription = "手机同步手表并添加桌面小组件的示意图",
                steps = listOf(
                    "确认手机与手表已连接，然后打开手机端“设置”。",
                    "点击“安全同步给手表”，等待手表确认保存。",
                    "长按手机桌面，添加“金属狗淋浴”小组件。",
                ),
            ),
            GuidePage(
                title = "刷新状态与设置定时器",
                subtitle = "后台计划只查询状态并更新小组件，绝不会自动开关浴室。",
                imageRes = R.drawable.guide_step_timer,
                imageDescription = "设置每日或单次后台定时刷新的示意图",
                steps = listOf(
                    "进入“设置 → 状态轮询与定时器”。",
                    "选择“每天”或“仅一次”，再选择开始、结束时间。",
                    "选择每隔几分钟刷新；系统省电时可能稍有延后。",
                    "真正开关浴室前，仍需明确选择房间并手动确认。",
                ),
            ),
        )
    }
    var pageIndex by rememberSaveable { mutableIntStateOf(0) }
    val page = pages[pageIndex]
    val scrollState = rememberScrollState()

    LaunchedEffect(pageIndex) { scrollState.scrollTo(0) }
    BackHandler {
        if (pageIndex > 0) pageIndex--
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .navigationBarsPadding(),
    ) {
        Surface(color = GuideNavy) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 15.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(color = Color(0xFF272D55), shape = CircleShape) {
                        Image(
                            painter = painterResource(R.drawable.metaldog_brand_mark),
                            contentDescription = null,
                            modifier = Modifier.size(48.dp).padding(5.dp),
                        )
                    }
                    Spacer(Modifier.size(11.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "金属狗淋浴 · 新手指引",
                            color = GuideIvory,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            "第 ${pageIndex + 1} 步，共 ${pages.size} 步",
                            color = Color(0xFFC8CBE8),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (pageIndex < pages.lastIndex) {
                        TextButton(onClick = onFinish) { Text("跳过", color = GuideIvory) }
                    }
                }
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { (pageIndex + 1f) / pages.size },
                    modifier = Modifier.fillMaxWidth(),
                    color = GuideOrange,
                    trackColor = Color(0xFF343A66),
                )
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(scrollState)
                .padding(horizontal = 20.dp, vertical = 18.dp),
        ) {
            Text(
                page.title,
                color = guideTextColor,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Black,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                page.subtitle,
                color = guideMutedColor,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(16.dp))
            Surface(
                color = GuideNavy,
                shape = RoundedCornerShape(22.dp),
                shadowElevation = 2.dp,
            ) {
                Image(
                    painter = painterResource(page.imageRes),
                    contentDescription = page.imageDescription,
                    modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                )
            }
            Spacer(Modifier.height(18.dp))
            page.steps.forEachIndexed { index, step ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .background(GuideOrange, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "${index + 1}",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                    Spacer(Modifier.size(11.dp))
                    Text(
                        step,
                        modifier = Modifier.weight(1f),
                        color = guideTextColor,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (pageIndex > 0) {
                OutlinedButton(
                    onClick = { pageIndex-- },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = guideTextColor),
                ) {
                    Text("上一步")
                }
            } else {
                Spacer(Modifier.weight(1f))
            }
            Button(
                onClick = {
                    if (pageIndex == pages.lastIndex) onFinish() else pageIndex++
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = GuideOrange,
                    contentColor = Color.White,
                ),
            ) {
                Text(
                    if (pageIndex == pages.lastIndex) "开始使用" else "下一步",
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
