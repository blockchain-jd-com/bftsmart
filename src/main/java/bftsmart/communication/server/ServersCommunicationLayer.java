/**
Copyright (c) 2007-2013 Alysson Bessani, Eduardo Alchieri, Paulo Sousa, and the authors indicated in the @author tags

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/
package bftsmart.communication.server;

import javax.crypto.SecretKey;

import bftsmart.communication.SystemMessage;

/**
 *
 * @author alysson
 */
public interface ServersCommunicationLayer  {

	SecretKey getSecretKey(int id);
	
	void updateConnections();

	ServerConnection updateConnection(int remoteId);

	ServerConnection getConnection(int remoteId);
	
	void startListening();

	default void send(int[] targets, SystemMessage sm, boolean useMAC) {
		send(targets, sm, useMAC, true);
	}

	void send(int[] targets, SystemMessage sm, boolean useMAC, boolean retrySending);

	void shutdown();

	void joinViewReceived();

}
