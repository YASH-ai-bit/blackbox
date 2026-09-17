package dev.blackbox.core

/** Interface-scoped IPv4 rules. IPv6 is explicitly blocked until routed IPv6 is implemented. */
object FirewallPlan {
    fun filter(chain: String, p: RouterProfile, lan: String, upstream: String?, vpn: String?, clients: List<RouterClient>,counterChain:String?=null): List<String> {
        require(chain.matches(Regex("BB_[A-Fa-f0-9]{8}_F"))); require(validInterface(lan))
        require(upstream==null || validInterface(upstream)); require(vpn==null || validInterface(vpn))
        require(counterChain==null || counterChain.matches(Regex("BB_[A-Fa-f0-9]{8}_C")))
        val accept=counterChain?:"ACCEPT"
        val rules=mutableListOf<String>()
        // Source anti-spoofing. Each known client's policy applies before established-flow acceptance.
        rules+="-A $chain -i $lan ! -s ${p.subnet} -j DROP"
        for(c in clients) {
            require(Regex("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}").matches(c.mac)); Ipv4.number(c.ip)
            when(c.policy) {
                ClientPolicy.PAUSED, ClientPolicy.LAN_ONLY -> { rules+="-A $chain -i $lan -m mac --mac-source ${c.mac} -j DROP"; if(c.leaseCurrent) rules+="-A $chain -o $lan -d ${c.ip} -j DROP" }
                ClientPolicy.VPN_REQUIRED -> {
                    if(vpn==null) { rules+="-A $chain -i $lan -m mac --mac-source ${c.mac} -j DROP"; if(c.leaseCurrent) rules+="-A $chain -o $lan -d ${c.ip} -j DROP" }
                    else { rules+="-A $chain -i $lan -m mac --mac-source ${c.mac} ! -o $vpn -j DROP"; if(c.leaseCurrent) rules+="-A $chain -o $lan -d ${c.ip} ! -i $vpn -j DROP" }
                }
                else -> Unit
            }
        }
        if(p.mode!=RouterMode.OFFLINE) {
            val outputs=if(p.vpn==VpnPolicy.ALL_CLIENTS) listOfNotNull(vpn) else listOfNotNull(upstream,vpn).distinct()
            outputs.forEach { out ->
                rules+="-A $chain -i $lan -o $out -s ${p.subnet} -m conntrack --ctstate NEW,ESTABLISHED,RELATED -j $accept"
                rules+="-A $chain -i $out -o $lan -d ${p.subnet} -m conntrack --ctstate ESTABLISHED,RELATED -j $accept"
            }
        }
        rules+="-A $chain -j DROP"
        return rules
    }
    fun input(chain: String,p: RouterProfile,lan: String, clients: List<RouterClient>): List<String> = buildList {
        add("-A $chain -i $lan -p udp --sport 68 --dport 67 -j ACCEPT")
        clients.filter { it.policy==ClientPolicy.PAUSED }.forEach { add("-A $chain -i $lan -m mac --mac-source ${it.mac} -j DROP") }
        add("-A $chain -i $lan ! -s ${p.subnet} -j DROP")
        add("-A $chain -d ${p.gateway} -p udp --dport 53 -j ACCEPT")
        add("-A $chain -d ${p.gateway} -p tcp --dport 53 -j ACCEPT")
        add("-A $chain -d ${p.gateway} -p icmp --icmp-type echo-request -j ACCEPT")
        p.services.forEach { add("-A $chain -d ${p.gateway} -p ${if(it.udp) "udp" else "tcp"} --dport ${it.port} -j ACCEPT") }
        add("-A $chain -j DROP")
    }
}
