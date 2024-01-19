package com.jamal_aliev.paginator

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jamal_aliev.paginator.MainViewState.DataState
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
                    DataState(state = state)
                }
            }
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
            items(state.data.size) {
                StrItem(data = state.data[it])
            }
        }
    }

    @Composable
    fun StrItem(data: String) {
        Text(
            text = data,
            fontSize = 26.sp
        )
    }
}
