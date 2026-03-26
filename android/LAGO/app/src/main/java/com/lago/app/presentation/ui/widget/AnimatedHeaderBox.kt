package com.lago.app.presentation.ui.widget

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.lago.app.domain.entity.ChartStockInfo
import com.lago.app.presentation.theme.*

@Composable
private fun SkeletonPlaceholder(
    width: Dp,
    height: Dp,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .width(width)
            .height(height)
            .background(
                color = Gray200.copy(alpha = 0.3f), // 매우 연한 회색
                shape = RoundedCornerShape(4.dp)
            )
    )
}

@Composable
fun AnimatedHeaderBox(
    stockInfo: ChartStockInfo,
    headerAlignmentProgress: Float, // 0f to 1f (30% ~ 40% 범위에서 0f to 1f로 변환됨)
    contentOffsetY: Float,
    modifier: Modifier = Modifier
) {
    // 스켈레톤 상태 감지 (이름이 비어있으면 스켈레톤 표시 - 가격 0이어도 이름 있으면 표시)
    val isSkeletonMode = stockInfo.name.isEmpty()
    // 박스 애니메이션 값들 계산
    val boxPadding = 16f - (headerAlignmentProgress * 16f) // 16 -> 0
    val boxTranslationY = -headerAlignmentProgress * 32f // 0 -> -32 (앱바 아이콘과 같은 높이)
    val boxHeight = 120f - (headerAlignmentProgress * 64f) // 120 -> 56
    val boxAlpha = 0f // 항상 투명
    val boxCornerRadius = 12f - (headerAlignmentProgress * 12f) // 12 -> 0

    // 텍스트 애니메이션 값들
    val titleScale = 1f - (headerAlignmentProgress * 0.25f) // 1f -> 0.75f
    val priceScale = 1f - (headerAlignmentProgress * 0.4f) // 1f -> 0.6f
    val layoutTransition = headerAlignmentProgress // 레이아웃 전환 진행도
    
    // Theme Typography 사용 - 정의된 스타일 사용
    
    // 부드러운 전환을 위한 easing 함수
    val easeInOut = { progress: Float ->
        if (progress < 0.5f) {
            2f * progress * progress
        } else {
            1f - 2f * (1f - progress) * (1f - progress)
        }
    }
    val easedTransition = easeInOut(layoutTransition)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = boxPadding.dp)
            .offset(y = (72f + boxTranslationY + contentOffsetY).dp)
            .height(boxHeight.dp)
            .background(
                color = MaterialTheme.colorScheme.surface.copy(alpha = boxAlpha),
                shape = RoundedCornerShape(boxCornerRadius.dp)
            )
            .zIndex(2f)
    ) {
        // 박스 안의 콘텐츠 - 부드러운 fade in/out
        Box(
            modifier = Modifier.fillMaxSize()
        ) {
            // 초기 상태 (세로 배치) - 부드럽게 사라짐
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(Spacing.md)
                    .offset(y = (-20).dp) // 타이틀 위치 상향 조정
                    .alpha((1f - easedTransition).coerceAtLeast(0f))
                    .graphicsLayer {
                        translationY = -easedTransition * 10f // 부드러운 이동
                        scaleX = titleScale
                        scaleY = titleScale
                    },
                verticalArrangement = Arrangement.Center
            ) {
                if (isSkeletonMode) {
                    // 스켈레톤 모드: 종목명 플레이스홀더
                    SkeletonPlaceholder(
                        width = 120.dp,
                        height = 24.dp
                    )
                } else {
                    Text(
                        text = stockInfo.name,
                        style = SubtitleSb24,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Spacer(modifier = Modifier.height(Spacing.sm))

                // 가격과 수익률을 한 줄에 배치
                Row(
                    verticalAlignment = Alignment.Bottom
                ) {
                    if (isSkeletonMode) {
                        // 스켈레톤 모드: 가격 플레이스홀더
                        SkeletonPlaceholder(
                            width = 140.dp,
                            height = 32.dp
                        )
                        
                        Spacer(modifier = Modifier.width(Spacing.sm))
                        
                        // 수익률 플레이스홀더
                        SkeletonPlaceholder(
                            width = 80.dp,
                            height = 14.dp
                        )
                    } else {
                        Text(
                            text = "${String.format("%,.0f", stockInfo.currentPrice)}원",
                            style = HeadEb32,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        
                        Spacer(modifier = Modifier.width(Spacing.sm))
                        
                        // 🔥 웹소켓 previousDay 데이터 사용
                        val previousDayChange = stockInfo.previousDay ?: stockInfo.priceChange.toInt()
                        val isPositive = previousDayChange >= 0
                        val changeSign = if (isPositive) "+" else ""
                        
                        // 🔥 등락률에서 abs 제거하고 자연스러운 부호 표시
                        val priceChangePercent = stockInfo.priceChangePercent
                        val percentSign = if (priceChangePercent >= 0) "+" else ""
                        val changeText = "${changeSign}${String.format("%,d", previousDayChange)}(${percentSign}${String.format("%.2f", priceChangePercent)}%)"
                        
                        Text(
                            text = changeText,
                            style = SubtitleSb14,
                            color = if (isPositive) MainPink else MainBlue
                        )
                    }
                }
            }

            // 최종 상태 (앱바 안 세로 배치) - 뒤로가기 버튼 바로 오른쪽에 위치
            Column(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = Spacing.xxxl + Spacing.xs) // 뒤로가기 버튼 + 추가 여백
                    .alpha(easedTransition.coerceAtLeast(0f))
                    .graphicsLayer {
                        translationY = (1f - easedTransition) * 10f // 부드러운 이동
                    }
            ) {
                if (isSkeletonMode) {
                    // 스켈레톤 모드: 종목명 플레이스홀더 (작은 버전)
                    SkeletonPlaceholder(
                        width = 80.dp,
                        height = 16.dp
                    )
                } else {
                    // 주가 타이틀
                    Text(
                        text = stockInfo.name,
                        style = SubtitleSb16,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Spacer(modifier = Modifier.height(2.dp))

                // 금액과 수익률을 한 줄에
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isSkeletonMode) {
                        // 스켈레톤 모드: 가격 플레이스홀더 (작은 버전)
                        SkeletonPlaceholder(
                            width = 70.dp,
                            height = 14.dp
                        )
                        
                        Spacer(modifier = Modifier.width(Spacing.xs + 2.dp))
                        
                        // 수익률 플레이스홀더 (작은 버전)
                        SkeletonPlaceholder(
                            width = 45.dp,
                            height = 14.dp
                        )
                    } else {
                        Text(
                            text = "${String.format("%,.0f", stockInfo.currentPrice)}원",
                            style = BodyR14,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        Spacer(modifier = Modifier.width(Spacing.xs + 2.dp))

                        // 🔥 웹소켓 previousDay 데이터 사용
                        val previousDayChange = stockInfo.previousDay ?: stockInfo.priceChange.toInt()
                        val isPositive = previousDayChange >= 0
                        
                        // 🔥 등락률에서 abs 제거하고 자연스러운 부호 표시
                        val priceChangePercent = stockInfo.priceChangePercent
                        val percentSign = if (priceChangePercent >= 0) "+" else ""
                        val percentText = "${percentSign}${String.format("%.2f", priceChangePercent)}%"
                        
                        Text(
                            text = percentText,
                            style = SubtitleSb14,
                            color = if (isPositive) MainPink else MainBlue
                        )
                    }
                }
            }
        }
    }
}