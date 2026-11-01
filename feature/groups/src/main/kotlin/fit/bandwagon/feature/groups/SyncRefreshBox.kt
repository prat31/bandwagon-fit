package fit.bandwagon.feature.groups

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import fit.bandwagon.core.sync.SyncEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SyncRefreshViewModel @Inject constructor(
    private val syncEngine: SyncEngine,
) : ViewModel() {
    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    /** Runs a real sync rather than queueing one, so the spinner means "your numbers are uploaded". */
    fun refresh() {
        if (_refreshing.value) return
        viewModelScope.launch {
            _refreshing.value = true
            runCatching { syncEngine.sync() }
            _refreshing.value = false
        }
    }
}

/** Pull down to sync this phone's health data now. Group screens update from Firestore on their own. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncRefreshBox(
    modifier: Modifier = Modifier,
    viewModel: SyncRefreshViewModel = hiltViewModel(),
    content: @Composable BoxScope.() -> Unit,
) {
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = viewModel::refresh,
        modifier = modifier.fillMaxSize(),
        content = content,
    )
}
