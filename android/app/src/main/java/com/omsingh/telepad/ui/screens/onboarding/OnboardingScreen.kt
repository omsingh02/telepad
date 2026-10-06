package com.omsingh.telepad.ui.screens.onboarding

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.omsingh.telepad.R
import com.omsingh.telepad.platform.Links
import com.omsingh.telepad.platform.LocalPlatformActions
import com.omsingh.telepad.ui.components.HeroIllustration
import com.omsingh.telepad.ui.components.Scene
import com.omsingh.telepad.ui.theme.Spacing
import kotlinx.coroutines.launch

private class Page(val scene: Scene, val title: Int, val body: Int, val download: Boolean = false)

private val pages = listOf(
    Page(Scene.PHONE_AND_PC, R.string.onboarding_welcome_title, R.string.onboarding_welcome_body),
    Page(Scene.PHONE_AND_PC, R.string.onboarding_install_title, R.string.onboarding_install_body, download = true),
    Page(Scene.VERIFY, R.string.onboarding_network_title, R.string.onboarding_network_body),
)

/**
 * Three short pages the first time the app opens: what it is, what the PC needs, and what
 * happens the first time they meet. Everything on them can be skipped, and the only thing
 * that is not obvious from the app itself (that the PC needs software) has a button.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OnboardingScreen(onFinish: () -> Unit, modifier: Modifier = Modifier) {
    val pager = rememberPagerState(pageCount = { pages.size })
    val scope = rememberCoroutineScope()
    val platform = LocalPlatformActions.current
    val last = pager.currentPage == pages.lastIndex

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.sm), horizontalArrangement = Arrangement.End) {
            if (!last) TextButton(onClick = onFinish) { Text(stringResource(R.string.action_skip)) }
            else Spacer(Modifier.height(48.dp))
        }

        HorizontalPager(state = pager, modifier = Modifier.weight(1f)) { index ->
            val page = pages[index]
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.xl),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Spacing.lg, Alignment.CenterVertically),
            ) {
                HeroIllustration(page.scene)
                Text(stringResource(page.title), style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
                Text(
                    stringResource(page.body),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                if (page.download) {
                    OutlinedButton(onClick = { platform.openUrl(Links.RELEASES) }) { Text(stringResource(R.string.onboarding_download)) }
                }
            }
        }

        val position = stringResource(R.string.onboarding_page, pager.currentPage + 1, pages.size)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.xl),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                modifier = Modifier.semantics { contentDescription = position },
            ) {
                for (index in pages.indices) Dot(selected = index == pager.currentPage)
            }
            Button(
                onClick = {
                    if (last) onFinish() else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }
                },
            ) {
                Text(stringResource(if (last) R.string.onboarding_get_started else R.string.action_next))
            }
        }
    }
}

@Composable
private fun Dot(selected: Boolean) {
    val width = animateDpAsState(if (selected) 24.dp else 8.dp, label = "dotWidth")
    val color = animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        label = "dotColor",
    )
    Row(
        Modifier
            .height(8.dp)
            .width(width.value)
            .clip(CircleShape)
            .background(color.value),
    ) {}
}

@Composable
private fun Spacer(modifier: Modifier) = androidx.compose.foundation.layout.Spacer(modifier)
