package com.lago.app.data.remote.dto

import com.google.gson.annotations.SerializedName

data class NewsDto(
    @SerializedName("id")
    val newsId: Int,
    @SerializedName("title")
    val title: String,
    @SerializedName("content")
    val content: String,
    @SerializedName("summary")
    val summary: String,
    @SerializedName("sentiment")
    val sentiment: String,
    @SerializedName("publishedAt")
    val publishedAt: String,
    @SerializedName("type")
    val type: String? = null
)

/**
 * 페이징된 뉴스 응답 구조
 */
data class NewsPageResponse(
    @SerializedName("content")
    val content: List<NewsDto>,
    @SerializedName("pageable")
    val pageable: Pageable,
    @SerializedName("last")
    val last: Boolean,
    @SerializedName("totalElements")
    val totalElements: Int,
    @SerializedName("totalPages")
    val totalPages: Int,
    @SerializedName("size")
    val size: Int,
    @SerializedName("number")
    val number: Int,
    @SerializedName("first")
    val first: Boolean,
    @SerializedName("numberOfElements")
    val numberOfElements: Int,
    @SerializedName("empty")
    val empty: Boolean
)

data class Pageable(
    @SerializedName("pageNumber")
    val pageNumber: Int,
    @SerializedName("pageSize")
    val pageSize: Int,
    @SerializedName("sort")
    val sort: Sort,
    @SerializedName("offset")
    val offset: Int,
    @SerializedName("paged")
    val paged: Boolean,
    @SerializedName("unpaged")
    val unpaged: Boolean
)

data class Sort(
    @SerializedName("empty")
    val empty: Boolean,
    @SerializedName("sorted")
    val sorted: Boolean,
    @SerializedName("unsorted")
    val unsorted: Boolean
)

// 기존 호환성을 위한 래퍼 클래스
data class NewsListResponse(
    val data: List<NewsDto>
)