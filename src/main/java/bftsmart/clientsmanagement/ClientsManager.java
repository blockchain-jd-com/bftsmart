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
package bftsmart.clientsmanagement;

import bftsmart.communication.ServerCommunicationSystem;
import bftsmart.reconfiguration.ViewTopology;
import bftsmart.tom.core.messages.TOMMessage;
import bftsmart.tom.leaderchange.RequestsTimer;
import bftsmart.tom.server.RequestVerifier;
import org.slf4j.LoggerFactory;
import utils.codec.Base58Utils;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;


/**
 *
 * @author alysson
 */
public class ClientsManager {

    private ViewTopology controller;
    private RequestsTimer timer;
    private HashMap<Integer, ClientData> clientsData = new HashMap<Integer, ClientData>();
    private RequestVerifier verifier;

    private AtomicLong clientDatasTotal = new AtomicLong(0);

    private ReentrantLock clientsLock = new ReentrantLock();
    private static final org.slf4j.Logger LOGGER = LoggerFactory.getLogger(ClientsManager.class);

    public ClientsManager(ViewTopology controller, RequestsTimer timer, RequestVerifier verifier) {
        this.controller = controller;
        this.timer = timer;
        this.verifier = verifier;
    }

    /**
     * Return the total count of requests from all client data lists.
     *
     * @param
     * @return the total number
     */
    public long getClientDatasTotal() {
        long total;

        clientsLock.lock();
        total =  clientDatasTotal.get();
        clientsLock.unlock();
//        System.out.println("I am proc " + this.controller.getStaticConf().getProcessId() + " , the client data total is " + total);
        return total;
    }
    /**
     * We are assuming that no more than one thread will access
     * the same clientData during creation.
     *
     *
     * @param clientId
     * @return the ClientData stored on the manager
     */
    public ClientData getClientData(int clientId) {
        clientsLock.lock();
        /******* BEGIN CLIENTS CRITICAL SECTION ******/
        ClientData clientData = clientsData.get(clientId);

        if (clientData == null) {
           LOGGER.debug("(ClientsManager.getClientData) Creating new client data, client id = {}", clientId);

            //******* EDUARDO BEGIN **************//
            clientData = new ClientData(clientId,
                    (controller.getStaticConf().isUseSignatures())
                    ? controller.getStaticConf().getRSAPublicKey(clientId)
                    : null);
            //******* EDUARDO END **************//
            clientsData.put(clientId, clientData);
        }

        /******* END CLIENTS CRITICAL SECTION ******/
        clientsLock.unlock();

        return clientData;
    }

    /**
     * Get pending requests in a fair way (one request from each client
     * queue until the max number of requests is obtained).
     *
     * @return the set of all pending requests of this system
     */
    public RequestList getPendingRequests() {
        RequestList allReq = new RequestList();

        clientsLock.lock();
        try {
            /******* BEGIN CLIENTS CRITICAL SECTION ******/

            // id为负的消息(Reconfig 消息类型)单独打包
            Set<Integer> ids = clientsData.keySet();
            for(Integer id : ids) {
                if (id < 0) {
                    ClientData clientData = clientsData.get(id);
                    RequestList clientPendingRequests = clientData.getPendingRequests();
                    clientData.clientLock.lock();
                    TOMMessage request = clientPendingRequests.size() > 0 ? clientPendingRequests.get(0) : null;
                    clientData.clientLock.unlock();
                    if (request != null) {
                        if (!request.alreadyProposed) {
                            //this client have pending message
                            request.alreadyProposed = true;
                            allReq.addLast(request);
                            return allReq;
                        }
                    }
                }
            }

            Set<Entry<Integer, ClientData>> clientsEntrySet = clientsData.entrySet();

            for (int i = 0; true; i++) {
                Iterator<Entry<Integer, ClientData>> it = clientsEntrySet.iterator();
                int noMoreMessages = 0;

                while (it.hasNext()
                        && allReq.size() < controller.getStaticConf().getMaxBatchSize()
                        && noMoreMessages < clientsEntrySet.size()) {

                    ClientData clientData = it.next().getValue();
                    RequestList clientPendingRequests = clientData.getPendingRequests();

                    clientData.clientLock.lock();
                    /******* BEGIN CLIENTDATA CRITICAL SECTION ******/
                    TOMMessage request = (clientPendingRequests.size() > i) ? clientPendingRequests.get(i) : null;

                    /******* END CLIENTDATA CRITICAL SECTION ******/
                    clientData.clientLock.unlock();

                    if (request != null) {
                        if (!request.alreadyProposed) {
                            //this client have pending message
                            request.alreadyProposed = true;
                            allReq.addLast(request);
                        }
                    } else {
                        //this client don't have more pending requests
                        noMoreMessages++;
                    }
                }

                if (allReq.size() == controller.getStaticConf().getMaxBatchSize() ||
                        noMoreMessages == clientsEntrySet.size()) {

                    break;
                }
            }

            return allReq;
        } finally {
            /******* END CLIENTS CRITICAL SECTION ******/
            clientsLock.unlock();
        }
    }

    /**
     * We've implemented some protection for individual client
     * data, but the clients table can change during the operation.
     *
     * @return true if there are some pending requests and false otherwise
     */
    public boolean havePendingRequests() {
        boolean havePending = false;

        clientsLock.lock();
        /******* BEGIN CLIENTS CRITICAL SECTION ******/        
        
        Iterator<Entry<Integer, ClientData>> it = clientsData.entrySet().iterator();

        while (it.hasNext() && !havePending) {
            ClientData clientData = it.next().getValue();
            
            clientData.clientLock.lock();
            RequestList reqs = clientData.getPendingRequests();
            if (!reqs.isEmpty()) {
                for(TOMMessage msg:reqs) {
                    if(!msg.alreadyProposed) {
                        havePending = true;
                        break;
                    }
                }
            }
            clientData.clientLock.unlock();
        }

        /******* END CLIENTS CRITICAL SECTION ******/
        clientsLock.unlock();
        return havePending;
    }

    /**
     * Clear obsolete requests from client data lists.
     *
     * @param void
     * @return void
     */
    public void clearObsoleteRequests() {
        clientsLock.lock();

        try {
            Iterator<Entry<Integer, ClientData>> it = clientsData.entrySet().iterator();

            while (it.hasNext()) {
                ClientData clientData = it.next().getValue();
                clientData.clientLock.lock();
                try {
                    RequestList reqs = clientData.getPendingRequests();
                    if (!reqs.isEmpty()) {
                        for(TOMMessage msg:reqs) {
                            if((System.currentTimeMillis() - msg.receptionTime) < (this.controller.getStaticConf().getRequestTimeout()) ) {
                                break;
                            }
                            else {
                                clientData.removePendingRequest(msg);
                                timer.unwatch(msg);
                                LOGGER.error("(ClientsManager.clearObsoleteRequests) I am proc {}, the client data total is too big, need clear!", this.controller.getStaticConf().getProcessId());
                            }
                        }
                    }
                } finally {
                    clientData.clientLock.unlock();
                }
            }
        } finally {
            clientsLock.unlock();
        }
    }

    /**
     * Verifies if some reqId is pending.
     *
     * @param reqId the request identifier
     * @return true if the request is pending
     */
    public boolean isPending(int reqId) {
        return getPending(reqId) != null;
    }

    /**
     * Get some reqId that is pending.
     *
     * @param reqId the request identifier
     * @return the pending request, or null
     */
    public TOMMessage getPending(int reqId) {
        ClientData clientData = getClientData(TOMMessage.getSenderFromId(reqId));

        clientData.clientLock.lock();
        /******* BEGIN CLIENTDATA CRITICAL SECTION ******/
        TOMMessage pendingMessage = clientData.getPendingRequests().getById(reqId);

        /******* END CLIENTDATA CRITICAL SECTION ******/
        clientData.clientLock.unlock();

        return pendingMessage;
    }

    public boolean requestReceived(TOMMessage request, boolean fromClient) {
        return requestReceived(request, fromClient, null);
    }

    /**
     * Notifies the ClientsManager that a new request from a client arrived.
     * This method updates the ClientData of the client request.getSender().
     *
     * @param request the received request
     * @param fromClient the message was received from client or not?
     * @param storeMessage the message should be stored or not? (read-only requests are not stored)
     * @param cs server com. system to be able to send replies to already processed requests
     *
     * @return true if the request is ok and is added to the pending messages
     * for this client, false if there is some problem and the message was not
     * accounted
     */
    public boolean requestReceived(TOMMessage request, boolean fromClient, ServerCommunicationSystem cs) {

        if(LOGGER.isDebugEnabled()) {
            LOGGER.debug("requestReceived: msg type = {}, msg from client = {}, req id = {}", request.getReqType(), request.getSender(), request.getId());
        }

        // if the content of the request is invalid, ignore it
        if (controller.getStaticConf().isBFT() && !verifier.isValidRequest(request.getContent())) return false;
        
//        request.receptionTime = System.nanoTime();

        request.receptionTime = System.currentTimeMillis();

        int clientId = request.getSender();
        boolean accounted = false;

        //Logger.println("(ClientsManager.requestReceived) getting info about client "+clientId);
        ClientData clientData = getClientData(clientId);
        
        //Logger.println("(ClientsManager.requestReceived) wait for lock for client "+clientData.getClientId());
        clientData.clientLock.lock();
        /******* BEGIN CLIENTDATA CRITICAL SECTION ******/
        //Logger.println("(ClientsManager.requestReceived) lock for client "+clientData.getClientId()+" acquired");

        /* ################################################ */
        //pjsousa: simple flow control mechanism to avoid out of memory exception
        if (fromClient && (controller.getStaticConf().getUseControlFlow() != 0)) {
            if (clientData.getPendingRequests().size() > controller.getStaticConf().getUseControlFlow()) {
                //clients should not have more than defined in the config file
                //outstanding messages, otherwise they will be dropped.
                //just account for the message reception
                clientData.setLastMessageReceived(request.getSequence());
                clientData.setLastMessageReceivedTime(request.receptionTime);

                clientData.clientLock.unlock();
                return false;
            }
        }
        /* ################################################ */

        //new session... just reset the client counter
        if (clientData.getSession() != request.getSession()) {
            clientData.setSession(request.getSession());
            clientData.setLastMessageReceived(-1);
            clientData.getOrderedRequests().clear();
            clientData.getPendingRequests().clear();
        }

        if ((clientData.getLastMessageReceived() == -1) || //first message received or new session (see above)
                (clientData.getLastMessageReceived() + 1 == request.getSequence()) || //message received is the expected
                ((request.getSequence() > clientData.getLastMessageReceived()) && !fromClient)) {

            //it is a new message and I have to verify it's signature
            if (!request.signed
                    || clientData.verifySignature(request.serializedMessage,
                    request.serializedMessageSignature)) {

                //I don't have the message but it is valid, I will
                //insert it in the pending requests of this client

                request.recvFromClient = fromClient;
                clientData.getPendingRequests().add(request); 
                clientData.setLastMessageReceived(request.getSequence());
                clientData.setLastMessageReceivedTime(request.receptionTime);
                clientDatasTotal.getAndIncrement();

                //create a timer for this message
                if (timer != null) {
                    timer.watch(request);
                }

                accounted = true;
            }
        } else {
            //I will not put this message on the pending requests list
            if (clientData.getLastMessageReceived() >= request.getSequence()) {
                //I already have/had this message

                //send reply if it is available
                TOMMessage reply = clientData.getReply(request.getSequence());
                
                if (reply != null && cs != null) {

                    if (reply.recvFromClient && fromClient) {
                        LOGGER.info("[CACHE] re-send reply [Sender: {}, sequence: {}, session: {}", reply.getSender(), reply.getSequence(), reply.getSession());
                        cs.send(new int[]{request.getSender()}, reply);

                    } 
                    
                    else if (!reply.recvFromClient && fromClient) {
                        reply.recvFromClient = true;
                    }
                    
                }
                accounted = true;
            } else {
                //a too forward message... the client must be malicious
                accounted = false;
            }
        }

        /******* END CLIENTDATA CRITICAL SECTION ******/
        clientData.clientLock.unlock();

        return accounted;
    }

    /**
     * Notifies the ClientsManager that these requests were already executed.
     * 
     * @param requests the array of requests to account as ordered
     */
    public void requestsOrdered(TOMMessage[] requests) {
        clientsLock.lock();
       LOGGER.debug("(ClientsManager.requestOrdered) Updating client manager");
        for (TOMMessage request : requests) {
            requestOrdered(request);
        }
       LOGGER.debug("(ClientsManager.requestOrdered) Finished updating client manager");
        clientsLock.unlock();
    }

    /**
     * Cleans all state for this request (e.g., removes it from the pending
     * requests queue and stop any timer for it).
     *
     * @param request the request ordered by the consensus
     */
    private void requestOrdered(TOMMessage request) {
        //stops the timer associated with this message
        if (timer != null) {
            timer.unwatch(request);
        }

        ClientData clientData = getClientData(request.getSender());

        clientData.clientLock.lock();
        /******* BEGIN CLIENTDATA CRITICAL SECTION ******/
        if (!clientData.removeOrderedRequest(request)) {
           LOGGER.debug("(ClientsManager.requestOrdered) Request {} does not exist in pending requests", request);
        }

        clientDatasTotal.getAndDecrement();

        clientData.setLastMessageExecuted(request.getSequence());

        /******* END CLIENTDATA CRITICAL SECTION ******/
        clientData.clientLock.unlock();
    }

    /**
     * Notifies the ClientsManager that these requests were abnormal, will be removed from pending requests
     *
     * @param requests the array of requests will be removed
     */
    public void requestsPending(TOMMessage[] requests) {
        clientsLock.lock();
       LOGGER.debug("(ClientsManager.requestOrdered) Updating client manager");
        for (TOMMessage request : requests) {
            requestPending(request);
        }
       LOGGER.debug("(ClientsManager.requestOrdered) Finished updating client manager");
        clientsLock.unlock();
    }

    /**
     * Cleans all state for this request (e.g., removes it from the pending
     * requests queue and stop any timer for it).
     *
     * @param request the request ordered by the consensus
     */
    private void requestPending(TOMMessage request) {
        //stops the timer associated with this message
        if (timer != null) {
            timer.unwatch(request);
        }

        ClientData clientData = getClientData(request.getSender());

        clientData.clientLock.lock();
        /******* BEGIN CLIENTDATA CRITICAL SECTION ******/
        if (!clientData.removePendingRequest(request)) {
           LOGGER.debug("(ClientsManager.requestPending) Request {} does not exist in pending requests", request);
        }

        clientDatasTotal.getAndDecrement();
        /******* END CLIENTDATA CRITICAL SECTION ******/
        clientData.clientLock.unlock();
    }

    public ReentrantLock getClientsLock() {
        return clientsLock;
    }
    
    public void clear() {
        clientsLock.lock();
        clientsData.clear();
        clientsLock.unlock();
        LOGGER.info("ClientsManager cleared.");

    }
}
