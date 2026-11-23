package com.nowlsantos.drivelive

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform