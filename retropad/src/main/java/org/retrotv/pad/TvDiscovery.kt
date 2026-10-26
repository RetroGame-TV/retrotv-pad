package org.retrotv.pad
import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper

/** Finds RetroTV Lite (Wi-Fi channel) and Google TV remote services on the local network with DNS-SD. */
class TvDiscovery(context:Context,private val found:(Service)->Unit){
    data class Service(val google:Boolean,val name:String,val host:String,val port:Int)
    private val nsd=context.getSystemService(NsdManager::class.java)
    private val main=Handler(Looper.getMainLooper())
    private val queue=ArrayDeque<NsdServiceInfo>()
    private var resolving=false
    private var stopped=false
    private val listeners=listOf("_retrotv._tcp.","_androidtvremote2._tcp.").map{type->object:NsdManager.DiscoveryListener{
        override fun onDiscoveryStarted(serviceType:String){}
        override fun onDiscoveryStopped(serviceType:String){}
        override fun onStartDiscoveryFailed(serviceType:String,errorCode:Int){}
        override fun onStopDiscoveryFailed(serviceType:String,errorCode:Int){}
        override fun onServiceLost(info:NsdServiceInfo){}
        override fun onServiceFound(info:NsdServiceInfo){main.post{if(!stopped){queue.addLast(info);next()}}}
    } to type}
    fun start(){listeners.forEach{(listener,type)->runCatching{nsd?.discoverServices(type,NsdManager.PROTOCOL_DNS_SD,listener)}}}
    fun stop(){stopped=true;listeners.forEach{(listener,_)->runCatching{nsd?.stopServiceDiscovery(listener)}};queue.clear()}
    // Older Android resolves one service at a time.
    @Suppress("DEPRECATION")
    private fun next(){
        if(resolving||stopped)return
        val info=queue.removeFirstOrNull()?:return
        resolving=true
        runCatching{nsd?.resolveService(info,object:NsdManager.ResolveListener{
            override fun onResolveFailed(i:NsdServiceInfo,errorCode:Int){main.post{resolving=false;next()}}
            override fun onServiceResolved(i:NsdServiceInfo){
                // Prefer IPv4: newer Android may list an IPv6 link-local address first, which a plain socket cannot reach.
                val host=(if(Build.VERSION.SDK_INT>=34)i.hostAddresses.let{all->all.firstOrNull{it is java.net.Inet4Address}?:all.firstOrNull()} else i.host)?.hostAddress
                val google=i.serviceType.contains("androidtvremote2")
                main.post{resolving=false;if(!stopped&&host!=null)found(Service(google,i.serviceName,host,i.port));next()}
            }
        })}.onFailure{resolving=false}
    }
}
