package com.budcontrol.sony.wear

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.NodeClient
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.tasks.await

class WearCommandSender(private val context: Context) {

    companion object {
        private const val TAG = "WearCmd"
    }

    private val messageClient: MessageClient = Wearable.getMessageClient(context)
    private val nodeClient: NodeClient = Wearable.getNodeClient(context)

    suspend fun sendCommand(path: String, data: ByteArray = byteArrayOf()) {
        try {
            val nodes = nodeClient.connectedNodes.await()
            val phoneNode = nodes.firstOrNull() ?: run {
                Log.w(TAG, "No connected phone node")
                return
            }
            messageClient.sendMessage(phoneNode.id, path, data).await()
            Log.i(TAG, "Sent $path to ${phoneNode.displayName}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send $path: ${e.message}")
        }
    }
}
