/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2010, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.jboss.msc.service;

import static org.jboss.modules.management.ObjectProperties.property;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.management.MBeanServerConnection;
import javax.management.ObjectName;

import org.jboss.modules.management.ObjectProperties;
import org.jboss.msc.util.TestServiceListener;
import org.jboss.msc.value.InjectedValue;
import org.jboss.msc.value.Values;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Test to verify the behavior of multiple dependencies.
 *
 * @author John Bailey
 * @author <a href="mailto:flavia.rainone@jboss.com">Flavia Rainone</a>
 */
public class MultipleDependenciesTestCase extends AbstractServiceTest {

    public static final String MODULE = "module";
    private static Field dependenciesField;
    private static final ServiceName firstServiceName = ServiceName.of("firstService");
    private static final ServiceName secondServiceName = ServiceName.of("secondService");
    private static final ServiceName thirdServiceName = ServiceName.of("thirdService");
    private static final ServiceName fourthServiceName = ServiceName.of("fourthService");

    private static final AtomicInteger stopCount = new AtomicInteger();
    private static final AtomicInteger startCount = new AtomicInteger();
    private TestServiceListener listener;

    @Before
    public void setUpTestListener() {
        listener = new TestServiceListener();
        stopCount.set(0);
        startCount.set(0);
    }

    @BeforeClass
    public static void initDependenciesField() throws Exception {
        dependenciesField = ServiceControllerImpl.class.getDeclaredField("dependencies");
        dependenciesField.setAccessible(true);
    }

    /**
     * multiTierRestartTest variant.
     * Here we do not wait for stability when the grandchild service
     * wants to restart its grandparent service.
     * Currently expected to fail intermittently
     */
    @Test
    public void testSomeThings2() throws Exception {
        multiTierRestartTest(true, false);
    }

    /**
     * multiTierRestartTest variant.
     * Here we  wait for stability when the grandchild service
     * wants to restart its grandparent service.  But the test driver does
     * not wait for stability following stopping the s1 parent before
     * it starts it again.
     * Currently expected to fail intermittently
     */
    @Test
    public void testDeferredMultiTierRestart() throws Exception {
        multiTierRestartTest(false, false);
    }

    /**
     * multiTierRestartTest variant.
     * Here we  wait for stability when the grandchild service
     * wants to restart its grandparent service.  Plus the test driver
     * waits for stability following stopping the s1 parent before
     * it starts it again.
     * This is not expected to fail.
     */
    @Test
    public void testDeferredMultiTierRestartWithStabilityDelay() throws Exception {
        multiTierRestartTest(false, true);
    }

    /**
     * Tests of a multi-tiered hierarchy of related service sets. Each set has three services with parent/child/grandchild
     * relationship, and with the "child" services also having dependency relationships between sets, with s3 depending
     * on s2 and s2 depending on s1 (so a 3 tier hierarchy between sets.) The grandchild services have special logic
     * such that if they detect they have been started more than once, their start does not proceed normally; rather
     * it invokes logic such that the "parent" in their set is started and then restopped.
     *
     * The test stops the s1 parent service and then immediately restarts it.
     *
     * All of this models behavior found in WildFly Core's deployment service handling. The stopping and restarting
     * behavior there has resulted in bug reports.
     *
     * @param immediateRestart {@code true} if the grandchild's restart of its grandparent should proceed immediately;
     *                         {@code false} if it should wait for container stability following the s1 restart
     * @param stabilizeBeforeReinstall {@code true} if following the s1 parent stop a pause for stability should
     *                                 happen before s1 parent is started again. {@code false} if the start should
     *                                 not wait for stability
     */
    private void multiTierRestartTest(boolean immediateRestart, boolean stabilizeBeforeReinstall) throws Exception {

        // Install a special service to handle stability delays and stability checks
        ConsistencyHandler consistencyHandler = new ConsistencyHandler();
        ServiceController<ConsistencyHandler> ch = serviceContainer.addService(ConsistencyHandler.SERVICE_NAME, consistencyHandler).install();

        final int loops = 100000;
        for(int i = 0; i < loops; ++i) {

            ServiceName s3 = ServiceName.JBOSS.append("s3");
            ServiceName s2 = ServiceName.JBOSS.append("s2");
            ServiceName s1 = ServiceName.JBOSS.append("s1");
            ServiceController<Void> c3 = serviceContainer.addService(s3, new RootService(s3, s2.append(MODULE)))
                    .install();
            ServiceController<Void> c2 = serviceContainer.addService(s2, new RootService(s2, s1.append(MODULE)))
                    .install();
            ServiceController<Void> c1 = serviceContainer.addService(s1, new RootService(s1))
                    .install();
            serviceContainer.awaitStability();
            final CountDownLatch latch = new CountDownLatch(1);
            c1.addListener(new AbstractServiceListener<Void>() {
                @Override
                public void transition(ServiceController<? extends Void> controller, ServiceController.Transition transition) {
                    if (transition.getAfter() == ServiceController.Substate.REMOVED) {
                        latch.countDown();
                    }
                }
            });

            // Model an operation that restarts the service set (s1) the others depend on

            if (!immediateRestart) {
                // Signal the consistency handler that it needs to defer restart requests
                consistencyHandler.deferRestarts();
            }

            c1.setMode(ServiceController.Mode.REMOVE);
            latch.await();
            if (stabilizeBeforeReinstall) {
                if(!serviceContainer.awaitStability(2, TimeUnit.SECONDS)) {
                    dumpDetails();
                    Assert.fail();
                }
            }
            c1 = serviceContainer.addService(s1, new RootService(s1))
                    .install();
            if(!consistencyHandler.awaitStability(serviceContainer,2, TimeUnit.SECONDS)) {
                dumpDetails();
                Assert.fail();
            }

            // Model an operation the removes the 3 service sets

            if (!immediateRestart) {
                // Signal the consistency handler that it needs to defer restart requests
                consistencyHandler.deferRestarts();
            }

            c1.setMode(ServiceController.Mode.REMOVE);
            c2.setMode(ServiceController.Mode.REMOVE);
            c3.setMode(ServiceController.Mode.REMOVE);
            if(!consistencyHandler.awaitStability(serviceContainer, 2, TimeUnit.SECONDS)) {
                dumpDetails();
                Assert.fail();
            }
        }

        // Final cleanup

        ch.setMode(ServiceController.Mode.REMOVE);
        if(!serviceContainer.awaitStability(2, TimeUnit.SECONDS)) {
            dumpDetails();
            Assert.fail();
        }

        System.out.println("With immediate restart '" + immediateRestart + "' over " + loops + " tests root service stop count was " + stopCount.get() + " and start count was " + startCount.get());
    }

    private void dumpDetails() throws Exception {
        MBeanServerConnection mbs = ManagementFactory.getPlatformMBeanServer();
        ObjectName on = new ObjectName("jboss.msc", ObjectProperties.properties(property("type", "container"), property("name", serviceContainer.getName())));
        String[] names = (String[]) mbs.invoke(on, "queryServiceNames", new Object[]{}, new String[]{});
        StringBuilder sb = new StringBuilder("Services for ");
        sb.append(serviceContainer.getName());
        sb.append("\n");
        for (String name : names) {
            sb.append(mbs.invoke(on, "dumpServiceDetails", new Object[]{name}, new String[]{String.class.getName()}));
            sb.append("\n");
        }
        sb.append(names.length);
        sb.append(" services displayed");
        System.out.println(sb);
    }


    /** The parent service in the multiTierRestartTest. */
    private class RootService extends AbstractService<Void> {
        final ServiceName baseName;
        private final ServiceName[] serviceNames;

        private RootService(ServiceName baseName, ServiceName... serviceNames) {
            this.baseName = baseName;
            this.serviceNames = serviceNames;
        }

        @Override
        public void start(final StartContext context) throws StartException {
            ServiceName module = baseName.append(MODULE);
            FirstModuleUseService service = new FirstModuleUseService();
            context.getChildTarget().addService(baseName.append("firstModuleUse"), service)
                    .addDependency(module)
                    .addDependency(ConsistencyHandler.SERVICE_NAME, ConsistencyHandler.class, service.consistencyHandlerInjectedValue)
                    .install();
            context.getChildTarget().addService(module, Service.NULL)
                    .addDependencies(serviceNames)
                    .install();
        }
    }

    /**
     * The grandchild service in the multiTierRestartTest.
     * Name reflects a service in WildFly that was important
     * in bug reports.
     */
    private class FirstModuleUseService extends AbstractService<Void> {

        private InjectedValue<ConsistencyHandler> consistencyHandlerInjectedValue = new InjectedValue<ConsistencyHandler>();
        boolean first = true;

        @Override
        public void start(final StartContext context) throws StartException {
            if(first) {
                first = false;
            } else {
                first = true;
                consistencyHandlerInjectedValue.getValue().serviceRequiresRestart(context.getController().getParent());
            }
        }
    }

    /** Handles grandparent service restart as well as service container stability checking for multiTierRestartTest. */
    private static class ConsistencyHandler extends AbstractService<ConsistencyHandler> {

        static final ServiceName SERVICE_NAME = ServiceName.of("consistencyHandler");

        private final Set<ServiceController<?>> controllersToStop = new HashSet<ServiceController<?>>();
        private final Set<ServiceController<?>> controllersToStart = new HashSet<ServiceController<?>>();
        private final AtomicBoolean deferRestarts = new AtomicBoolean();


        public void serviceRequiresRestart(ServiceController toRestart) {
            if (deferRestarts.get()) {
                synchronized (controllersToStop) {
                    controllersToStop.add(toRestart);
                }
            } else {
                // We're not testing deferring restart until stable.
                // Just restart immediately
                restartService(toRestart, true);
            }
        }

        void deferRestarts() {
            deferRestarts.set(true);
        }

        boolean awaitStability(ServiceContainer serviceContainer, long timeout, TimeUnit unit) throws InterruptedException {
            try {
                boolean loop;
                do {
                    loop = false;
                    if (!serviceContainer.awaitStability(timeout, unit)) {
                        return false;
                    }

                    Set<ServiceController<?>> stopClone = null;
                    synchronized (controllersToStop) {
                        if (!controllersToStop.isEmpty()) {
                            assertTrue(deferRestarts.get());
                            stopClone = new HashSet<ServiceController<?>>(controllersToStop);
                            controllersToStop.clear();
                        }
                    }
                    if (stopClone != null) {
                        loop = true;
                        for (ServiceController<?> svcController : stopClone) {
                            restartService(svcController, false);
                        }

                        if (!serviceContainer.awaitStability(timeout, unit)) {
                            return false;
                        }
                    }

                    Set<ServiceController<?>> startClone = null;
                    synchronized (controllersToStart) {
                        if (!controllersToStart.isEmpty()) {
                            assertTrue(deferRestarts.get());
                            startClone = new HashSet<ServiceController<?>>(controllersToStart);
                            controllersToStart.clear();
                        }
                    }

                    if (startClone != null) {
                        loop = true;
                        for (ServiceController<?> svcController : startClone) {
                            if (svcController.getState() != ServiceController.State.REMOVED) {
                                svcController.setMode(ServiceController.Mode.ACTIVE);
                                startCount.incrementAndGet();
                            }
                        }
                    }
                } while (loop);

                return true;
            } finally {
                deferRestarts.set(false);
            }

        }

        private void restartService(final ServiceController serviceController, final boolean immediateActivate) {
            if (serviceController.getState() != ServiceController.State.REMOVED) {
                serviceController.addListener(new AbstractServiceListener() {
                    @Override
                    public void transition(ServiceController controller, ServiceController.Transition transition) {
                        if(transition.getAfter() == ServiceController.Substate.DOWN) {
                            if (immediateActivate) {
                                controller.setMode(ServiceController.Mode.ACTIVE);
                                startCount.incrementAndGet();
                            } else {
                                synchronized (controllersToStart) {
                                    controllersToStart.add(controller);
                                }
                            }
                            controller.removeListener(this);
                        }
                    }
                });
                serviceController.setMode(ServiceController.Mode.NEVER);
                stopCount.incrementAndGet();
            }
        }

        @Override
        public ConsistencyHandler getValue() throws IllegalStateException {
            return this;
        }
    }

    @Test
    public void test() throws Exception {
        serviceContainer.addListener(listener);
        serviceContainer.addDependency(fourthServiceName);

        final Set<ServiceListener<Object>> listeners = serviceContainer.getListeners();
        assertNotNull(listeners);
        assertEquals(1, listeners.size());
        assertTrue(listeners.contains(listener));

        final Set<ServiceName> builderDependencies = serviceContainer.getDependencies();
        assertNotNull(builderDependencies);
        assertEquals(1, builderDependencies.size());
        assertTrue(builderDependencies.contains(fourthServiceName));

        final Future<ServiceController<?>> firstService = listener.expectServiceStart(firstServiceName);
        final Future<ServiceController<?>> secondService = listener.expectServiceStart(secondServiceName);
        final Future<ServiceController<?>> thirdService = listener.expectServiceStart(thirdServiceName);
        final Future<ServiceController<?>> fourthService = listener.expectServiceStart(fourthServiceName);

        serviceContainer.addService(firstServiceName, Service.NULL).install();
        serviceContainer.addService(secondServiceName, Service.NULL).install();
        serviceContainer.addService(thirdServiceName, Service.NULL).install();
        serviceContainer.addService(fourthServiceName, Service.NULL).install();

        final ServiceController<?> fourthController = assertController(fourthServiceName, fourthService);
        final ServiceController<?> firstController = assertController(firstServiceName, firstService);

        List<ServiceControllerImpl<?>> dependencies = getServiceDependencies(firstController);
        assertTrue(dependencies.contains(fourthController));

        dependencies = getServiceDependencies(secondService.get());
        assertTrue(dependencies.contains(fourthController));

        dependencies = getServiceDependencies(thirdService.get());
        assertTrue(dependencies.contains(fourthController));

        dependencies = getServiceDependencies(fourthController);
        assertFalse(dependencies.contains(fourthController));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void testWithServiceValues() throws Exception {
        final TestServiceListener listener1 = new TestServiceListener();
        final TestServiceListener listener2 = new TestServiceListener();
        serviceContainer.addListener(listener1, listener2);

        final Future<ServiceController<?>> firstService1 = listener1.expectServiceStart(ServiceName.of("firstService"));
        final Future<ServiceController<?>> firstService2 = listener2.expectServiceStart(ServiceName.of("firstService"));
        final Future<ServiceController<?>> secondService1 = listener1.expectServiceStart(ServiceName.of("secondService"));
        final Future<ServiceController<?>> secondService2 = listener2.expectServiceStart(ServiceName.of("secondService"));

        serviceContainer.addServiceValue(firstServiceName, Values.immediateValue(Service.NULL)).install();
        serviceContainer.addServiceValue(secondServiceName, Values.immediateValue(Service.NULL)).install();

        Set<ServiceListener<Object>> listeners = serviceContainer.getListeners();
        assertNotNull(listeners);
        assertEquals(2, listeners.size());
        assertTrue(listeners.contains(listener1));
        assertTrue(listeners.contains(listener2));

        Set<ServiceName> containerDependencies = serviceContainer.getDependencies();
        assertNotNull(containerDependencies);
        assertEquals(0, containerDependencies.size());

        final ServiceController<?> firstController = assertController(firstServiceName, firstService1);
        assertController(firstController, firstService2);

        final ServiceController<?> secondController = assertController(secondServiceName, secondService1);
        assertController(secondController, secondService2);

        List<ServiceControllerImpl<?>> dependencies = getServiceDependencies(firstController);
        assertEquals(0, dependencies.size());

        dependencies = getServiceDependencies(secondController);
        assertEquals(0, dependencies.size());

        final Future<ServiceController<?>> thirdService = listener2.expectServiceStart(ServiceName.of("thirdService"));
        final Future<ServiceController<?>> fourthService = listener2.expectServiceStart(ServiceName.of("fourthService"));

        serviceContainer.addDependency(firstServiceName, secondServiceName, thirdServiceName);
        serviceContainer.addListener(listener2);
        serviceContainer.addServiceValue(thirdServiceName, Values.immediateValue(Service.NULL)).install();
        serviceContainer.addServiceValue(fourthServiceName, Values.immediateValue(Service.NULL)).install();

        listeners = serviceContainer.getListeners();
        assertNotNull(listeners);
        assertEquals(2, listeners.size());
        assertTrue(listeners.contains(listener2));

        containerDependencies = serviceContainer.getDependencies();
        assertNotNull(containerDependencies);
        assertEquals(3, containerDependencies.size());
        assertTrue(containerDependencies.contains(firstServiceName));
        assertTrue(containerDependencies.contains(secondServiceName));
        assertTrue(containerDependencies.contains(thirdServiceName));

        final ServiceController<?> thirdController = assertController(thirdServiceName, thirdService);
        final ServiceController<?> fourthController = assertController(fourthServiceName, fourthService);

        dependencies = getServiceDependencies(thirdController);
        assertNotNull(dependencies);
        assertEquals(2, dependencies.size());
        assertTrue(dependencies.contains(firstController));
        assertTrue(dependencies.contains(secondController));

        dependencies = getServiceDependencies(fourthController);
        assertNotNull(dependencies);
        assertEquals(3, dependencies.size());
        assertTrue(dependencies.contains(firstController));
        assertTrue(dependencies.contains(secondController));
        assertTrue(dependencies.contains(thirdController));
    }

    @Test
    public void installNull() throws Exception {
        /*builder.addDependency((ServiceName) null);
        builder.addService(ServiceName.of("service"), Service.NULL);
        try {
            builder.install();
            fail("NullPointerException expected");
        } catch (NullPointerException e) {}
*/
        // No exception expected
        serviceContainer.addDependency((ServiceName[]) null);
        serviceContainer.addDependency((Collection<ServiceName>) null);
        serviceContainer.addListener((ServiceListener<Object>[]) null);
        serviceContainer.addListener((Collection<ServiceListener<Object>>) null);
        try {
            serviceContainer.addService(null, Service.NULL);
            fail("IllegalArgumentException expected");
        } catch (IllegalArgumentException e) {}

       try {
            serviceContainer.addServiceValue(null, Values.immediateValue(Service.NULL));
            fail("IllegalArgumentException expected");
        } catch (IllegalArgumentException e) {}
    }

    private List<ServiceControllerImpl<?>> getServiceDependencies(ServiceController<?> serviceController) throws IllegalAccessException {
        Dependency[] deps = (Dependency[]) dependenciesField.get(serviceController);
        List<ServiceControllerImpl<?>> depInstances = new ArrayList<ServiceControllerImpl<?>>(deps.length);
        for (Dependency dep: deps) {
            ServiceControllerImpl<?> depInstance = (ServiceControllerImpl<?>) ((ServiceRegistrationImpl)dep).getInstance();
            if (depInstance != null) {
                depInstances.add(depInstance);
            }
        }
        return depInstances;
    }
}
