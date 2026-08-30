package com.backrecorderapp.di

import javax.inject.Qualifier

/**
 * The dispatcher long-running background work runs on.
 *
 * Injected rather than referenced as `Dispatchers.IO` directly so tests can substitute a test
 * dispatcher and drive time-based logic - the sync loop's tick interval and backoff - on a
 * virtual clock instead of waiting out real seconds.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher
