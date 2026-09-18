package com.omsingh.telepad.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.omsingh.telepad.ui.theme.Dimens
import kotlinx.coroutines.launch

/**
 * First-run onboarding: 4-slide horizontal pager.
 *
 * The slides explain (in order):
 *  1. **Welcome** — what is Telepad.
 *  2. **PC install** — go to telepad.app/download.
 *  3. **Same Wi-Fi** — network setup tip.
 *  4. **You're ready** — fingerprint verification preview.
 *
 * Skip button is always present; Next becomes Done on the last slide.
 * On completion, the caller persists "onboarding shown" and navigates to Home.
 *
 * Why pager and not a single scrollable screen? A pager paces the read —
 * users absorb one idea per slide. A scrollable wall of text gets skipped.
 */
@Composable
fun OnboardingScreen(
    onFinished: () -> Unit,
) {
    val slides = remember { onboardingSlides() }
    val pagerState = rememberPagerState(pageCount = { slides.size })
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.systemBars),
    ) {
        // Skip button — top-right of the entire screen.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Dimens.ScreenHorizontalPadding),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onFinished) {
                Text(
                    "Skip",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) { page ->
            OnboardingSlide(slides[page])
        }

        // Dot indicator + Next/Done button.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Dimens.ScreenHorizontalPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Dimens.ItemSpacing),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(slides.size) { i ->
                    Box(
                        modifier = Modifier
                            .size(if (i == pagerState.currentPage) 10.dp else 8.dp)
                            .background(
                                color = if (i == pagerState.currentPage)
                                    MaterialTheme.colorScheme.primary
                                else
                                    MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                                shape = CircleShape,
                            )
                    )
                }
            }
            val isLast = pagerState.currentPage == slides.size - 1
            Button(
                onClick = {
                    if (isLast) onFinished()
                    else scope.launch {
                        pagerState.animateScrollToPage(pagerState.currentPage + 1)
                    }
                },
                shape = RoundedCornerShape(Dimens.ButtonCornerRadius),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(Dimens.ButtonMinHeight),
            ) {
                Text(if (isLast) "Get started" else "Next")
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun OnboardingSlide(slide: Slide) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = Dimens.ScreenHorizontalPadding * 2),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(Dimens.OnboardingIllustrationSize)
                .background(
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                slide.icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(80.dp),
            )
        }
        Spacer(Modifier.height(40.dp))
        Text(
            text = slide.title,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = slide.body,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private data class Slide(val icon: ImageVector, val title: String, val body: String)

private fun onboardingSlides(): List<Slide> = listOf(
    Slide(
        icon = Icons.Filled.TouchApp,
        title = "Welcome to Telepad",
        body = "Turn your phone into a touchpad and keyboard for your PC. Fast, private, and no account needed."
    ),
    Slide(
        icon = Icons.Filled.Computer,
        title = "Install the desktop app",
        body = "Telepad needs a tiny companion app on your PC. Visit telepad.app/download to get it."
    ),
    Slide(
        icon = Icons.Filled.Wifi,
        title = "Use the same Wi-Fi",
        body = "Your phone and PC must be on the same Wi-Fi network. Or use Bluetooth if Wi-Fi isn't available."
    ),
    Slide(
        icon = Icons.Filled.Lock,
        title = "Verify once, trust forever",
        body = "On the first connection, Telepad shows a short fingerprint to verify against your PC screen. Once you tap 'Trust', every future connection is silent."
    ),
)

// without importing it explicitly (already pulled via other Compose imports).
