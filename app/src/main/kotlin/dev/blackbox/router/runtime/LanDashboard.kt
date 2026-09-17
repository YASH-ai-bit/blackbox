package dev.blackbox.router.runtime

import org.json.JSONObject
import java.io.Closeable
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Read-only, bounded HTTP service, bound exclusively to the private gateway address. */
class LanDashboard(gateway:String,port:Int,private val state:()->LiveRouter):Closeable {
    private val server=ServerSocket().apply { reuseAddress=true;bind(InetSocketAddress(InetAddress.getByName(gateway),port),8) }
    private val workers=ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,ArrayBlockingQueue(8))
    private val sockets=java.util.concurrent.ConcurrentHashMap.newKeySet<Socket>()
    private val acceptor=thread(name="blackbox-lan-http",isDaemon=true) {
        while(!server.isClosed) {
            val socket=try {server.accept()} catch(_:Exception){break}
            sockets.add(socket)
            try { workers.execute { try {serve(socket)} finally {sockets.remove(socket)} } }
            catch(_:java.util.concurrent.RejectedExecutionException) { sockets.remove(socket);socket.close() }
        }
    }
    private fun serve(socket:Socket) = socket.use { s ->
        s.soTimeout=3000
        try {
            val input=s.getInputStream();val header=StringBuilder()
            while(header.length<8192 && !header.endsWith("\r\n\r\n")) {
                val b=input.read();if(b<0)return@use;header.append(b.toChar())
            }
            if(!header.endsWith("\r\n\r\n"))return@use
            val request=header.toString().substringBefore("\r\n").split(' ')
            val path=request.getOrNull(1)
            val ok=request.size==3 && request[0]=="GET" && path in setOf("/","/status.json")
            val live=state()
            // No secrets, commands, client identifiers or administrative operations leave the phone.
            val json=JSONObject().put("name","BLACKBOX").put("status",live.status.name)
                .put("gateway",live.profile?.gateway).put("mode",live.profile?.mode?.name)
                .put("vpn",live.profile?.vpn?.name).put("clients",live.clients.count {it.online})
                .put("downloadBytesPerSecond",live.downBps).put("uploadBytesPerSecond",live.upBps)
            val body=if(!ok) "Not found" else if(path=="/status.json") json.toString() else HTML
            val bytes=body.toByteArray(Charsets.UTF_8)
            val type=if(path=="/status.json") "application/json" else "text/html"
            val response="HTTP/1.1 ${if(ok) "200 OK" else "404 Not Found"}\r\nContent-Type: $type; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nContent-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'\r\n\r\n"
            s.getOutputStream().apply {write(response.toByteArray(Charsets.US_ASCII));write(bytes);flush()}
        } catch(_:java.io.IOException) { /* A slow/disconnected client does not affect routing. */ }
    }
    override fun close() {server.close();sockets.forEach {runCatching {it.close()}};workers.shutdownNow();acceptor.interrupt()}
    companion object {
        private val HTML="""<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>BLACKBOX</title>
            <style>body{background:#0b100e;color:#e5eee7;font:17px system-ui;max-width:620px;margin:8vh auto;padding:24px}h1{letter-spacing:.18em}small{color:#b8ee70}article{background:#17201a;border:1px solid #34412e;border-radius:20px;padding:24px;margin:24px 0}dl{display:grid;grid-template-columns:1fr 1fr;gap:18px}dd{margin:0;color:#b8ee70}footer{color:#a0afa5;font-size:14px}</style>
            <small>YOUR PORTABLE NETWORK</small><h1>BLACKBOX</h1><article><h2 id="status">Connecting…</h2><dl><dt>Gateway</dt><dd id="gateway"></dd><dt>Mode</dt><dd id="mode"></dd><dt>VPN policy</dt><dd id="vpn"></dd><dt>Clients</dt><dd id="clients"></dd><dt>Download</dt><dd id="down"></dd><dt>Upload</dt><dd id="up"></dd></dl></article><footer>This dashboard works offline. Change settings in the BLACKBOX Android app. Traffic totals include local transfers.</footer>
            <script>async function refresh(){try{const s=await(await fetch('/status.json',{cache:'no-store'})).json();for(const k of ['status','gateway','mode','vpn','clients'])document.getElementById(k).textContent=s[k];document.getElementById('down').textContent=(s.downloadBytesPerSecond*8/1e6).toFixed(2)+' Mbps';document.getElementById('up').textContent=(s.uploadBytesPerSecond*8/1e6).toFixed(2)+' Mbps'}catch(e){document.getElementById('status').textContent='Disconnected'}}refresh();setInterval(refresh,5000)</script></html>""".trimIndent()
    }
}
