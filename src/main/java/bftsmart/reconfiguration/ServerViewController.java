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
package bftsmart.reconfiguration;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.StringTokenizer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import bftsmart.reconfiguration.util.TOMConfiguration;
import bftsmart.reconfiguration.views.NodeNetwork;
import bftsmart.reconfiguration.views.View;
import bftsmart.reconfiguration.views.ViewStorage;
import bftsmart.tom.core.TOMLayer;
import bftsmart.tom.core.messages.TOMMessage;
import bftsmart.tom.util.BytesUtils;
import bftsmart.tom.util.TOMUtil;

/**
 *
 * @author eduardo
 */
public class ServerViewController extends ViewController implements ReplicaTopology {

	private static final Logger LOGGER = LoggerFactory.getLogger(ServerViewController.class);

	public static final int ADD_SERVER = 0;
	public static final int REMOVE_SERVER = 1;
	public static final int CHANGE_F = 2;

	private int quorumBFT; // ((n + f) / 2) replicas
	private int quorumCFT; // (n / 2) replicas
	private int[] otherProcesses;
	private int[] lastJoinStet;
	private List<TOMMessage> updates = new LinkedList<TOMMessage>();
	private TOMLayer tomLayer;
	// protected View initialView;

	public ServerViewController(TOMConfiguration config, ViewStorage viewSotrage) {
		super(config, viewSotrage);
	}

	public void setTomLayer(TOMLayer tomLayer) {
		this.tomLayer = tomLayer;
	}

	@Override
	public int[] getCurrentViewOtherAcceptors() {
		return this.otherProcesses;
	}

	public boolean hasUpdates() {
		return !this.updates.isEmpty();
	}

	public void enqueueUpdate(TOMMessage up) {
		ReconfigureRequest request = (ReconfigureRequest) TOMUtil.getObject(up.getContent());
		if (TOMUtil.verifySignature(getStaticConf().getRSAPublicKey(request.getSender()),
				BytesUtils.getBytes(request.toString()), request.getSignature())) {
//			if (request.getSender() == getStaticConf().getTTPId()) {
//				this.updates.add(up);
//			} else {
			boolean add = true;
			Iterator<Integer> it = request.getProperties().keySet().iterator();
			while (it.hasNext()) {
				int key = it.next();
				String value = request.getProperties().get(key);
				if (key == ADD_SERVER) {
					StringTokenizer str = new StringTokenizer(value, ":");
					if (str.countTokens() > 2) {
						int id = Integer.parseInt(str.nextToken());
						if (id != request.getSender()) {
//								add = false;
						}
					} else {
						add = false;
					}
				} else if (key == REMOVE_SERVER) {
					if (isCurrentViewMember(Integer.parseInt(value))) {
						if (Integer.parseInt(value) != request.getSender()) {
//								add = false;
						}
					} else {
						add = false;
					}
				} else if (key == CHANGE_F) {
					add = false;
				}
			}
			if (add) {
				this.updates.add(up);
			}
//			}//End of: if (request.getSender() == getStaticConf().getTTPId()) {}else{
		}
	}

	public byte[] executeUpdates(int cid) {

		List<Integer> jSet = new LinkedList<>();
		List<Integer> rSet = new LinkedList<>();
		int f = -1;

		List<String> jSetInfo = new LinkedList<>();
		Map<Integer, NodeNetwork> currentAddresses = getCurrentView().getAddresses();

		for (int i = 0; i < updates.size(); i++) {
			ReconfigureRequest request = (ReconfigureRequest) TOMUtil.getObject(updates.get(i).getContent());
			Iterator<Integer> it = request.getProperties().keySet().iterator();

			while (it.hasNext()) {
				int key = it.next();
				String value = request.getProperties().get(key);

				if (key == ADD_SERVER) {
					StringTokenizer str = new StringTokenizer(value, ":");
					if (str.countTokens() > 2) {
						int id = Integer.parseInt(str.nextToken());
						if (!currentAddresses.containsKey(id) && !contains(id, jSet)) {
							jSetInfo.add(value);
							jSet.add(id);
							String host = str.nextToken();
							int port = Integer.valueOf(str.nextToken());
							boolean secure = Boolean.valueOf(str.nextToken());
							try {
								int monitorPort = Integer.valueOf(str.nextToken());
								boolean monitorSecure = Boolean.valueOf(str.nextToken());
								this.getStaticConf().addHostInfo(id, host, port, monitorPort, secure, monitorSecure);
								this.getStaticConf().getOuterHostConfig().add(id, host, port, monitorPort, secure, monitorSecure);
							} catch (Exception e) {
								this.getStaticConf().addHostInfo(id, host, port, -1, secure, false);

							}
							currentAddresses.put(id, new NodeNetwork(host, port, -1, secure, false));
						}
					}
				} else if (key == REMOVE_SERVER) {
					int id = Integer.parseInt(value);
					if (currentAddresses.containsKey(id)) {
						rSet.add(id);
						currentAddresses.remove(id);
						this.getStaticConf().getOuterHostConfig().del(Integer.parseInt(value));
					}
				} else if (key == CHANGE_F) {
					f = Integer.parseInt(value);
				}
			}

		}
		// ret = reconfigure(updates.get(i).getContent());
		return reconfigure(jSetInfo, jSet, rSet, f, cid);
	}

	private boolean contains(int id, List<Integer> list) {
		for (int i = 0; i < list.size(); i++) {
			if (list.get(i).intValue() == id) {
				return true;
			}
		}
		return false;
	}

	private byte[] reconfigure(List<String> jSetInfo, List<Integer> jSet, List<Integer> rSet, int f, int cid) {
		// ReconfigureRequest request = (ReconfigureRequest) TOMUtil.getObject(req);
		// Hashtable<Integer, String> props = request.getProperties();
		// int f = Integer.valueOf(props.get(CHANGE_F));
		lastJoinStet = new int[jSet.size()];
		int[] nextV = new int[currentView.getN() + jSet.size() - rSet.size()];
		int p = 0;

		boolean forceLC = false;
		for (int i = 0; i < jSet.size(); i++) {
			lastJoinStet[i] = jSet.get(i);
			nextV[p++] = jSet.get(i);
		}

		for (int i = 0; i < currentView.getProcesses().length; i++) {
			if (!contains(currentView.getProcesses()[i], rSet)) {
				nextV[p++] = currentView.getProcesses()[i];
			} else if (tomLayer.execManager.getCurrentLeader() == currentView.getProcesses()[i]) {
				// 如果要删除的参与方集合中包含了当前的领导者，则需要强制触发领导者切换流程；
				forceLC = true;
			}
		}

		if (f < 0) {
			f = currentView.getF();
		}

		NodeNetwork[] addresses = new NodeNetwork[nextV.length];

		for (int i = 0; i < nextV.length; i++) {
			int processId = nextV[i];
			addresses[i] = getStaticConf().getRemoteAddress(processId);
			NodeNetwork nodeNetwork = currentView.getAddress(processId);
			if (nodeNetwork != null) {
				addresses[i].setMonitorPort(nodeNetwork.getMonitorPort());
			}
			LOGGER.info("I am {}, network[{}] -> {} !", this.getStaticConf().getProcessId(), processId, addresses[i]);
		}

//		View newV = new View(currentView.getId() + 1, nextV, f, addresses);

		// f的值需要动态计算
		View newV = new View(currentView.getId() + 1, nextV, (nextV.length - 1) / 3, addresses);

		LOGGER.info("I am proc {}, new view: {}", this.getStaticConf().getProcessId(), newV);
		LOGGER.info("I am proc {}, installed on CID: {}", this.getStaticConf().getProcessId(), cid);
		LOGGER.info("I am proc {}, lastJoinSet: {}", this.getStaticConf().getProcessId(), jSet);

		// TODO:Remove all information stored about each process in rSet
		// processes execute the leave!!!
		reconfigureTo(newV);

		LOGGER.info("I am proc {}, after reconfigure ,currview = {}", this.getStaticConf().getProcessId(),
				this.currentView);

		if (forceLC) {

			// TODO: Reactive it and make it work
			LOGGER.info("Shortening LC timeout");
//			tomLayer.requestsTimer.stopTimer();
			tomLayer.requestsTimer.setShortTimeout(3000);
//			tomLayer.requestsTimer.startTimer();
			// tomLayer.triggerTimeout(new LinkedList<TOMMessage>());

		}

		LOGGER.info("I am proc {}, I will send ReconfigureReply!", this.getStaticConf().getProcessId());

		List<NodeNetwork> addressesTemp = new ArrayList<>();

		for (int i = 0; i < newV.getProcesses().length; i++) {
			int cpuId = newV.getProcesses()[i];

			NodeNetwork nodeNetwork = newV.getAddress(cpuId);
//			InetSocketAddress inetSocketAddress = newV.getAddress(cpuId);

			if (nodeNetwork.getHost().equals("0.0.0.0")) {
				// proc docker env
				String host = this.getStaticConf().getOuterHostConfig().getHost(cpuId);
				NodeNetwork nodeNetworkNew = new NodeNetwork(host, nodeNetwork.getConsensusPort(), -1, nodeNetwork.isConsensusSecure(), false);
				LOGGER.info("I am proc {}, tempSocketAddress.getAddress().getHostAddress() = {}",
						this.getStaticConf().getProcessId(), host);
				addressesTemp.add(nodeNetworkNew);
			} else {
				addressesTemp.add(new NodeNetwork(nodeNetwork.getHost(), nodeNetwork.getConsensusPort(), -1, nodeNetwork.isConsensusSecure(), false));
			}
		}

		View replyView = new View(newV.getId(), newV.getProcesses(), newV.getF(),
				addressesTemp.toArray(new NodeNetwork[addressesTemp.size()]));

		LOGGER.info("I am proc {}, I adjust reply view, reply view = {}", this.getStaticConf().getProcessId(),
				replyView);

		// 更新 TOMConfiguration
		staticConf.updateConfiguration(replyView.getProcesses());

		return TOMUtil.getBytes(new ReconfigureReply(replyView, jSetInfo.toArray(new String[0]), cid,
				tomLayer.execManager.getCurrentLeader()));
	}

	public TOMMessage[] clearUpdates() {
		TOMMessage[] ret = new TOMMessage[updates.size()];
		for (int i = 0; i < updates.size(); i++) {
			ret[i] = updates.get(i);
		}
		updates.clear();
		return ret;
	}

	@Override
	public boolean isInLastJoinSet(int id) {
		if (lastJoinStet != null) {
			for (int i = 0; i < lastJoinStet.length; i++) {
				if (lastJoinStet[i] == id) {
					return true;
				}
			}

		}
		return false;
	}

	@Override
	public final void reconfigureTo(View newView) {

		// 防止reconfig过程中其他线程比如view sync 更改视图
		if (newView.getId() < this.getCurrentView().getId()) {
			LOGGER.info("reconfigureTo error, new view id little than loacal view id!");
			return;
		}

		int processId = this.getStaticConf().getProcessId();
		NodeNetwork localNodeNetwork = this.getCurrentView().getAddress(processId);
		this.currentView = newView;
		LOGGER.info("I am {}, my new current view = {} !!!", processId, this.currentView);
		getViewStore().storeView(this.currentView);
		if (newView.isMember(getStaticConf().getProcessId())) {
			this.currentView.setAddresses(processId, localNodeNetwork);

			// membro da view atual
			otherProcesses = new int[currentView.getProcesses().length - 1];
			int c = 0;
			for (int i = 0; i < currentView.getProcesses().length; i++) {
				if (currentView.getProcesses()[i] != getStaticConf().getProcessId()) {
					otherProcesses[c++] = currentView.getProcesses()[i];
				}
			}

			// error use of quorum , refactor later
			this.quorumBFT = (int) Math.ceil((this.currentView.getN() + this.currentView.getF()) / 2);
			this.quorumCFT = (int) Math.ceil(this.currentView.getN() / 2);
		} else if (this.currentView != null && this.currentView.isMember(getStaticConf().getProcessId())) {
			// TODO: Left the system in newView -> LEAVE
			// CODE for LEAVE
		} else {
			// TODO: Didn't enter the system yet

		}
	}

	/*
	 * public int getQuorum2F() { return quorum2F; }
	 */

	@Override
	public int getQuorum() {
		return getStaticConf().isBFT() ? quorumBFT : quorumCFT;
	}

	public void addHostInfo(int procId, String host, int consensusPort, int monitorPort, boolean secure, boolean monitorSecure) {
		staticConf.addHostInfo(procId, host, consensusPort, monitorPort, secure, monitorSecure);
		staticConf.getOuterHostConfig().add(procId, host, consensusPort, monitorPort, secure, monitorSecure);
	}
}
