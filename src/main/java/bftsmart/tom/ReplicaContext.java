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
package bftsmart.tom;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import bftsmart.communication.ServerCommunicationSystem;
import bftsmart.reconfiguration.ServerViewController;
import bftsmart.reconfiguration.util.TOMConfiguration;
import bftsmart.reconfiguration.views.View;
import bftsmart.tom.core.TOMLayer;

/**
 * This class contains information related to the replica.
 * 
 * @author Alysson Bessani
 */
public class ReplicaContext {

	private static Logger LOGGER = LoggerFactory.getLogger(ReplicaContext.class);

	private ServerViewController SVController;
	private TOMLayer tomLayer;

	public ReplicaContext(TOMLayer tomLayer, ServerViewController SVController) {
		this.tomLayer = tomLayer;
		this.SVController = SVController;
	}

	/**
	 * Returns the controller of the replica's view
	 * 
	 * @return The controller of the replica's view
	 */
	public ServerViewController getSVController() {
		return SVController;
	}

	// TODO: implement a method that allow the replica to send a message with
	// total order to all other replicas

	/**
	 * Returns the static configuration of this replica.
	 * 
	 * @return the static configuration of this replica
	 */
	public TOMConfiguration getStaticConfiguration() {
		return SVController.getStaticConf();
	}

	/**
	 * Returns the current view of the replica group.
	 * 
	 * @return the current view of the replica group.
	 */
	public View getCurrentView() {
		return SVController.getCurrentView();
	}

	public ServerCommunicationSystem getServerCommunicationSystem() {
		return tomLayer.getCommunication();
	}

	public void shutdown() {
		try {
			tomLayer.shutdown();
			tomLayer.join();
			tomLayer.getDeliveryThread().join();
		} catch (Exception e) {
			LOGGER.warn("Error occurred while shuting down the replica! --[RepliaId="
					+ SVController.getCurrentProcessId() + "] " + e.getMessage(), e);
		}
	}

	public void start() {
		tomLayer.getCommunication().getAcceptor().start();
	}

	public TOMLayer getTOMLayer() {
		return tomLayer;
	}
}
