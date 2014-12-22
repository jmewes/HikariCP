/*
 * Copyright (C) 2013,2014 Brett Wooldridge
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.zaxxer.hikari.pool;

import static com.zaxxer.hikari.util.IConcurrentBagEntry.STATE_IN_USE;
import static com.zaxxer.hikari.util.IConcurrentBagEntry.STATE_NOT_IN_USE;
import static com.zaxxer.hikari.util.UtilityElf.quietlySleep;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.util.ConcurrentBag;
import com.zaxxer.hikari.util.IBagStateListener;
import com.zaxxer.hikari.util.Java6ConcurrentBag;

/**
 * This is the primary connection pool class that provides the basic
 * pooling behavior for HikariCP.
 *
 * @author Brett Wooldridge
 */
public final class HikariPool extends BaseHikariPool
{
   /**
    * Construct a HikariPool with the specified configuration.
    *
    * @param configuration a HikariConfig instance
    */
   public HikariPool(HikariConfig configuration)
   {
      this(configuration, configuration.getUsername(), configuration.getPassword());
   }

   /**
    * Construct a HikariPool with the specified configuration.
    *
    * @param configuration a HikariConfig instance
    * @param username authentication username
    * @param password authentication password
    */
   public HikariPool(HikariConfig configuration, String username, String password)
   {
      super(configuration, username, password);
   }

   // ***********************************************************************
   //                        IBagStateListener callback
   // ***********************************************************************

   /** {@inheritDoc} */
   @Override
   public void addBagItem()
   {
      class AddConnection implements Runnable
      {
         public void run()
         {
            long sleepBackoff = 200L;
            final int maxPoolSize = configuration.getMaximumPoolSize();
            final int minIdle = configuration.getMinimumIdle();
            while (poolState == POOL_RUNNING && totalConnections.get() < maxPoolSize && (minIdle == 0 || getIdleConnections() < minIdle)) {
               if (addConnection()) {
                  if (minIdle == 0) {
                     break; // This break is here so we only add one connection when there is no min. idle
                  }
               }
               else {
                  if (minIdle == 0 && getThreadsAwaitingConnection() == 0) {
                     break;
                  }
                  
                  quietlySleep(sleepBackoff);
                  sleepBackoff = Math.min(connectionTimeout / 2, (long) ((double) sleepBackoff * 1.5));
               }
            }
         }
      }

      addConnectionExecutor.execute(new AddConnection());
   }

   // ***********************************************************************
   //                        HikariPoolMBean methods
   // ***********************************************************************

   /** {@inheritDoc} */
   @Override
   public void softEvictConnections()
   {
      for (PoolBagEntry bagEntry : connectionBag.values(STATE_IN_USE)) {
         bagEntry.evicted = true;
      }

      for (PoolBagEntry bagEntry : connectionBag.values(STATE_NOT_IN_USE)) {
         if (connectionBag.reserve(bagEntry)) {
            closeConnection(bagEntry);
         }
      }
   }

   // ***********************************************************************
   //                           Private methods
   // ***********************************************************************

   /**
    * Permanently close the real (underlying) connection (eat any exception).
    *
    * @param connectionProxy the connection to actually close
    */
   @Override
   protected void closeConnection(final PoolBagEntry bagEntry)
   {
      bagEntry.cancelMaxLifeTermination();
      if (connectionBag.remove(bagEntry)) {
         final int tc = totalConnections.decrementAndGet();
         if (tc < 0) {
            LOGGER.warn("Internal accounting inconsistency, totalConnections={}", tc, new Exception());
         }
         closeConnectionExecutor.execute(new Runnable() {
            public void run() {
               poolUtils.quietlyCloseConnection(bagEntry.connection);
            }
         });
      }
   }

   /**
    * Check whether the connection is alive or not.
    *
    * @param connection the connection to test
    * @param timeoutMs the timeout before we consider the test a failure
    * @return true if the connection is alive, false if it is not alive or we timed out
    */
   @Override
   protected boolean isConnectionAlive(final Connection connection, final long timeoutMs)
   {
      try {
         final boolean timeoutEnabled = (connectionTimeout != Integer.MAX_VALUE);
         int timeoutSec = timeoutEnabled ? (int) Math.max(1L, TimeUnit.MILLISECONDS.toSeconds(timeoutMs)) : 0;

         if (isUseJdbc4Validation) {
            return connection.isValid(timeoutSec);
         }

         final int originalTimeout = poolUtils.getAndSetNetworkTimeout(connection, Math.max(1000, (int) timeoutMs), timeoutEnabled);

         Statement statement = connection.createStatement();
         try {
            poolUtils.setQueryTimeout(statement, timeoutSec);
            statement.executeQuery(configuration.getConnectionTestQuery());
         }
         finally {
            statement.close();
         }

         if (isIsolateInternalQueries && !isAutoCommit) {
            connection.rollback();
         }

         poolUtils.setNetworkTimeout(connection, originalTimeout, timeoutEnabled);

         return true;
      }
      catch (SQLException e) {
         LOGGER.warn("Exception during keep alive check, that means the connection ({}) must be dead.", connection, e);
         return false;
      }
   }

   /**
    * Attempt to abort() active connections on Java7+, or close() them on Java6.
    *
    * @throws InterruptedException 
    */
   @Override
   protected void abortActiveConnections(final ExecutorService assassinExecutor) throws InterruptedException
   {
      for (PoolBagEntry bagEntry : connectionBag.values(STATE_IN_USE)) {
         try {
            bagEntry.aborted = bagEntry.evicted = true;
            bagEntry.connection.abort(assassinExecutor);
         }
         catch (Throwable e) {
            if (e instanceof InterruptedException) {
               throw (InterruptedException) e;
            }
            poolUtils.quietlyCloseConnection(bagEntry.connection);
         }
         finally {
            if (connectionBag.remove(bagEntry)) {
               totalConnections.decrementAndGet();
            }
         }
      }
   }

   /** {@inheritDoc} */
   @Override
   protected Runnable getHouseKeeper()
   {
      return new HouseKeeper();
   }

   /** {@inheritDoc} */
   @Override
   protected ConcurrentBag<PoolBagEntry> createConcurrentBag(IBagStateListener listener)
   {
      return new Java6ConcurrentBag(listener);
   }

   /**
    * The house keeping task to retire idle connections.
    */
   private class HouseKeeper implements Runnable
   {
      @Override
      public void run()
      {
         logPoolState("Before cleanup ");

         connectionTimeout = configuration.getConnectionTimeout(); // refresh member in case it changed

         final long now = System.currentTimeMillis();
         final long idleTimeout = configuration.getIdleTimeout();

         for (PoolBagEntry bagEntry : connectionBag.values(STATE_NOT_IN_USE)) {
            if (connectionBag.reserve(bagEntry)) {
               if (bagEntry.evicted || (idleTimeout > 0L && now > bagEntry.lastAccess + idleTimeout)) {
                  closeConnection(bagEntry);
               }
               else {
                  connectionBag.unreserve(bagEntry);
               }
            }
         }
         
         logPoolState("After cleanup ");

         if (configuration.getMinimumIdle() > 0) {
            addBagItem(); // Try to maintain minimum connections
         }
      }
   }
}
