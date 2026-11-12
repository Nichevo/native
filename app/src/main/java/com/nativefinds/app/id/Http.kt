package com.nativefinds.app.id

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

object Http {
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("User-Agent", "NativeFinds-Android/0.1").build())
            }
            .build()
    }

    /** Local vision models can take a while, especially on a cold start. */
    val slowClient: OkHttpClient by lazy {
        client.newBuilder().readTimeout(240, TimeUnit.SECONDS).build()
    }
}
