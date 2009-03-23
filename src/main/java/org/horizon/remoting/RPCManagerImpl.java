package org.horizon.remoting;

import org.horizon.CacheException;
import org.horizon.commands.ReplicableCommand;
import org.horizon.config.GlobalConfiguration;
import org.horizon.factories.KnownComponentNames;
import org.horizon.factories.annotations.ComponentName;
import org.horizon.factories.annotations.Inject;
import org.horizon.factories.annotations.Start;
import org.horizon.factories.annotations.Stop;
import org.horizon.jmx.annotations.MBean;
import org.horizon.jmx.annotations.ManagedAttribute;
import org.horizon.jmx.annotations.ManagedOperation;
import org.horizon.logging.Log;
import org.horizon.logging.LogFactory;
import org.horizon.marshall.Marshaller;
import org.horizon.notifications.cachemanagerlistener.CacheManagerNotifier;
import org.horizon.remoting.transport.Address;
import org.horizon.remoting.transport.Transport;
import org.horizon.statetransfer.StateTransferException;

import java.text.NumberFormat;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicLong;

/**
 * This component really is just a wrapper around a {@link org.horizon.remoting.transport.Transport} implementation, and
 * is used to set up the transport and provide lifecycle and dependency hooks into external transport implementations.
 *
 * @author Manik Surtani
 * @since 4.0
 */
@MBean(objectName = "RPCManager")
public class RPCManagerImpl implements RPCManager {

   Transport t;
   private final AtomicLong replicationCount = new AtomicLong(0);
   private final AtomicLong replicationFailures = new AtomicLong(0);
   boolean statisticsEnabled = false; // by default, don't gather statistics.
   private static final Log log = LogFactory.getLog(RPCManagerImpl.class);
   private volatile Address currentStateTransferSource;

   @Inject
   public void injectDependencies(GlobalConfiguration globalConfiguration, Transport t, InboundInvocationHandler handler,
                                  Marshaller marshaller,
                                  @ComponentName(KnownComponentNames.ASYNC_SERIALIZATION_EXECUTOR) ExecutorService e,
                                  CacheManagerNotifier notifier) {
      this.t = t;
      this.t.initialize(globalConfiguration, globalConfiguration.getTransportProperties(), marshaller, e, handler,
                        notifier, globalConfiguration.getDistributedSyncTimeout());
   }

   @Start(priority = 10)
   public void start() {
      t.start();
   }

   @Stop
   public void stop() {
      t.stop();
   }

   public List<Object> invokeRemotely(List<Address> recipients, ReplicableCommand rpcCommand, ResponseMode mode, long timeout, boolean usePriorityQueue, ResponseFilter responseFilter, boolean stateTransferEnabled) throws Exception {
      try {
         List<Object> result = t.invokeRemotely(recipients, rpcCommand, mode, timeout, usePriorityQueue, responseFilter, stateTransferEnabled);
         if (isStatisticsEnabled()) replicationCount.incrementAndGet();
         return result;
      } catch (Throwable e) {
         if (isStatisticsEnabled()) replicationFailures.incrementAndGet();
         throw new CacheException(e);
      }
   }

   public List<Object> invokeRemotely(List<Address> recipients, ReplicableCommand rpcCommand, ResponseMode mode, long timeout, boolean usePriorityQueue, boolean stateTransferEnabled) throws Exception {
      return invokeRemotely(recipients, rpcCommand, mode, timeout, usePriorityQueue, null, stateTransferEnabled);
   }

   public List<Object> invokeRemotely(List<Address> recipients, ReplicableCommand rpcCommand, ResponseMode mode, long timeout, boolean stateTransferEnabled) throws Exception {
      return invokeRemotely(recipients, rpcCommand, mode, timeout, false, null, stateTransferEnabled);
   }

   public void retrieveState(String cacheName, long timeout) throws StateTransferException {
      if (t.isSupportStateTransfer()) {
         // TODO make these configurable
         int initialWaitTime = 1000; // millis
         int waitTimeIncreaseFactor = 2;
         int numRetries = 3;
         List<Address> members = t.getMembers();
         if (members.size() < 2) {
            if (log.isDebugEnabled())
               log.debug("We're the only member in the cluster; no one to retrieve state from. Not doing anything!");
            return;
         }

         boolean success = false;

         try {

            outer:
            for (int i = 0, wait = initialWaitTime; i < numRetries; i++) {
               for (Address member : members) {
                  if (!member.equals(t.getAddress())) {
                     try {
                        if (log.isInfoEnabled()) log.info("Trying to fetch state from {0}", member);
                        currentStateTransferSource = member;
                        if (t.retrieveState(cacheName, member, timeout)) {
                           if (log.isInfoEnabled())
                              log.info("Successfully retrieved and applied state from {0}", member);
                           success = true;
                           break outer;
                        }
                     } catch (StateTransferException e) {
                        if (log.isDebugEnabled()) log.debug("Error while fetching state from member " + member, e);
                     } finally {
                        currentStateTransferSource = null;
                     }
                  }
               }

               if (!success) {
                  if (log.isWarnEnabled())
                     log.warn("Could not find available peer for state, backing off and retrying");

                  try {
                     Thread.sleep(wait *= waitTimeIncreaseFactor);
                  }
                  catch (InterruptedException e) {
                     Thread.currentThread().interrupt();
                  }
               }

            }
         } finally {
            currentStateTransferSource = null;
         }

         if (!success) throw new StateTransferException("Unable to fetch state on startup");
      } else {
         throw new StateTransferException("Transport does not, or is not configured to, support state transfer.  Please disable fetching state on startup, or reconfigure your transport.");
      }
   }

   public Transport getTransport() {
      return t;
   }

   public Address getCurrentStateTransferSource() {
      return currentStateTransferSource;
   }

   // -------------------------------------------- JMX information -----------------------------------------------

   @ManagedOperation
   public void resetStatistics() {
      replicationCount.set(0);
      replicationFailures.set(0);
   }

   @ManagedAttribute(description = "number of successful replications")
   public String getReplicationCount() {
      if (!isStatisticsEnabled()) {
         return "N/A";
      }
      return String.valueOf(replicationCount.get());
   }

   @ManagedAttribute(description = "number of failed replications")
   public String getReplicationFailures() {
      if (!isStatisticsEnabled()) {
         return "N/A";
      }
      return String.valueOf(replicationFailures.get());
   }

   @ManagedAttribute(description = "whether or not jmx statistics are enabled")
   public boolean isStatisticsEnabled() {
      return statisticsEnabled;
   }

   @ManagedAttribute
   public void setStatisticsEnabled(boolean statisticsEnabled) {
      this.statisticsEnabled = statisticsEnabled;
   }

   @ManagedAttribute
   public String getAddress() {
      if (t == null || !isStatisticsEnabled()) return "N/A";
      Address address = t.getAddress();
      return address == null ? "N/A" : address.toString();
   }

   @ManagedAttribute
   public String getMembers() {
      if (t == null || !isStatisticsEnabled()) return "N/A";
      List<Address> addressList = t.getMembers();
      return addressList.toString();
   }

   @ManagedAttribute
   public String getSuccessRatio() {
      if (replicationCount.get() == 0 || !statisticsEnabled) {
         return "N/A";
      }
      double totalCount = replicationCount.get() + replicationFailures.get();
      double ration = (double) replicationCount.get() / totalCount * 100d;
      return NumberFormat.getInstance().format(ration) + "%";
   }

   // mainly for unit testing
   public void setTransport(Transport t) {
      this.t = t;
   }
}
