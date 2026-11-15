package com.backrecorderapp.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton

private val Context.complimentaryAccessDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "complimentary_access"
)

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ComplimentaryAccessDataStore

/**
 * Separate from [ComplimentaryModule], which instrumented tests replace: a test wants the real
 * store on the real file and only the accepted digests swapped, so the two must not be replaceable
 * together.
 */
@Module
@InstallIn(SingletonComponent::class)
object ComplimentaryDataStoreModule {

    @Provides
    @Singleton
    @ComplimentaryAccessDataStore
    fun provideComplimentaryAccessDataStore(
        @ApplicationContext context: Context
    ): DataStore<Preferences> = context.complimentaryAccessDataStore
}
