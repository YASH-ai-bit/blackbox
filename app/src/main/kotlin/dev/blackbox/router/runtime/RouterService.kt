package dev.blackbox.router.runtime

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dev.blackbox.core.RouterStatus
import dev.blackbox.router.MainActivity
import dev.blackbox.router.R
import dev.blackbox.router.data.ProfileStore
import kotlinx.coroutines.*

/** Foreground service starts before the first privileged mutation and owns the router lifetime. */
class RouterService:Service() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val controller by lazy { RouterController.get(this) }
    private var operation:Job?=null
    override fun onBind(intent:Intent?):IBinder?=null
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("router","BLACKBOX Router",NotificationManager.IMPORTANCE_LOW))
        startForeground(50,notification("Preparing router"))
        scope.launch {
            controller.state.collect { s ->
                getSystemService(NotificationManager::class.java).notify(50,notification("${s.status} · ${s.clients.count { it.online }} clients · VPN ${s.profile?.vpn?:"Off"}"))
            }
        }
    }
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        val action=intent?.action?:"RECOVER"
        val previous=operation
        if(previous?.isActive==true && action !in setOf("STOP","RECOVER")) return START_NOT_STICKY
        if(action in setOf("STOP","RECOVER")) previous?.cancel()
        operation=scope.launch {
            previous?.join()
            when(action) {
                "START" -> {
                    val id=intent?.getStringExtra("profile")
                    val p=ProfileStore(this@RouterService).all().firstOrNull { it.id==id }
                    if(p!=null) controller.start(p)
                }
                "STOP" -> controller.stop()
                "RECOVER" -> controller.recover()
            }
            if(isActive && controller.state.value.status !in setOf(RouterStatus.RUNNING,RouterStatus.DEGRADED)) { stopForeground(STOP_FOREGROUND_REMOVE);stopSelfResult(startId) }
        }
        return START_NOT_STICKY
    }
    private fun notification(text:String):Notification {
        val open=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop=PendingIntent.getService(this,1,Intent(this,RouterService::class.java).setAction("STOP"),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this,"router").setSmallIcon(R.drawable.ic_blackbox).setContentTitle("BLACKBOX Router").setContentText(text)
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).addAction(0,"Stop",stop).build()
    }
    override fun onDestroy() {
        scope.cancel()
        if(controller.state.value.status in setOf(RouterStatus.RUNNING,RouterStatus.DEGRADED)) CoroutineScope(Dispatchers.IO).launch { controller.stop() }
        super.onDestroy()
    }
    companion object {
        fun command(context:Context,action:String,profile:String?=null) { context.startForegroundService(Intent(context,RouterService::class.java).setAction(action).putExtra("profile",profile)) }
    }
}
