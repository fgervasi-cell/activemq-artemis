/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.activemq.artemis.api.core.management;

import java.lang.invoke.MethodHandles;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.activemq.artemis.api.core.JsonUtil;
import org.apache.activemq.artemis.api.core.client.ActiveMQClient;
import org.apache.activemq.artemis.api.core.client.ClientMessage;
import org.apache.activemq.artemis.api.core.client.ClientSession;
import org.apache.activemq.artemis.api.core.client.ClientSessionFactory;
import org.apache.activemq.artemis.api.core.client.ServerLocator;
import org.apache.activemq.artemis.core.client.impl.ServerLocatorImpl;
import org.apache.activemq.artemis.json.JsonArray;
import org.apache.activemq.artemis.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** This class provides a simple proxy for management operations */
public class SimpleManagement implements AutoCloseable {

   private static final Logger logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

   private static final String SIMPLE_OPTIONS  = "{\"field\":\"\",\"value\":\"\",\"operation\":\"\"}";

   String uri, user, password;

   ServerLocator locator;
   ClientSessionFactory sessionFactory;
   ClientSession session;


   public SimpleManagement(String uri, String user, String password) {
      this.uri = uri;
      this.user = user;
      this.password = password;
   }

   public SimpleManagement open() throws Exception {
      if (session == null) {
         locator = ServerLocatorImpl.newLocator(uri);
         sessionFactory = locator.createSessionFactory();
         session = sessionFactory.createSession(user, password, false, true, true, false, ActiveMQClient.DEFAULT_ACK_BATCH_SIZE);
      }
      return this;
   }

   @Override
   public void close() throws Exception {
      if (session != null) {
         session.close();
         sessionFactory.close();
         locator.close();
         session = null;
         sessionFactory = null;
         locator = null;
      }
   }

   public long getCurrentTimeMillis() throws Exception {
      return simpleManagementLong("broker", "getCurrentTimeMillis");
   }

   /** Simple helper for management returning a string.*/
   public String simpleManagement(String resource, String method, Object... parameters) throws Exception {
      AtomicReference<String> responseString = new AtomicReference<>();
      doManagement((m) -> setupCall(m, resource, method, parameters), m -> setStringResult(m, responseString), SimpleManagement::failed);
      return responseString.get();
   }

   /** Simple helper for management returning a long.*/
   public long simpleManagementLong(String resource, String method, Object... parameters) throws Exception {
      AtomicLong responseLong = new AtomicLong();
      doManagement((m) -> setupCall(m, resource, method, parameters), m -> setLongResult(m, responseLong), SimpleManagement::failed);
      return responseLong.get();
   }

   public long getQueueCount(String queueName) throws Exception {
      return simpleManagementLong(ResourceNames.QUEUE + queueName, "getMessageCount");
   }

   public Map<String, Long> getQueueCounts(int maxRows) throws Exception {
      String responseString = simpleManagement("broker", "listQueues", SIMPLE_OPTIONS, 1, maxRows);

      JsonObject queuesAsJsonObject = JsonUtil.readJsonObject(responseString);
      JsonArray array = queuesAsJsonObject.getJsonArray("data");

      Map<String, Long> queues = new HashMap<>();

      for (int i = 0; i < array.size(); i++) {
         JsonObject object = array.getJsonObject(i);
         String name = object.getString("name");
         String messageCount = object.getString("messageCount");
         queues.put(name, Long.parseLong(messageCount));
      }

      return queues;
   }

   public String getNodeID() throws Exception {
      return simpleManagement("broker", "getNodeID");
   }

   public JsonArray listNetworkTopology() throws Exception {
      String result = simpleManagement("broker", "listNetworkTopology");
      return JsonUtil.readJsonArray(result);
   }

   protected static void failed(ClientMessage message) throws Exception {
      final String result = (String) ManagementHelper.getResult(message, String.class);
      logger.warn("simple management operation failed:: {}", result);
      throw new Exception("Failed " + result);
   }

   protected static void setupCall(ClientMessage m, String resource, String methodName, Object... parameters) throws Exception {
      if (logger.isDebugEnabled()) {
         logger.debug("Setting up call {}::{}::{}", resource, methodName, parameters);
      }
      ManagementHelper.putOperationInvocation(m, resource, methodName, parameters);
   }

   protected static void setStringResult(ClientMessage m, AtomicReference<String> result) throws Exception {
      String resultString = (String)ManagementHelper.getResult(m, String.class);
      logger.debug("management result:: {}", resultString);
      result.set(resultString);
   }

   protected static void setLongResult(ClientMessage m, AtomicLong result) throws Exception {
      long resultLong = (long)ManagementHelper.getResult(m, Long.class);
      logger.debug("management result:: {}", resultLong);
      result.set(resultLong);
   }

   protected void doManagement(ManagementHelper.MessageAcceptor setup, ManagementHelper.MessageAcceptor ok, ManagementHelper.MessageAcceptor failed) throws Exception {
      if (session != null) {
         ManagementHelper.doManagement(session, setup, ok, failed);
      } else {
         ManagementHelper.doManagement(uri, user, password, setup, ok, failed);
      }
   }
}