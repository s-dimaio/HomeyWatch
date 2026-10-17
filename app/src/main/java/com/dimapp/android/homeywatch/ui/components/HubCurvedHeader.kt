package com.dimapp.android.homeywatch.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.AnchorType
import androidx.wear.compose.foundation.CurvedAlignment
import androidx.wear.compose.foundation.CurvedLayout
import androidx.wear.compose.foundation.CurvedModifier
import androidx.wear.compose.foundation.CurvedTextStyle
import androidx.wear.compose.foundation.curvedRow
import androidx.wear.compose.foundation.padding
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.curvedText

/**
 * Persistent curved header displaying the active Homey Hub name along the top bezel (12 o'clock),
 * the active destination tab icon, and an auto-fading 4-page dots overlay that takes zero layout space.
 *
 * Implements:
 * - Concentric circular bezel adherence on round Wear OS displays via [CurvedLayout]
 * - Crossfading active destination tab icon in crisp white
 * - Auto-fading 4-dot indicator overlay (only appears during paging/swipe, leaving full screen space)
 * - Compact AMOLED black vertical gradient scrim preventing scrollable list collision
 * - Centered fallback layout on square/rectangular screens
 *
 * @public
 * @param hubName Display name of the active Homey hub.
 * @param tabIconRes Optional drawable resource representing the active tab icon.
 * @param selectedPage Current active carousel page index (0..3).
 * @param pageCount Total count of carousel destination pages (default: 4).
 * @param indicatorAlpha Dynamic opacity for the page indicator dots (0f when idle, 1f during swipe).
 * @param modifier Layout modifier.
 * @example
 * HubCurvedHeader(
 *     hubName = "Homey Pro",
 *     tabIconRes = R.drawable.selector_home,
 *     selectedPage = 0,
 *     pageCount = 4,
 *     indicatorAlpha = 1f
 * )
 */
@Composable
fun HubCurvedHeader(
    hubName: String,
    @DrawableRes tabIconRes: Int? = null,
    selectedPage: Int = 0,
    pageCount: Int = 4,
    indicatorAlpha: Float = 0f,
    modifier: Modifier = Modifier
) {
    val isRound = LocalConfiguration.current.isScreenRound

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        // Vertical gradient scrim to smoothly dissolve scrolling items into black behind the header
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .background(
                    Brush.verticalGradient(
                        0.0f to Color.Black,
                        0.70f to Color.Black,
                        1.0f to Color.Transparent
                    )
                )
        )

        if (isRound) {
            CurvedLayout(
                anchor = 270f,
                anchorType = AnchorType.Center,
                modifier = Modifier.fillMaxSize()
            ) {
                curvedRow(
                    modifier = CurvedModifier.padding(radial = 6.dp),
                    radialAlignment = CurvedAlignment.Radial.Center
                ) {
                    curvedText(
                        text = hubName.uppercase(),
                        style = CurvedTextStyle(
                            TextStyle(
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White.copy(alpha = 0.85f),
                                letterSpacing = 1.2.sp
                            )
                        )
                    )
                }
            }

            // Destination tab icon
            if (tabIconRes != null) {
                Box(
                    modifier = Modifier.padding(top = 19.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Crossfade(
                        targetState = tabIconRes,
                        label = "TabIconCrossfade"
                    ) { iconRes ->
                        Icon(
                            painter = painterResource(id = iconRes),
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                // Auto-fading 4-dot overlay (takes ZERO layout space from the screen)
                if (indicatorAlpha > 0f) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .padding(top = 39.dp)
                            .graphicsLayer { alpha = indicatorAlpha }
                    ) {
                        for (i in 0 until pageCount) {
                            val isSelected = i == selectedPage
                            Box(
                                modifier = Modifier
                                    .size(if (isSelected) 4.5.dp else 3.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (isSelected) Color.White else Color.White.copy(alpha = 0.45f)
                                    )
                            )
                        }
                    }
                }
            }
        } else {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(top = 4.dp)
            ) {
                Text(
                    text = hubName.uppercase(),
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    letterSpacing = 1.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (tabIconRes != null) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Crossfade(
                        targetState = tabIconRes,
                        label = "TabIconCrossfade"
                    ) { iconRes ->
                        Icon(
                            painter = painterResource(id = iconRes),
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    if (indicatorAlpha > 0f) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.graphicsLayer { alpha = indicatorAlpha }
                        ) {
                            for (i in 0 until pageCount) {
                                val isSelected = i == selectedPage
                                Box(
                                    modifier = Modifier
                                        .size(if (isSelected) 5.dp else 3.5.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (isSelected) Color.White else Color.White.copy(alpha = 0.45f)
                                        )
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
