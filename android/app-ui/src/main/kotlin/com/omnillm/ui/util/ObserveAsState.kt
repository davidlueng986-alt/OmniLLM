package com.omnillm.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.StateFlow
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue

/**
 * Bridge pure JVM listener ViewModels into Compose without AndroidX ViewModel.
 */
@Composable
fun <T> observeListenerAsState(
    initial: T,
    observe: (listener: (T) -> Unit) -> (() -> Unit),
): State<T> {
    val state = remember { mutableStateOf(initial) }
    DisposableEffect(observe) {
        val unsubscribe = observe { next -> state.value = next }
        onDispose { unsubscribe() }
    }
    return state
}

@Composable
fun <T> StateFlow<T>.collectAsComposeState(): T {
    val value by collectAsState()
    return value
}
