package com.clawfare.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Investigation configuration - defines a flight search scope.
 */
@Serializable
data class Investigation(
    val slug: String,
    val origin: String,
    val destination: String,
    @SerialName("depart_start")
    val departStart: String,
    @SerialName("depart_end")
    val departEnd: String,
    @SerialName("return_start")
    val returnStart: String? = null,
    @SerialName("return_end")
    val returnEnd: String? = null,
    @SerialName("cabin_class")
    val cabinClass: String = "economy",
    @SerialName("max_stops")
    val maxStops: Int = 1,
    @SerialName("created_at")
    val createdAt: String,
    @SerialName("updated_at")
    val updatedAt: String,
)
