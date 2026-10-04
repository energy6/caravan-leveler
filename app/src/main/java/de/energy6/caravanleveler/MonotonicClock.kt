package de.energy6.caravanleveler

import android.os.SystemClock
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

interface MonotonicClock {
    fun nowNanos(): Long
}

@Singleton
class SystemMonotonicClock @Inject constructor() : MonotonicClock {
    // SensorEvent.timestamp uses the elapsed-realtime clock. System.nanoTime()
    // is not guaranteed to share that time base across Android devices/suspend.
    override fun nowNanos(): Long = SystemClock.elapsedRealtimeNanos()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class ClockModule {
    @Binds
    abstract fun bindMonotonicClock(clock: SystemMonotonicClock): MonotonicClock
}
