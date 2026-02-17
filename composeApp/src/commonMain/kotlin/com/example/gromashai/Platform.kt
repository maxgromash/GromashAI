package com.example.gromashai

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform