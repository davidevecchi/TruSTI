package com.davv.trusti.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.davv.trusti.R

/**
 * Standard page layout with consistent margins, title, and scrolling content area.
 * Used across all main screens (Tests, Bonds, Settings) to ensure theme consistency.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StandardPageLayout(
    title: String,
    floatingActionButton: @Composable () -> Unit = {},
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    isRefreshing: Boolean = false,
    onRefresh: (() -> Unit)? = null,
    listState: LazyListState = rememberLazyListState(),
    content: LazyListScope.() -> Unit
) {
    Scaffold(
        floatingActionButton = floatingActionButton
    ) { padding ->
        val pullToRefreshState = rememberPullToRefreshState()
        
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = { onRefresh?.invoke() },
            state = pullToRefreshState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
                verticalArrangement = verticalArrangement
            ) {
                item {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(top = 16.dp, bottom = 24.dp)
                    )
                }
                
                content()
                
                // Bottom spacer to ensure content isn't hidden behind FAB
                item {
                    Spacer(Modifier.height(80.dp))
                }
            }
        }
    }
}

/**
 * Standard empty state component with consistent styling
 */
@Composable
fun StandardEmptyState(
    title: String,
    subtitle: String,
    icon: @Composable (() -> Unit)? = null
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(300.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            icon?.invoke()
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp)
            )
        }
    }
}

/**
 * Standard FAB for adding new items
 */
@Composable
fun StandardAddFab(
    text: String,
    onClick: () -> Unit
) {
    ExtendedFloatingActionButton(
        text = { Text(text) },
        icon = {
            Icon(
                painter = painterResource(R.drawable.ic_plus),
                contentDescription = null
            )
        },
        onClick = onClick
    )
}

/**
 * Standard section divider
 */
@Composable
fun StandardSectionDivider() {
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant,
        modifier = Modifier.padding(vertical = 12.dp)
    )
}

/**
 * Standard spacer for consistent spacing
 */
@Composable
fun StandardSpacer(height: Int = 16) {
    Spacer(Modifier.height(height.dp))
}
