package com.jamal_aliev.paginator

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jamal_aliev.paginator.MainViewState.DataState
import com.jamal_aliev.paginator.Paginator.Companion.isEmptyState
import com.jamal_aliev.paginator.Paginator.Companion.isErrorState
import com.jamal_aliev.paginator.Paginator.Companion.isProgressState
import com.jamal_aliev.paginator.Paginator.Companion.isSuccessState
import com.jamal_aliev.paginator.ui.theme.PaginatorTheme

class MainActivity : ComponentActivity() {

    private val viewModel by viewModels<MainViewModel> { MainViewModel.Factory() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            PaginatorTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val state by viewModel.state.collectAsStateWithLifecycle()

                    when (state) {
                        is DataState -> {
                            DataState(state = state as DataState)
                        }

                        MainViewState.ProgressState -> {
                            ProgressState()
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun ProgressState() {
        Box(contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    }

    @Composable
    fun DataState(state: DataState) {
        val lazyListState = rememberLazyListState()

        val endOfListReached by remember {
            derivedStateOf {
                val currentIndex = lazyListState.layoutInfo.visibleItemsInfo.lastOrNull()?.index
                val totalCount = lazyListState.layoutInfo.totalItemsCount
                currentIndex != null && currentIndex >= totalCount - 10
            }
        }
        LaunchedEffect(endOfListReached) {
            if (endOfListReached) {
                viewModel.endReached()
            }
        }

        LazyColumn(state = lazyListState) {
            state.data.forEach { pageState ->
                when {
                    pageState.isSuccessState() -> {
                        items(pageState.data.size) { index ->
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                if (index == 0) {
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Text(text = "PageSuccess #${pageState.page}")
                                }

                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(Color.Green),
                                    contentAlignment = Alignment.Center
                                ) {
                                    StrItem(data = pageState.data[index])
                                }

                                if (index == pageState.data.lastIndex) {
                                    Text(text = "PageSuccess #${pageState.page}")
                                    Spacer(modifier = Modifier.height(16.dp))
                                }
                            }
                        }
                    }

                    pageState.isEmptyState() -> TODO()

                    pageState.isErrorState() -> {
                        pageState as Paginator.PageState.Error
                        item {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(text = "PageError #${pageState.page}")
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(Color.Red),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(text = pageState.exception.message.toString())
                                    Button(onClick = { viewModel.refreshPage(pageState) }) {
                                        Text(text = "Refresh")
                                    }
                                }
                                Text(text = "PageError #${pageState.page}")
                                Spacer(modifier = Modifier.height(16.dp))
                            }
                        }
                    }

                    pageState.isProgressState() -> {
                        item {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(text = "PageProgress #${pageState.page}")

                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(Color.Cyan),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator()
                                }

                                Text(text = "PageProgress #${pageState.page}")
                                Spacer(modifier = Modifier.height(16.dp))
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun StrItem(data: String) {
        Text(
            text = "Item $data",
            fontSize = 26.sp
        )
    }
}
