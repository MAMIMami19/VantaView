// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.di

import android.content.Context
import androidx.room.Room
import com.vantaview.app.data.db.VantaDb
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AppScope

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun db(@ApplicationContext context: Context): VantaDb =
        Room.databaseBuilder(context, VantaDb::class.java, "vanta.db")
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    @Singleton
    fun okHttp(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    @Provides
    @Singleton
    @AppScope
    fun appScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}
