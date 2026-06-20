package me.wcy.music.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import top.wangchenyan.common.ext.toUnMutable
import me.wcy.music.consts.Consts
import top.wangchenyan.common.net.apiCall

/**
 * Created by wangchenyan.top on 2023/9/20.
 */
class SearchViewModel : ViewModel() {
    private val _keywords = MutableStateFlow("")
    val keywords = _keywords.toUnMutable()

    private val _historyKeywords = MutableStateFlow(SearchPreference.historyKeywords ?: emptyList())
    val historyKeywords = _historyKeywords.toUnMutable()

    private val _showResult = MutableStateFlow(false)
    val showResult = _showResult.toUnMutable()

    private val _suggestKeywords = MutableStateFlow(emptyList<String>())
    val suggestKeywords = _suggestKeywords.toUnMutable()

    private var suggestJob: Job? = null

    fun onInputChanged(input: String) {
        val text = input.trim()
        suggestJob?.cancel()
        if (text.isEmpty() || _showResult.value) {
            _suggestKeywords.value = emptyList()
            return
        }
        suggestJob = viewModelScope.launch(Dispatchers.IO) {
            delay(300)
            val res = apiCall {
                SearchApi.get().suggest(text)
            }
            val list = if (res.isSuccessWithData()) {
                res.getDataOrThrow()
                    .allMatch
                    .map { it.keyword.trim() }
                    .filter { it.isNotEmpty() && it != text }
                    .distinct()
                    .take(8)
            } else {
                emptyList()
            }
            _suggestKeywords.value = list
        }
    }

    fun search(keywords: String) {
        if (keywords.isEmpty()) {
            return
        }
        suggestJob?.cancel()
        _suggestKeywords.value = emptyList()
        _keywords.value = keywords
        _showResult.value = true

        val list = _historyKeywords.value.toMutableList()
        list.remove(keywords)
        list.add(0, keywords)
        val realList = list.take(Consts.SEARCH_HISTORY_COUNT)
        _historyKeywords.value = realList
        viewModelScope.launch(Dispatchers.IO) {
            SearchPreference.historyKeywords = realList
        }
    }

    fun showHistory() {
        _showResult.value = false
        _suggestKeywords.value = emptyList()
    }

    fun clearHistory() {
        _historyKeywords.value = emptyList()
        viewModelScope.launch(Dispatchers.IO) {
            SearchPreference.historyKeywords = emptyList()
        }
    }
}
