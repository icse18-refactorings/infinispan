package org.horizon.jmx;

import javax.management.MBeanServer;
import javax.management.MBeanServerFactory;

/**
 * Creates an MBeanServer on each thread.
 *
 * @author Mircea.Markus@jboss.com
 * @since 4.0
 */
public class PerThreadMBeanServerLookup implements MBeanServerLookup {

   static ThreadLocal<MBeanServer> threadMBeanServer = new ThreadLocal<MBeanServer>();

   public MBeanServer getMBeanServer() {
      return getThreadMBeanServer();
   }

   public static MBeanServer getThreadMBeanServer() {
      MBeanServer beanServer = threadMBeanServer.get();
      if (beanServer == null) {
         beanServer = MBeanServerFactory.createMBeanServer();
         threadMBeanServer.set(beanServer);
      }
      return beanServer;
   }
}
