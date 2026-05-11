package me.wcy.music.search.bean

import com.google.gson.annotations.SerializedName

data class SearchSuggestData(
    @SerializedName("allMatch")
    val allMatch: List<SearchSuggestItemData> = emptyList()
)

data class SearchSuggestItemData(
    @SerializedName("keyword")
    val keyword: String = "",
    @SerializedName("type")
    val type: Int = 0,
    @SerializedName("alg")
    val alg: String = ""
)
