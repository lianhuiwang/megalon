package org.megalon;

import java.util.HashMap;
import java.util.Map;

import org.megalon.Config.Host;
import org.megalon.Config.ReplicaDesc;

/**
 * This is a singleton class is instantiated if config value "run_client" is 
 * true. It contains data that is shared between clients in a single JVM. For
 * example, if there are two threads in the same JVM writing to the same Megalon
 * database, we want them to share TCP connections to replication servers.
 */
public class ClientSharedData {
	Map<Host,PaxosSocketMultiplexer> replServerSockets = 
		new HashMap<Host,PaxosSocketMultiplexer>();
	Megalon megalon;
	
	public ClientSharedData(Megalon megalon) {
		this.megalon = megalon;
		updateReplServerSockets();
	}
	
	/**
	 * For each replication server that we know about (except this replica),
	 * create a connection (a PaxosSocketMultiplexer).
	 */
	protected void updateReplServerSockets() {
		for(ReplicaDesc replDesc: megalon.config.replicas.values()) {
			if(replDesc == megalon.config.myReplica) {
				continue;
			}
			for(Host host: replDesc.replsrv) {
				if(!replServerSockets.containsKey(host)) {
					replServerSockets.put(host, 
							new PaxosSocketMultiplexer(host, replDesc.name));
				}
			}
		}
	}
	
	/**
	 * Get the PaxosSocketMultiplexer that is connected to a certain remote
	 * replication server.
	 */
	public PaxosSocketMultiplexer getReplSrvSocket(Host host) {
		return replServerSockets.get(host);
	}
}
