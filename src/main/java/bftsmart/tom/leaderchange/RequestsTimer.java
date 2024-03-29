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
package bftsmart.tom.leaderchange;

import java.util.Hashtable;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.ListIterator;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.TreeSet;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import bftsmart.tom.core.messages.ForwardedMessage;
import org.slf4j.LoggerFactory;

import bftsmart.communication.ServerCommunicationSystem;
import bftsmart.consensus.Consensus;
import bftsmart.reconfiguration.ReplicaTopology;
import bftsmart.tom.core.TOMLayer;
import bftsmart.tom.core.messages.TOMMessage;
import bftsmart.tom.server.defaultservices.DefaultRecoverable;

/**
 * This thread serves as a manager for all timers of pending requests.
 *
 */
public class RequestsTimer {

//    private Timer timer = new Timer("request timer");
//    private RequestTimerTask rtTask = null;
	private TOMLayer tomLayer; // TOM layer
	private long timeout;
	private long shortTimeout;
	private long stoptimeout;
	private TreeSet<TOMMessage> watched = new TreeSet<TOMMessage>();
	private ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();

	private boolean enabled = true;

	private ServerCommunicationSystem communication; // Communication system between replicas
	private ReplicaTopology controller; // Reconfiguration manager

	private Hashtable<Integer, Timer> stopTimers = new Hashtable<>();

	private ScheduledExecutorService requestsTimer = null;

    private volatile ScheduledFuture<?> taskFuture;

	private static final org.slf4j.Logger LOGGER = LoggerFactory.getLogger(RequestsTimer.class);

	// private Storage st1 = new Storage(100000);
	// private Storage st2 = new Storage(10000);
	/**
	 * Creates a new instance of RequestsTimer
	 * 
	 * @param tomLayer TOM layer
	 */
	public RequestsTimer(TOMLayer tomLayer, ServerCommunicationSystem communication, ReplicaTopology controller) {
		this.tomLayer = tomLayer;

		this.communication = communication;
		this.controller = controller;

		this.stoptimeout = this.controller.getStaticConf().getStopMsgTimeout();
		this.timeout = this.controller.getStaticConf().getRequestTimeout();
		this.shortTimeout = -1;

		// 请求定时器初次启动时延迟设置为4秒
		startTimer(4000);
	}

	public void setShortTimeout(long shortTimeout) {
		this.shortTimeout = shortTimeout;
	}

	public void setTimeout(long timeout) {
		this.timeout = timeout;
	}

	public long getTimeout() {
		return timeout;
	}

	public void startTimer(int delay) {
	    if (taskFuture != null) {
	        return;
        }
		requestsTimer = Executors.newSingleThreadScheduledExecutor();
		taskFuture = requestsTimer.scheduleWithFixedDelay(new RequestsTimeoutTask(), delay,
				this.timeout, TimeUnit.MILLISECONDS);
	}

	public void stopTimer() {
		if (requestsTimer != null) {
			requestsTimer.shutdownNow();
		}
		requestsTimer = null;
		taskFuture = null;
	}

    private synchronized void cancelTask() {
        ScheduledFuture<?> future = taskFuture;
        taskFuture = null;
        if (future != null) {
            future.cancel(true);

            try {
                requestsTimer.shutdown();
            } catch (Exception e) {
            }

            LOGGER.debug("I am proc {}, quit the requests timeout check task!", tomLayer.getCurrentProcessId());
        }
    }


    private class RequestsTimeoutTask implements Runnable {

        @Override
		public void run() {

			// 需要判断所有连接是否已经成功建立,即状态传输是否完成
			if (!tomLayer.isConnectRemotesOK()) {
				return;
			}

			LinkedList<TOMMessage> pendingRequests = new LinkedList<TOMMessage>();

			rwLock.readLock().lock();

			for (Iterator<TOMMessage> iter = watched.iterator(); iter.hasNext();) {
				TOMMessage request = iter.next();
				if ((System.currentTimeMillis() - request.receptionTime) > timeout) {
					pendingRequests.add(request);
				} else {
					break;
				}
			}

			rwLock.readLock().unlock();

			// 存在超时的交易请求
			if (pendingRequests.size() !=0) {
				if (tomLayer.isLeader()) {
					tomLayer.heartBeatTimer.stopAll();
					LOGGER.info("I am proc {}, tx requests timeout! Set leader inactive, wait for trigger lc!", tomLayer.getCurrentProcessId());
					tomLayer.heartBeatTimer.setLeaderInactived();
					cancelTask();
				} else {
					forwardRequestsToLeader(pendingRequests);
				}
			}
		}// End of : public void run();
	}//

	private void forwardRequestsToLeader(LinkedList<TOMMessage> pendingRequests) {
		for (ListIterator<TOMMessage> li = pendingRequests.listIterator(); li.hasNext(); ) {
                TOMMessage request = li.next();
                communication.send(new ForwardedMessage(this.controller.getStaticConf().getProcessId(), request), this.tomLayer.execManager.getCurrentLeader());
		}
	}
//    public void startTimer() {
//        if (rtTask == null) {
//            long t = (shortTimeout > -1 ? shortTimeout : timeout);
//            //shortTimeout = -1;
//            rtTask = new RequestTimerTask();
//            if (controller.getCurrentViewN() > 1) timer.schedule(rtTask, t);
//        }
//    }

//    public void stopTimer() {
//        if (rtTask != null) {
//            rtTask.cancel();
//            rtTask = null;
//        }
//    }

//    public void Enabled(boolean phase) {
//
//        enabled = phase;
//    }
//
//    public boolean isEnabled() {
//    	return enabled;
//    }

	/**
	 * Creates a timer for the given request
	 * 
	 * @param request Request to which the timer is being createf for
	 */
	public void watch(TOMMessage request) {
		// long startInstant = System.nanoTime();
		rwLock.writeLock().lock();
		watched.add(request);
//        System.out.println("request client  " + request.getSender() + ", req seq  " + request.getSequence() + ", watch at  " + System.currentTimeMillis() + "\r\n");
//        if (watched.size() >= 1 && enabled) startTimer();
		rwLock.writeLock().unlock();
	}

	/**
	 * Cancels a timer for a given request
	 * 
	 * @param request Request whose timer is to be canceled
	 */
	public void unwatch(TOMMessage request) {
		// long startInstant = System.nanoTime();
		rwLock.writeLock().lock();
		watched.remove(request);
//        System.out.println("request client  " + request.getSender() + ", req seq  " + request.getSequence() + ", unwatch at  " + System.currentTimeMillis() + "\r\n");
//        if (watched.remove(request) && watched.isEmpty()) stopTimer();
		rwLock.writeLock().unlock();
	}

	/**
	 * Cancels all timers for all messages
	 */
	public void clearAll() {
//        TOMMessage[] requests = new TOMMessage[watched.size()];
		rwLock.writeLock().lock();

		watched.clear();
//
//        watched.toArray(requests);
//
//        for (TOMMessage request : requests) {
//
//            if (request != null && watched.remove(request) && watched.isEmpty() && rtTask != null) {
//                rtTask.cancel();
//                rtTask = null;
//            }
//        }
		rwLock.writeLock().unlock();
	}

	public DefaultRecoverable getDefaultExecutor() {
		return (DefaultRecoverable) tomLayer.getDeliveryThread().getReceiver().getExecutor();
	}

	public Consensus getCurrConsensus() {
		if (tomLayer.getInExec() == -1) {
			return tomLayer.getExecManager().getConsensus(tomLayer.getLastExec());
		} else {
			return tomLayer.getExecManager().getConsensus(tomLayer.getInExec());
		}
	}

	public void run_lc_protocol(LeaderRegencyPropose regencyPropose) {

		long t = (shortTimeout > -1 ? shortTimeout : timeout);

		// System.out.println("(RequestTimerTask.run) I SOULD NEVER RUN WHEN THERE IS NO
		// TIMEOUT");

		LinkedList<TOMMessage> pendingRequests = new LinkedList<TOMMessage>();

		rwLock.readLock().lock();

		for (Iterator<TOMMessage> iter = watched.iterator(); iter.hasNext();) {
			TOMMessage request = iter.next();
			if ((System.currentTimeMillis() - request.receptionTime) > t) {
//            if ((request.receptionTime + System.currentTimeMillis()) > t) {
//                System.out.println("real consensus message timeout!!!!!!!!\r\n");
//                System.out.println("request client  " + request.getSender() + ", req seq  " + request.getSequence() + ",  consensus message timeout at  " + System.currentTimeMillis() + "\r\n");
				pendingRequests.add(request);
			} else {
				break;
			}
		}

		rwLock.readLock().unlock();

		tomLayer.getSynchronizer().triggerTimeout(regencyPropose, pendingRequests);

//        if (!pendingRequests.isEmpty()) {
//            //when the first timeout occurs, no need to roll back, has one opportunity, waiting for the arrival of a timeout message
//            for (ListIterator<TOMMessage> li = pendingRequests.listIterator(); li.hasNext(); ) {
//                TOMMessage request = li.next();
//                if (!request.timeout) {
//
//                    request.signed = request.serializedMessageSignature != null;
//                    tomLayer.forwardRequestToLeader(request);
//                    request.timeout = true;
//                    li.remove();
//                }
//            }
//
//            if (!pendingRequests.isEmpty()) {
//                System.out.println("Timeout for messages: " + pendingRequests);
//
//                //When the second timeout occurs, need not roll back pre compute hash operation
////                if (getCurrConsensus() != null) {
////                    Epoch epoch = getCurrConsensus().getLastEpoch();
////
////                    if (getCurrConsensus().getPrecomputed() && !getCurrConsensus().getPrecomputeCommited()) {
////                        if (epoch != null && epoch.getBatchId() != null) {
////                            System.out.println("The second time requests timeout occurs, roll back precompute hash operation!");
////                            getDefaultExecutor().preComputeAppRollback(epoch.getBatchId());
////                            getCurrConsensus().setPrecomputeCommited(false);
////                            getCurrConsensus().setPrecomputed(false);
////                            getCurrConsensus().setSecondTimeout(true);
////                        }
////                    }
////                }
//                //Logger.debug = true;
//                //tomLayer.requestTimeout(pendingRequests);
//                //if (reconfManager.getStaticConf().getProcessId() == 4) Logger.debug = true;
//                tomLayer.getSynchronizer().triggerTimeout(pendingRequests);
//            }
//            else {
//                rtTask = new RequestTimerTask();
//                timer.schedule(rtTask, t);
//            }
//        } else {
//            rtTask = null;
//            timer.purge();
//        }

	}

	public void setSTOP(int regency, LCMessage stop) {

		stopSTOP(regency);

		SendStopTask stopTask = new SendStopTask(stop);
		Timer stopTimer = new Timer("Stop message");

		stopTimer.schedule(stopTask, stoptimeout);

		stopTimers.put(regency, stopTimer);

	}

	public void stopAllSTOPs() {
		Iterator stops = getTimers().iterator();
		while (stops.hasNext()) {
			stopSTOP((Integer) stops.next());
		}
	}

	public void stopSTOP(int regency) {

		Timer stopTimer = stopTimers.remove(regency);
		if (stopTimer != null)
			stopTimer.cancel();

	}

	public Set<Integer> getTimers() {

		return ((Hashtable<Integer, Timer>) stopTimers.clone()).keySet();

	}

	public void shutdown() {
//        timer.cancel();
		stopAllSTOPs();
		stopTimer();
		LOGGER.info("RequestsTimer stopped.");

	}

//    class RequestTimerTask extends TimerTask {
//
//        @Override
//        /**
//         * This is the code for the TimerTask. It executes the timeout for the first
//         * message on the watched list.
//         */
//        public void run() {
//
//            int[] myself = new int[1];
//            myself[0] = controller.getStaticConf().getProcessId();
//
//            communication.send(myself, new LCMessage(-1, TOMUtil.TRIGGER_LC_LOCALLY, -1, null));
//
//        }
//    }

	class SendStopTask extends TimerTask {

		private LCMessage stop;

		public SendStopTask(LCMessage stop) {
			this.stop = stop;
		}

		/**
		 * This is the code for the TimerTask. It sends a STOP message to the other
		 * replicas
		 */
		@Override
		public void run() {

			LOGGER.info("(SendStopTask.run) I am proc {}; Re-transmitting STOP message to install regency {}",
					controller.getStaticConf().getProcessId(), stop.getReg());
			communication.send(controller.getCurrentViewOtherAcceptors(), this.stop);

			setSTOP(stop.getReg(), stop); // repeat
		}

	}
}
