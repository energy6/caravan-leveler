package de.energy6.caravanleveler

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
fun Dispatchers.setupForTest() = Dispatchers.setMain(Dispatchers.Unconfined)
