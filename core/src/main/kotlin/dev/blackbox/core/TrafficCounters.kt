package dev.blackbox.core

data class ClientTraffic(val down:Long=0,val up:Long=0) {
    operator fun plus(other:ClientTraffic)=ClientTraffic(down+other.down,up+other.up)
}
object TrafficCounters {
    /** Parses byte counters from an owned chain, never packet payloads. */
    fun parse(output:String):Map<String,ClientTraffic> {
        val result=mutableMapOf<String,ClientTraffic>()
        val pattern=Regex("^\\s*\\d+\\s+(\\d+)\\s+.*?/\\* bb_(down|up)_([a-f0-9]{12}) \\*/")
        output.lineSequence().forEach {line->pattern.find(line)?.let {m->
            val mac=m.groupValues[3].chunked(2).joinToString(":");val bytes=m.groupValues[1].toLongOrNull()?:return@let
            val old=result[mac]?:ClientTraffic()
            result[mac]=if(m.groupValues[2]=="down")old.copy(down=bytes) else old.copy(up=bytes)
        }}
        return result
    }
}
