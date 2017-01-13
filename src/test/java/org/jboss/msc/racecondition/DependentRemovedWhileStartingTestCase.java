package org.jboss.msc.racecondition;

import java.util.concurrent.TimeUnit;

import org.jboss.byteman.contrib.bmunit.BMScript;
import org.jboss.byteman.contrib.bmunit.BMUnitConfig;
import org.jboss.byteman.contrib.bmunit.BMUnitRunner;
import org.jboss.byteman.rule.helper.Helper;
import org.jboss.msc.service.AbstractServiceListener;
import org.jboss.msc.service.Service;
import org.jboss.msc.service.ServiceController;
import org.jboss.msc.service.ServiceName;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * @author Stuart Douglas
 */
@RunWith(BMUnitRunner.class)
@BMScript(dir="src/test/resources")
@BMUnitConfig(debug = true)
public class DependentRemovedWhileStartingTestCase extends AbstractRaceConditionTest {

    private static final ServiceName dependee = ServiceName.of("dependee");
    private static final ServiceName depdent = ServiceName.of("depdent");
    @Test
    public void testRemoveWhileServiceStarts() throws InterruptedException {
        ServiceController<Void> c1 = serviceContainer.addService(dependee, Service.NULL).install();
        serviceContainer.awaitStability();
        c1.addListener(new AbstractServiceListener<Void>() {
            @Override
            public void transition(ServiceController<? extends Void> controller, ServiceController.Transition transition) {
                if(transition.getAfter() == ServiceController.Substate.REMOVED) {
                    serviceContainer.addService(dependee, Service.NULL).install();
                }
            }
        });
        c1.setMode(ServiceController.Mode.REMOVE);
        ServiceController<Void> c2 = doServiceInstall();
        if (!serviceContainer.awaitStability(2, TimeUnit.SECONDS)) {
            serviceContainer.dumpServices();
            Assert.fail();
        }
        c1.setMode(ServiceController.Mode.REMOVE);
        c2.setMode(ServiceController.Mode.REMOVE);

    }

    private ServiceController<Void> doServiceInstall() {
        return serviceContainer.addService(depdent, Service.NULL)
                    .addDependency(dependee)
                    .install();
    }
}
