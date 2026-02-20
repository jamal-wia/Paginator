package com.jamal_aliev.paginator

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jamal_aliev.paginator.extension.isEmptyState
import com.jamal_aliev.paginator.extension.isErrorState
import com.jamal_aliev.paginator.extension.isProgressState
import com.jamal_aliev.paginator.extension.isRealProgressState
import com.jamal_aliev.paginator.extension.isSuccessState
import com.jamal_aliev.paginator.page.PageState
import com.jamal_aliev.paginator.ui.theme.PaginatorTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val viewModel by viewModels<MainViewModel> { MainViewModel.Factory() }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PaginatorTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()
                val snackbarHostState = remember { SnackbarHostState() }
                val scope = rememberCoroutineScope()
                var showInspector by rememberSaveable { mutableStateOf(false) }

                LaunchedEffect(state.errorMessage) {
                    state.errorMessage?.let {
                        scope.launch {
                            snackbarHostState.showSnackbar(it)
                            viewModel.clearError()
                        }
                    }
                }

                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = {
                                Column {
                                    Text("Paginator Demo", fontWeight = FontWeight.Bold)
                                    Text(
                                        "Context: ${state.startContextPage}..${state.endContextPage} | Final: ${state.finalPage}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(
                                            alpha = 0.7f
                                        )
                                    )
                                }
                            },
                            actions = {
                                IconButton(onClick = { showInspector = true }) {
                                    Text("i", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        )
                    },
                    snackbarHost = {
                        SnackbarHost(snackbarHostState) { data ->
                            Snackbar(
                                snackbarData = data,
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                ) { padding ->
                    if (state.isInitialLoading) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(padding),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(48.dp),
                                    strokeWidth = 4.dp
                                )
                                Spacer(Modifier.height(16.dp))
                                Text(
                                    "Loading first page...",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(padding)
                        ) {
                            ControlPanel(state)
                            PaginatedContent(
                                state = state,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                if (showInspector) {
                    InspectorBottomSheet(
                        state = state,
                        onDismiss = { showInspector = false }
                    )
                }
            }
        }
    }

    @Composable
    private fun ControlPanel(state: MainViewState) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                // Row 1: Navigation
                Text(
                    "Navigation",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilledTonalButton(
                        onClick = { viewModel.goPreviousPage() },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text("Prev", fontSize = 12.sp, maxLines = 1)
                    }
                    FilledTonalButton(
                        onClick = { viewModel.goNextPage() },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text("Next", fontSize = 12.sp, maxLines = 1)
                    }
                }

                Spacer(Modifier.height(8.dp))

                // Row 2: Bookmarks
                Text(
                    "Bookmarks: ${state.bookmarks.joinToString(", ") { "p$it" }}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    OutlinedButton(
                        onClick = { viewModel.jumpBackward() },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text("Bookmark Back", fontSize = 11.sp, maxLines = 1)
                    }
                    OutlinedButton(
                        onClick = { viewModel.jumpForward() },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text("Bookmark Fwd", fontSize = 11.sp, maxLines = 1)
                    }
                }

                Spacer(Modifier.height(8.dp))

                // Row 3: Jump to page
                Text(
                    "Jump to Page",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(4.dp))
                JumpToPageRow()
            }
        }
    }

    @Composable
    private fun JumpToPageRow() {
        var jumpText by rememberSaveable { mutableStateOf("") }
        val focusManager = LocalFocusManager.current
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = jumpText,
                onValueChange = { jumpText = it.filter { c -> c.isDigit() } },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Page number", fontSize = 13.sp) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Go
                ),
                keyboardActions = KeyboardActions(
                    onGo = {
                        jumpText.toIntOrNull()?.let { page ->
                            if (page > 0) {
                                viewModel.jumpToPage(page)
                                jumpText = ""
                                focusManager.clearFocus()
                            }
                        }
                    }
                )
            )
            Button(
                onClick = {
                    jumpText.toIntOrNull()?.let { page ->
                        if (page > 0) {
                            viewModel.jumpToPage(page)
                            jumpText = ""
                            focusManager.clearFocus()
                        }
                    }
                },
                enabled = jumpText.toIntOrNull()?.let { it > 0 } == true
            ) {
                Text("Jump")
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun PaginatedContent(state: MainViewState, modifier: Modifier) {
        val lazyListState = rememberLazyListState()

        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = { viewModel.restart() },
            modifier = modifier
        ) {
            LazyColumn(
                state = lazyListState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                state.data.forEach { pageState: PageState<String> ->
                    when {
                        pageState.isSuccessState() -> {
                            item(key = "header_success_${pageState.page}") {
                                PageHeader(
                                    page = pageState.page,
                                    label = "SUCCESS",
                                    itemCount = pageState.data.size,
                                    maxItems = SampleRepository.PAGE_SIZE,
                                    color = Color(0xFF2E7D32)
                                )
                            }
                            items(
                                count = pageState.data.size,
                                key = { "success_${pageState.page}_$it" }
                            ) { index ->
                                SuccessItem(
                                    text = pageState.data[index],
                                    index = index,
                                    isLast = index == pageState.data.lastIndex,
                                    isIncomplete = pageState.data.size < SampleRepository.PAGE_SIZE
                                )
                            }
                        }

                        pageState.isEmptyState() -> {
                            item(key = "empty_${pageState.page}") {
                                PageHeader(
                                    page = pageState.page,
                                    label = "EMPTY",
                                    itemCount = 0,
                                    maxItems = SampleRepository.PAGE_SIZE,
                                    color = Color(0xFF757575)
                                )
                                EmptyPageCard(pageState.page)
                            }
                        }

                        pageState.isErrorState() -> {
                            item(key = "error_${pageState.page}") {
                                PageHeader(
                                    page = pageState.page,
                                    label = "ERROR",
                                    itemCount = pageState.data.size,
                                    maxItems = SampleRepository.PAGE_SIZE,
                                    color = Color(0xFFC62828)
                                )
                                ErrorPageCard(
                                    page = pageState.page,
                                    message = pageState.exception.message ?: "Unknown error",
                                    hasData = pageState.data.isNotEmpty()
                                )
                            }
                            if (pageState.data.isNotEmpty()) {
                                items(
                                    count = pageState.data.size,
                                    key = { "error_data_${pageState.page}_$it" }
                                ) { index ->
                                    CachedDataItem(
                                        text = pageState.data[index],
                                        index = index
                                    )
                                }
                            }
                        }

                        pageState.isProgressState() -> {
                            val isPrevious = pageState.isRealProgressState(PreviousProgressState::class)

                            if (pageState.data.isNotEmpty()) {
                                item(key = "header_progress_data_${pageState.page}") {
                                    PageHeader(
                                        page = pageState.page,
                                        label = if (isPrevious)
                                            "LOADING PREV (with cached data)"
                                        else
                                            "LOADING NEXT (with cached data)",
                                        itemCount = pageState.data.size,
                                        maxItems = SampleRepository.PAGE_SIZE,
                                        color = Color(0xFFE65100)
                                    )
                                }
                                if (isPrevious) {
                                    item(key = "progress_indicator_before_data_${pageState.page}") {
                                        ProgressCard(
                                            page = pageState.page,
                                            hasData = true
                                        )
                                    }
                                }
                                items(
                                    count = pageState.data.size,
                                    key = { "progress_data_${pageState.page}_$it" }
                                ) { index ->
                                    CachedDataItem(
                                        text = pageState.data[index],
                                        index = index
                                    )
                                }
                                if (!isPrevious) {
                                    item(key = "progress_indicator_after_data_${pageState.page}") {
                                        ProgressCard(
                                            page = pageState.page,
                                            hasData = true
                                        )
                                    }
                                }
                            } else {
                                item(key = "progress_${pageState.page}") {
                                    PageHeader(
                                        page = pageState.page,
                                        label = if (isPrevious)
                                            "LOADING PREV"
                                        else
                                            "LOADING",
                                        itemCount = 0,
                                        maxItems = SampleRepository.PAGE_SIZE,
                                        color = Color(0xFF1565C0)
                                    )
                                    if (isPrevious) {
                                        ProgressCard(
                                            page = pageState.page,
                                            hasData = false
                                        )
                                    } else {
                                        ProgressCard(
                                            page = pageState.page,
                                            hasData = false
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun PageHeader(
        page: Int,
        label: String,
        itemCount: Int,
        maxItems: Int,
        color: Color
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(color),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "$page",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = color,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.width(8.dp))
            if (itemCount < maxItems && itemCount > 0) {
                Text(
                    "$itemCount/$maxItems items (incomplete)",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFE65100)
                )
            } else {
                Text(
                    "$itemCount/$maxItems items",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    @Composable
    private fun SuccessItem(
        text: String,
        index: Int,
        isLast: Boolean,
        isIncomplete: Boolean
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (isIncomplete)
                    Color(0xFFFFF3E0)
                else
                    Color(0xFFE8F5E9)
            ),
            shape = RoundedCornerShape(8.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f)
                )
                if (isIncomplete && isLast) {
                    Text(
                        "incomplete page",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFFE65100)
                    )
                }
            }
        }
    }

    @Composable
    private fun CachedDataItem(text: String, index: Int) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp),
            colors = CardDefaults.cardColors(
                containerColor = Color(0xFFFFF8E1)
            ),
            shape = RoundedCornerShape(8.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
                Text(
                    "cached",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFF57F17)
                )
            }
        }
    }

    @Composable
    private fun ErrorPageCard(page: Int, message: String, hasData: Boolean) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            colors = CardDefaults.cardColors(
                containerColor = Color(0xFFFFEBEE)
            ),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFFC62828),
                    textAlign = TextAlign.Center
                )
                if (hasData) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Showing cached data below",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFFF57F17)
                    )
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { viewModel.refreshPage(page) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFC62828)
                    )
                ) {
                    Text("Retry")
                }
            }
        }
    }

    @Composable
    private fun ProgressCard(page: Int, hasData: Boolean) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (hasData) Color(0xFFFFF3E0) else Color(0xFFE3F2FD)
            ),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(32.dp),
                    strokeWidth = 3.dp,
                    color = if (hasData) Color(0xFFE65100) else Color(0xFF1565C0)
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = if (hasData)
                        "Reloading page $page (incomplete data shown above)..."
                    else
                        "Loading page $page...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    @Composable
    private fun EmptyPageCard(page: Int) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            colors = CardDefaults.cardColors(
                containerColor = Color(0xFFF5F5F5)
            ),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(
                text = "Page $page is empty",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFF757575)
            )
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun InspectorBottomSheet(state: MainViewState, onDismiss: () -> Unit) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = sheetState
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Text(
                    "Paginator Inspector",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(16.dp))

                // Stats
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            "Statistics",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(8.dp))
                        StatRow("Start Context Page", "${state.startContextPage}")
                        StatRow("End Context Page", "${state.endContextPage}")
                        StatRow("Final Page Limit", "${state.finalPage}")
                        StatRow("Cached Pages", "${state.cachedPages.size}")
                        StatRow("Total Cached Items", "${state.totalCachedItems}")
                        StatRow("Capacity", "${SampleRepository.PAGE_SIZE}")
                        StatRow(
                            "Bookmarks",
                            state.bookmarks.joinToString(", ") { "p$it" }
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                // Cached pages visualization
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            "Cached Pages",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(8.dp))

                        if (state.cachedPages.isEmpty()) {
                            Text(
                                "No pages in cache",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                state.cachedPages.forEach { info ->
                                    CachePageChip(info, state)
                                }
                            }
                        }

                        Spacer(Modifier.height(8.dp))

                        // Legend
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            LegendDot(Color(0xFF2E7D32), "Success")
                            LegendDot(Color(0xFFE65100), "Incomplete")
                            LegendDot(Color(0xFF1565C0), "Progress")
                            LegendDot(Color(0xFFC62828), "Error")
                            LegendDot(Color(0xFF757575), "Empty")
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                // Snapshot pages
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            "Current Snapshot (${state.data.size} pages visible)",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(8.dp))
                        state.data.forEach { pageState ->
                            val (emoji, label) = when {
                                pageState.isRealProgressState(PreviousProgressState::class) && pageState.data.isNotEmpty() ->
                                    Color(0xFFE65100) to "p${pageState.page}: Loading prev ↑ (${pageState.data.size} cached items)"

                                pageState.isRealProgressState(PreviousProgressState::class) ->
                                    Color(0xFF1565C0) to "p${pageState.page}: Loading prev ↑"

                                pageState.isRealProgressState(NextProgressState::class) && pageState.data.isNotEmpty() ->
                                    Color(0xFFE65100) to "p${pageState.page}: Loading next ↓ (${pageState.data.size} cached items)"

                                pageState.isProgressState() ->
                                    Color(0xFF1565C0) to "p${pageState.page}: Loading..."

                                pageState.isErrorState() ->
                                    Color(0xFFC62828) to "p${pageState.page}: Error - ${(pageState as PageState.ErrorPage).exception.message}"

                                pageState.isEmptyState() ->
                                    Color(0xFF757575) to "p${pageState.page}: Empty"

                                pageState.isSuccessState() && pageState.data.size < SampleRepository.PAGE_SIZE ->
                                    Color(0xFFE65100) to "p${pageState.page}: Success (${pageState.data.size}/${SampleRepository.PAGE_SIZE} - incomplete)"

                                pageState.isSuccessState() ->
                                    Color(0xFF2E7D32) to "p${pageState.page}: Success (${pageState.data.size}/${SampleRepository.PAGE_SIZE})"

                                else -> Color.Gray to "p${pageState.page}: Unknown"
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(vertical = 2.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(emoji)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    label,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(32.dp))
            }
        }
    }

    @Composable
    private fun CachePageChip(info: CachedPageInfo, state: MainViewState) {
        val isInContext = info.page in state.startContextPage..state.endContextPage
        val chipColor = when {
            info.type.startsWith("Success") && info.itemCount < SampleRepository.PAGE_SIZE ->
                Color(0xFFE65100) // incomplete
            info.type.startsWith("Success") -> Color(0xFF2E7D32)
            info.type == "Progress" -> Color(0xFF1565C0)
            info.type == "Error" -> Color(0xFFC62828)
            info.type == "Empty" -> Color(0xFF757575)
            else -> Color.Gray
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .then(
                    if (isInContext)
                        Modifier.border(
                            2.dp,
                            MaterialTheme.colorScheme.primary,
                            RoundedCornerShape(8.dp)
                        )
                    else Modifier
                )
                .background(chipColor.copy(alpha = 0.15f))
                .padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            Text(
                "p${info.page}",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = chipColor
            )
            Text(
                info.type,
                fontSize = 9.sp,
                color = chipColor.copy(alpha = 0.8f)
            )
            Text(
                "${info.itemCount} items",
                fontSize = 9.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    @Composable
    private fun StatRow(label: String, value: String) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, style = MaterialTheme.typography.bodySmall)
            Text(
                value,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold
            )
        }
    }

    @Composable
    private fun LegendDot(color: Color, label: String) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(color)
            )
            Spacer(Modifier.width(4.dp))
            Text(label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
