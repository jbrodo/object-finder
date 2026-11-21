package it.objectfinder.app.ui.common

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/** true finché il telefono ha una rete con accesso a internet; si aggiorna quando cambia. */
@Composable
fun rememberIsOnline(): State<Boolean> {
    val context = LocalContext.current
    val manager = remember(context) { context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager }
    val online = remember { mutableStateOf(manager.isOnline()) }
    DisposableEffect(manager) {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { online.value = true }
            override fun onLost(network: Network) { online.value = manager.isOnline() }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                online.value = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            }
        }
        val request = NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
        runCatching { manager.registerNetworkCallback(request, callback) }
        onDispose { runCatching { manager.unregisterNetworkCallback(callback) } }
    }
    return online
}

private fun ConnectivityManager.isOnline(): Boolean =
    getNetworkCapabilities(activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
