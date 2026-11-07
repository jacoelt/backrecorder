package com.backrecorderapp.di

import javax.inject.Qualifier

/**
 * A [kotlinx.coroutines.CoroutineScope] tied to the application rather than to any component that
 * can be torn down.
 *
 * For work that must finish even though whatever asked for it is going away — closing the segment
 * a stopped [com.backrecorderapp.service.RecordingService] had open, for instance, which cannot
 * run on `serviceScope` (`onDestroy` cancels it) and cannot block the main thread.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope
