/*
 *******************************************************************************
 * Copyright (c) 2018 Contributors to the Eclipse Foundation
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information regarding copyright ownership.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *******************************************************************************/
package org.eclipse.microprofile.lra.tck;

import static org.eclipse.microprofile.lra.annotation.ws.rs.LRA.LRA_HTTP_CONTEXT_HEADER;
import static org.eclipse.microprofile.lra.tck.participant.api.LraController.ACCEPT_WORK;
import static org.eclipse.microprofile.lra.tck.participant.api.LraController.LRA_CONTROLLER_PATH;
import static org.eclipse.microprofile.lra.tck.participant.api.LraController.TRANSACTIONAL_WORK_PATH;
import static org.eclipse.microprofile.lra.tck.participant.api.ParticipatingTckResource.COMPENSATED_CNT_PATH;
import static org.eclipse.microprofile.lra.tck.participant.api.ParticipatingTckResource.COMPLETED_CNT_PATH;
import static org.eclipse.microprofile.lra.tck.participant.api.ParticipatingTckResource.JOIN_WITH_EXISTNG_LRA_PATH;
import static org.eclipse.microprofile.lra.tck.participant.api.ParticipatingTckResource.JOIN_WITH_EXISTNG_LRA_PATH2;
import static org.eclipse.microprofile.lra.tck.participant.api.ParticipatingTckResource.TCK_PARTICIPANT_RESOURCE_PATH;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.time.temporal.ChronoUnit;
import java.util.logging.Logger;
import java.util.stream.IntStream;

import javax.inject.Inject;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.Response;

import org.eclipse.microprofile.lra.tck.participant.api.GenericLRAException;
import org.eclipse.microprofile.lra.tck.participant.api.NoLRAController;
import org.eclipse.microprofile.lra.tck.participant.api.Util;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.junit.Arquillian;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.EmptyAsset;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;
import org.junit.runner.RunWith;

@RunWith(Arquillian.class)
public class TckTests {
    private static final Logger LOGGER = Logger.getLogger(TckTests.class.getName());


    @Rule public TestName testName = new TestName();

    @Inject
    private LraTckConfigBean config;

    private LRAClientOps lraClient;

    private static URL recoveryCoordinatorBaseUrl;
    private static Client tckSuiteClient;
    private static Client recoveryCoordinatorClient;

    private WebTarget tckSuiteTarget;
    private WebTarget recoveryTarget;

    private enum CompletionType {
        complete, compensate, mixed
    }

    @Deployment(name = "tcktests", managed = true, testable = true)
    public static WebArchive deploy() {
        String archiveName = TckTests.class.getSimpleName().toLowerCase();
        return ShrinkWrap
            .create(WebArchive.class, archiveName + ".war")
            .addPackages(true, "org.eclipse.microprofile.lra.tck")
            .addAsManifestResource(EmptyAsset.INSTANCE, "beans.xml");
    }
    
    @AfterClass
    public static void afterClass() {
        if(tckSuiteClient != null) {
            tckSuiteClient.close();
        }
        if(recoveryCoordinatorClient != null) {
            recoveryCoordinatorClient.close();
        }
    }

    @Before
    public void before() {
        LOGGER.info("Running test: " + testName.getMethodName());
        setUpTestCase();

        try {
            tckSuiteTarget = tckSuiteClient.target(URI.create(new URL(config.tckSuiteBaseUrl()).toExternalForm()));
            lraClient = new LRAClientOps(tckSuiteTarget);
        } catch (MalformedURLException mfe) {
            throw new IllegalStateException("Cannot create URL for the LRA TCK suite base url " + config.tckSuiteBaseUrl(), mfe);
        }
        recoveryTarget = recoveryCoordinatorClient.target(URI.create(recoveryCoordinatorBaseUrl.toExternalForm()));
    }

    /**
     * Checking if coordinator is running, set ups the client to contact the recovery manager and the TCK suite itself.
     */
    private void setUpTestCase() {
        if(recoveryCoordinatorBaseUrl != null) {
            // we've already set up the recovery urls and REST clients for the tests
            return;
        }

        try {
            // TODO: what to do with this? recovery tests are valid?
            recoveryCoordinatorBaseUrl = new URL(String.format("http://%s:%d/%s",
                    config.recoveryHostName(), config.recoveryPort(), config.recoveryPath()));

            tckSuiteClient = ClientBuilder.newClient();
            recoveryCoordinatorClient = ClientBuilder.newClient();
        } catch (MalformedURLException e) {
            throw new RuntimeException("Cannot properly setup the TCK tests (coordinator endpoint, testsuite endpoints...)", e);
        }
    }

    /**
     * <p>
     * Starting LRA and canceling it.
     * <p>
     * It's expected the LRA won't be listed in active LRAs when canceled.
     */
    @Test
    public void cancelLRA() throws WebApplicationException {
        try {
            URI lra = lraClient.startLRA(null,lraClientId(), lraTimeout(), ChronoUnit.MILLIS);

            lraClient.cancelLRA(lra);

            assertTrue("LRA '" + lra + "' should not be active ",
                    lraClient.isLRAFinished(lra));
        } catch (GenericLRAException e) {
            e.printStackTrace();
            throw e;
        }
    }

    /**
     * <p>
     * Starting LRA and closing it.
     * <p>
     * It's expected the LRA won't be listed in active LRAs when closed.
     */
    @Test
    public void closeLRA() throws WebApplicationException {
        URI lra = lraClient.startLRA(null, lraClientId(), lraTimeout(), ChronoUnit.MILLIS);

        lraClient.closeLRA(lra);

        assertTrue("LRA '" + lra + "' should not be active anymore",
                lraClient.isLRAFinished(lra));
    }

    @Test
    public void nestedActivity() throws WebApplicationException {
        URI lra = lraClient.startLRA(null, lraClientId(), lraTimeout(), ChronoUnit.MILLIS);
        WebTarget resourcePath = tckSuiteTarget
                .path(LRA_CONTROLLER_PATH).path("nestedActivity");

        Response response = null;
        try {
            response = resourcePath
                    .request()
                    .header(LRA_HTTP_CONTEXT_HEADER, lra)
                    .put(Entity.text(""));
    
            assertEquals("Response status to ' " + resourcePath.getUri() + "' does not match.",
                    Response.Status.OK.getStatusCode(), response.getStatus());
    
            Object parentId = response.getHeaders().getFirst(LRA_HTTP_CONTEXT_HEADER);
    
            assertNotNull("Expecting to get parent LRA id as response from " + resourcePath.getUri(), parentId);
            assertEquals("The nested activity should return the parent LRA id. The call to " + resourcePath.getUri(),
                    parentId, lra.toString());
    
            String nestedLraId = response.readEntity(String.class);
    
            // close the LRA
            lraClient.closeLRA(lra);
    
            // validate that the nested LRA was closed

            // the resource /activities/work is annotated with Type.REQUIRED so the container should have ended it
            assertTrue("Nested LRA id '" + lra + "' should be listed in the list of the active LRAs (from call to "
                    + resourcePath.getUri() + ")", lraClient.isLRAFinished(nestedLraId));
        } finally {
            if(response != null) {
                response.close();
            }
        } 
    }

    @Test
    public void completeMultiLevelNestedActivity() throws WebApplicationException {
        multiLevelNestedActivity(CompletionType.complete, 1);
    }

    @Test
    public void compensateMultiLevelNestedActivity() throws WebApplicationException {
        multiLevelNestedActivity(CompletionType.compensate, 1);
    }

    @Test
    public void mixedMultiLevelNestedActivity() throws WebApplicationException {
        multiLevelNestedActivity(CompletionType.mixed, 2);
    }

    @Test
    public void joinLRAViaHeader() throws WebApplicationException {
        int beforeCompletedCount = getCompletedCount();

        URI lra = lraClient.startLRA(null, lraClientId(), lraTimeout(), ChronoUnit.MILLIS);

        WebTarget resourcePath = tckSuiteTarget.path(LRA_CONTROLLER_PATH).path(TRANSACTIONAL_WORK_PATH);
        Response response = resourcePath
                .request().header(LRA_HTTP_CONTEXT_HEADER, lra).put(Entity.text(""));

        checkStatusAndCloseResponse(Response.Status.OK, response, resourcePath);

        // validate that the LRA coordinator still knows about lraId
        assertFalse("LRA '" + lra + "' should be active as it is not closed yet.",
                lraClient.isLRAFinished(lra));

        // close the LRA
        lraClient.closeLRA(lra);

        // check that LRA coordinator no longer knows about lraId
        assertTrue("LRA '" + lra + "' should not be active anymore as it was closed yet.",
                lraClient.isLRAFinished(lra));

        // check that participant was told to complete
        int completedCount = getCompletedCount();
        assertEquals("Wrong completion count for call " + resourcePath.getUri() + ". Expecting the method LRA was completed "
                + "after joining the existing LRA " + lra, beforeCompletedCount + 1, completedCount);
    }

    @Test
    public void join() throws WebApplicationException {
        URI lra = lraClient.startLRA(null, lraClientId(), lraTimeout(), ChronoUnit.MILLIS);
        WebTarget resourcePath = tckSuiteTarget.path(LRA_CONTROLLER_PATH).path(TRANSACTIONAL_WORK_PATH);
        Response response = resourcePath
                .request().header(LRA_HTTP_CONTEXT_HEADER, lra).put(Entity.text(""));
        checkStatusAndCloseResponse(Response.Status.OK, response, resourcePath);
        lraClient.closeLRA(lra);
        assertTrue("LRA '" + lra + "' should be active as it is not closed yet.",
                lraClient.isLRAFinished(lra));
    }

    @Test
    public void leaveLRA() throws WebApplicationException {
        int beforeCompletedCount = getCompletedCount();
        URI lra = lraClient.startLRA(null, lraClientId(), lraTimeout(), ChronoUnit.MILLIS);
        WebTarget resourcePath = tckSuiteTarget.path(LRA_CONTROLLER_PATH).path(TRANSACTIONAL_WORK_PATH);
        Response response = resourcePath.request().header(LRA_HTTP_CONTEXT_HEADER, lra).put(Entity.text(""));

        checkStatusAndCloseResponse(Response.Status.OK, response, resourcePath);

        // perform a second request to the same method in the same LRA context to validate that multiple participants are not registered
        resourcePath = tckSuiteTarget.path(LRA_CONTROLLER_PATH).path(TRANSACTIONAL_WORK_PATH);
        response = resourcePath.request().header(LRA_HTTP_CONTEXT_HEADER, lra).put(Entity.text(""));
        checkStatusAndCloseResponse(Response.Status.OK, response, resourcePath);

        // call a method annotated with @Leave (should remove the participant from the LRA)
        resourcePath = tckSuiteTarget.path(LRA_CONTROLLER_PATH).path("leave");
        response = resourcePath.request().header(LRA_HTTP_CONTEXT_HEADER, lra).put(Entity.text(""));
        checkStatusAndCloseResponse(Response.Status.OK, response, resourcePath);

        lraClient.closeLRA(lra);

        // check that participant was not told to complete
        int completedCount = getCompletedCount();

        assertEquals("Wrong completion count when participant left the LRA. "
                + "Expecting the completed count hasn't change between start and end of the test. "
                + "The test call went to LRA controller at " + resourcePath.getUri(), beforeCompletedCount, completedCount);
    }

    @Test
    public void dependentLRA() throws WebApplicationException {
        // call a method annotated with NOT_SUPPORTED but one which programatically starts an LRA and returns it via a header
        WebTarget resourcePath = tckSuiteTarget.path(LRA_CONTROLLER_PATH).path("startViaApi");
        Response response = resourcePath.request().put(Entity.text(""));
        Object lraHeader = response.getHeaders().getFirst(LRA_HTTP_CONTEXT_HEADER);
        String lraId = checkStatusReadAndCloseResponse(Response.Status.OK, response, resourcePath);

        // LRAs started within the invoked remote method should not be available to the caller via the context header
        assertNull("JAX-RS response to PUT request should not have returned the header " + LRA_HTTP_CONTEXT_HEADER
                + ". The test call went to " + resourcePath.getUri(), lraHeader);

        // check that the remote method returned an active LRA (ie check it's not null and then close it)
        assertNotNull("JAX-RS response to PUT request should have returned content of LRA id. The test call went to "
                + resourcePath.getUri(), lraId);

        lraClient.closeLRA(URI.create(lraId));
    }

    @Test
    public void timeLimit() {
        int beforeCompletedCount = getCompletedCount();
        int beforeCompensatedCount = getCompensatedCount();
        
        WebTarget resourcePath = tckSuiteTarget.path(LRA_CONTROLLER_PATH).path("timeLimit");
        Response response = resourcePath
                .request()
                .get();

        response.close();

        // Note that the timeout firing will cause the coordinator to compensate
        // the LRA so it may no longer exist
        // (depends upon how long the coordinator keeps a record of finished LRAs

        // check that participant was invoked
        int completedCount = getCompletedCount();
        int compensatedCount = getCompensatedCount();

        /*
         * The call to activities/timeLimit should have started an LRA which should have timed out
         * (because the invoked resource method sleeps for longer than the timeLimit annotation
         * attribute specifies). Therefore the participant should have compensated:
         */
        assertEquals("The LRA should have timed out but complete was called instead of compensate. "
                + "Expecting the number of complete call before test matches the ones after LRA timed out. "
                + "The test call went to " + resourcePath.getUri(), beforeCompletedCount, completedCount);
        assertEquals("The LRA should have timed out and compensate should be called. "
                + "Expecting the number of compensate call before test is one less lower than the ones after LRA timed out. "
                + "The test call went to " + resourcePath.getUri(), beforeCompensatedCount + 1, compensatedCount);
    }

    @Test
    public void acceptCloseTest() throws WebApplicationException {
        joinAndEnd(true, true, LRA_CONTROLLER_PATH, ACCEPT_WORK);
    }

    @Test
    public void acceptCancelTest() throws WebApplicationException {
        joinAndEnd(true, false, LRA_CONTROLLER_PATH, ACCEPT_WORK);
    }

    private void joinAndEnd(boolean waitForRecovery, boolean close, String path, String path2) throws WebApplicationException {
        URI lra = lraClient.startLRA(null, lraClientId(), lraTimeout(), ChronoUnit.MILLIS);
        int beforeCompletionCount = getCompletedCount();
        int beforeCompensationCount = getCompensatedCount();
        WebTarget resourcePath = tckSuiteTarget.path(path).path(path2);

        Response response = resourcePath
                .request().header(LRA_HTTP_CONTEXT_HEADER, lra).put(Entity.text(""));

        checkStatusAndCloseResponse(Response.Status.OK, response, resourcePath);

        if (close) {
            lraClient.closeLRA(lra);
        } else {
            lraClient.cancelLRA(lra);
        }

        if (waitForRecovery) {
            // TODO the spec does not specifiy recovery semantics

            // trigger a recovery scan which trigger a replay attempt on any participants
            // that have responded to complete/compensate requests with Response.Status.ACCEPTED
            WebTarget recoveryPath = recoveryTarget.path("recovery");
            Response response2 = recoveryPath
                    .request().get();

            checkStatusAndCloseResponse(Response.Status.OK, response2, recoveryPath);
        }

        int completionCount = getCompletedCount() - beforeCompletionCount;
        int compensationCount = getCompensatedCount() - beforeCompensationCount;

        boolean wasCalled = (close ? completionCount > 0 : compensationCount > 0);
        boolean wasNotCalled = (close ? compensationCount == 0 : completionCount == 0);
        String lraMode = (close ? "close" : "cancel");
        String participantMode = (close ? "complete" : "compensate");

        assertTrue(String.format("acceptTest with %s: participant (%s) was not asked to %s",
                lraMode, resourcePath.getUri(), participantMode),
                wasCalled);
        assertTrue(String.format("acceptTest with %s: participant (%s) was asked to %s",
                lraMode, resourcePath.getUri(), participantMode),
                wasNotCalled);
        assertTrue("acceptTest: LRA did not finish", lraClient.isLRAFinished(lra));
    }

    @Test
    public void noLRATest() throws WebApplicationException {
        WebTarget resourcePath = tckSuiteTarget
                .path(NoLRAController.NO_LRA_CONTROLLER_PATH)
                .path(NoLRAController.NON_TRANSACTIONAL_WORK_PATH);

        int beforeCompletedCount = getCompletedCount();
        int beforeCompensatedCount = getCompensatedCount();
        URI lra = lraClient.startLRA(null, lraClientId(), lraTimeout(), ChronoUnit.MILLIS);

        Response response = resourcePath.request().header(LRA_HTTP_CONTEXT_HEADER, lra)
                .put(Entity.text(""));

        String lraId = checkStatusReadAndCloseResponse(Response.Status.OK, response, resourcePath);

        assertEquals("While calling non-LRA method the controller returns not expected LRA id",
                lraId, lra.toString());

        lraClient.cancelLRA(lra);

        // check that second service (the LRA aware one), namely
        // {@link org.eclipse.microprofile.lra.tck.participant.api.ActivityController#activityWithMandatoryLRA(String, String)}
        // was told to compensate
        int completedCount = getCompletedCount();
        int compensatedCount = getCompensatedCount();

        assertEquals("Complete should not be called on the LRA aware service. "
                + "The number of completed count for before and after test does not match. "
                + "The test call went to " + resourcePath.getUri(), beforeCompletedCount, completedCount);
        assertEquals("Compensate service should be called on LRA aware service. The number of compensated count after test is bigger for one. "
                + "The test call went to " + resourcePath.getUri(), beforeCompensatedCount + 1, compensatedCount);
    }

    /**
     * client invokes the same participant method twice in the same LRA context
     * cancel the LRA
     * check that the participant was asked to compensate only once
     */
    @Test
    public void joinWithOneResourceSameMethodTwiceWithCancel() throws WebApplicationException {
        joinWithOneResource("joinWithOneResourceSameMethodTwiceWithCancel",
                false, JOIN_WITH_EXISTNG_LRA_PATH, JOIN_WITH_EXISTNG_LRA_PATH);
    }

    /**
     * client invokes the same participant twice but using different methods in the same LRA context
     * cancel the LRA
     * check that the participant was asked to compensate only once
     */
    @Test
    public void joinWithOneResourceDifferentMethodTwiceWithCancel() throws WebApplicationException {
        joinWithOneResource("joinWithOneResourceDifferentMethodTwiceWithCancel",
                false, JOIN_WITH_EXISTNG_LRA_PATH, JOIN_WITH_EXISTNG_LRA_PATH2);
    }

    /**
     * client invokes the same participant method twice in the same LRA context
     * close the LRA
     * check that the participant was asked to complete only once
     */
    @Test
    public void joinWithOneResourceSameMethodTwiceWithClose() throws WebApplicationException {
        joinWithOneResource("joinWithOneResourceSameMethodTwiceWithClose",
                true, JOIN_WITH_EXISTNG_LRA_PATH, JOIN_WITH_EXISTNG_LRA_PATH);
    }

    /**
     * client invokes the same participant twice but using different methods in the same LRA context
     * close the LRA
     * check that the participant was asked to complete only once
     */
    @Test
    public void joinWithOneResourceDifferentMethodTwiceWithClose() throws WebApplicationException {
        joinWithOneResource("joinWithOneResourceDifferentMethodTwiceWithClose",
                true, JOIN_WITH_EXISTNG_LRA_PATH, JOIN_WITH_EXISTNG_LRA_PATH2);
    }

    /**
     * client invokes different participant in the same LRA context
     * close the LRA
     * check that both participants were asked to complete
     */
    @Test
    public void joinWithTwoResourcesWithClose() throws WebApplicationException {
        joinWithTwoResources(true);
    }

    /**
     * client invokes different participants in the same LRA context
     * cancel the LRA
     * check that both participants were asked to compensate
     */
    @Test
    public void joinWithTwoResourcesWithCancel() throws WebApplicationException {
        joinWithTwoResources(false);
    }

    private void joinWithOneResource(String methodName, boolean close, String resouce1Method, String resouce2Method)
            throws WebApplicationException {
        // get the initial values for the number of completions and compensations
        int completedCount = getActivityCount(TCK_PARTICIPANT_RESOURCE_PATH, COMPLETED_CNT_PATH);
        int compensatedCount = getActivityCount(TCK_PARTICIPANT_RESOURCE_PATH, COMPENSATED_CNT_PATH);

        // set up the web target for the test
        WebTarget resource1Path = tckSuiteTarget.path(TCK_PARTICIPANT_RESOURCE_PATH).path(resouce1Method);
        WebTarget resource2Path = tckSuiteTarget.path(TCK_PARTICIPANT_RESOURCE_PATH).path(resouce2Method);

        URI lra = lraClient.startLRA(null, lraClientId(), lraTimeout(), ChronoUnit.MILLIS);

        // invoke the same JAX-RS resources twicein the context of the lra which should enlist the resource only once:
        Response response1 = resource1Path.request().header(LRA_HTTP_CONTEXT_HEADER, lra).put(Entity.text(""));
        checkStatusAndCloseResponse(Response.Status.OK, response1, resource1Path);
        Response response2 = resource2Path.request().header(LRA_HTTP_CONTEXT_HEADER, lra).put(Entity.text(""));
        checkStatusAndCloseResponse(Response.Status.OK, response2, resource2Path);

        if (close) {
            lraClient.closeLRA(lra);

            assertTrue(methodName + ": resource should have completed once with no compensations",
                    completedCount + 1 == getActivityCount(TCK_PARTICIPANT_RESOURCE_PATH, COMPLETED_CNT_PATH)
                            && compensatedCount == getActivityCount(TCK_PARTICIPANT_RESOURCE_PATH, COMPENSATED_CNT_PATH));
        } else {
            lraClient.cancelLRA(lra);

            assertTrue(methodName + ":: resource should have compensated once with no completions",
                    completedCount == getActivityCount(TCK_PARTICIPANT_RESOURCE_PATH, COMPLETED_CNT_PATH)
                            && compensatedCount + 1 == getActivityCount(TCK_PARTICIPANT_RESOURCE_PATH, COMPENSATED_CNT_PATH));
        }
    }

    private void joinWithTwoResources(boolean close) throws WebApplicationException {
        // get the initial values for the number of completions and compensations
        int completedCount1 = getActivityCount(LRA_CONTROLLER_PATH, "completedactivitycount");
        int compensatedCount1 = getActivityCount(LRA_CONTROLLER_PATH, "compensatedactivitycount");
        int completedCount2 = getActivityCount(TCK_PARTICIPANT_RESOURCE_PATH, COMPLETED_CNT_PATH);
        int compensatedCount2 = getActivityCount(TCK_PARTICIPANT_RESOURCE_PATH, COMPENSATED_CNT_PATH);

        // set up the web target for the test
        WebTarget resource1Path = tckSuiteTarget.path(LRA_CONTROLLER_PATH).path(TRANSACTIONAL_WORK_PATH);
        WebTarget resource2Path = tckSuiteTarget.path(TCK_PARTICIPANT_RESOURCE_PATH).path(JOIN_WITH_EXISTNG_LRA_PATH);

        URI lra = lraClient.startLRA(null, lraClientId(), lraTimeout(), ChronoUnit.MILLIS);

        // invoke two JAX-RS resources in the context of the lra which should enlist them both:
        Response response1 = resource1Path.request().header(LRA_HTTP_CONTEXT_HEADER, lra).put(Entity.text(""));
        checkStatusAndCloseResponse(Response.Status.OK, response1, resource1Path);
        Response response2 = resource2Path.request().header(LRA_HTTP_CONTEXT_HEADER, lra).put(Entity.text(""));
        checkStatusAndCloseResponse(Response.Status.OK, response2, resource2Path);

        if (close) {
            lraClient.closeLRA(lra);

            assertTrue("joinWithTwoResourcesWithClose: both resources should have completed",
                    getActivityCount(LRA_CONTROLLER_PATH, "completedactivitycount") == completedCount1 + 1
                            && getActivityCount(TCK_PARTICIPANT_RESOURCE_PATH, COMPLETED_CNT_PATH) == completedCount2 + 1);
        } else {
            lraClient.cancelLRA(lra);

            assertTrue("joinWithTwoResourcesWithCancel: both resources should have compensated",
                    getActivityCount(LRA_CONTROLLER_PATH, "compensatedactivitycount") == compensatedCount1 +1
                            && getActivityCount(TCK_PARTICIPANT_RESOURCE_PATH, COMPLETED_CNT_PATH) == compensatedCount2 + 1);
        }
    }

    private void checkStatusAndCloseResponse(Response.Status expectedStatus, Response response, WebTarget resourcePath) {
        try {
            assertEquals("Not expected status at call '" + resourcePath.getUri() + "'",
                    expectedStatus.getStatusCode(), response.getStatus());
        } finally {
            response.close();
        }
    }

    private String checkStatusReadAndCloseResponse(Response.Status expectedStatus, Response response, WebTarget resourcePath) {
        try {
            assertEquals("Response status on call to '" + resourcePath.getUri() + "' failed to match.",
                    expectedStatus.getStatusCode(), response.getStatus());
            return response.readEntity(String.class);
        } finally {
            response.close();
        }
    }

    private int getCompletedCount() {
        return getActivityCount("completedactivitycount");
    }

    private int getCompensatedCount() {
        return getActivityCount("compensatedactivitycount");
    }

    private int getActivityCount(String activityCountTargetPath) {
        return getActivityCount(LRA_CONTROLLER_PATH, activityCountTargetPath);
    }

    private int getActivityCount(String basePath, String activityCountTargetPath) {
        WebTarget resourcePath = tckSuiteTarget.path(basePath)
                .path(activityCountTargetPath);

        Response response = resourcePath.request().get();
        String count = checkStatusReadAndCloseResponse(Response.Status.OK, response, resourcePath);
        return Integer.parseInt(count);
    }

    private void multiLevelNestedActivity(CompletionType how, int nestedCnt) throws WebApplicationException {
        WebTarget resourcePath = tckSuiteTarget.path(LRA_CONTROLLER_PATH).path("multiLevelNestedActivity");

        int beforeCompletedCount = getCompletedCount();
        int beforeCompensatedCount = getCompensatedCount();

        if (how == CompletionType.mixed && nestedCnt <= 1) {
            how = CompletionType.complete;
        }

        URI lra = lraClient.startLRA(null, lraClientId(), lraTimeout(), ChronoUnit.MILLIS);
        String lraId = lra.toString();

        Response response = resourcePath
                .queryParam("nestedCnt", nestedCnt)
                .request()
                .header(LRA_HTTP_CONTEXT_HEADER, lra)
                .put(Entity.text(""));

        String lraStr = checkStatusReadAndCloseResponse(Response.Status.OK, response, resourcePath);
        assertNotNull("expecting a LRA string returned from " + resourcePath.getUri(), lraStr);
        String[] lraArray = lraStr.split(",");
        URI[] uris = new URI[lraArray.length];

        IntStream.range(0, uris.length).forEach(i -> {
            try {
                uris[i] = new URI(lraArray[i]);
            } catch (URISyntaxException e) {
                fail(String.format("%s (multiLevelNestedActivity): returned an invalid URI: %s",
                        resourcePath.getUri().toString(), e.getMessage()));
            }
        });
        // check that the multiLevelNestedActivity method returned the mandatory LRA followed by any nested LRAs
        assertEquals("multiLevelNestedActivity: step 1 (the test call went to " + resourcePath.getUri() + ")",
                nestedCnt + 1, lraArray.length);
        // first element should be the mandatory LRA
        assertEquals("multiLevelNestedActivity: step 2 (the test call went to " + resourcePath.getUri() + ")",
                lraId, lraArray[0]);

        // and the mandatory lra seen by the multiLevelNestedActivity method
        assertFalse("multiLevelNestedActivity: top level LRA should be active (path called " + resourcePath.getUri() + ")",
                lraClient.isLRAFinished(lraArray[0]));

        int inMiddleCompletedCount = getCompletedCount();
        int inMiddleCompensatedCount = getCompensatedCount();

        // check that all nested activities were told to complete
        assertEquals("multiLevelNestedActivity: step 3 (called test path " + resourcePath.getUri() + ")",
                beforeCompletedCount + nestedCnt, inMiddleCompletedCount);
        // and that neither were told to compensate
        assertEquals("multiLevelNestedActivity: step 4 (called test path " + resourcePath.getUri() + ")",
                beforeCompensatedCount, inMiddleCompensatedCount);

        // close the LRA
        if (how == CompletionType.compensate) {
            lraClient.cancelLRA(lra);
        } else if (how == CompletionType.complete) {
            lraClient.closeLRA(lra);
        } else {
            /*
             * The test is calling for a mixed outcome (a top level LRA L! and nestedCnt nested LRAs (L2, L3, ...)::
             * L1 the mandatory call (PUT "activities/multiLevelNestedActivity") registers participant C1
             *   the resource makes nestedCnt calls to "activities/nestedActivity" each of which create nested LRAs
             * L2, L3, ... each of which enlists a participant (C2, C3, ...) which are completed when the call returns
             * L2 is canceled  which causes C2 to compensate
             * L1 is closed which triggers the completion of C1
             *
             * To summarise:
             *
             * - C1 is completed
             * - C2 is completed and then compensated
             * - C3, ... are completed
             */
            lraClient.cancelLRA(uris[1]); // compensate the first nested LRA
            lraClient.closeLRA(lra); // should not complete any nested LRAs (since they have already completed via the interceptor)
        }

        // validate that the top level and nested LRAs are gone
        IntStream.rangeClosed(0, nestedCnt).forEach(i -> assertTrue(
                String.format("multiLevelNestedActivity: %s LRA still active (resouce path was %s)",
                        (i == 0 ? "top level" : "nested"), resourcePath.getUri()),
                lraClient.isLRAFinished(lraArray[i])));

        int afterCompletedCount = getCompletedCount();
        int afterCompensatedCount = getCompensatedCount();

        if (how == CompletionType.complete) {
            // make sure that all nested activities were not told to complete or cancel a second time
            assertEquals("multiLevelNestedActivity: step 5 (called test path " + resourcePath.getUri() + ")",
                    inMiddleCompletedCount + nestedCnt, afterCompletedCount);
            // and that neither were still not told to compensate
            assertEquals("multiLevelNestedActivity: step 6 (called test path " + resourcePath.getUri() + ")",
                    beforeCompensatedCount, afterCompensatedCount);

        } else if (how == CompletionType.compensate) {
            /*
             * the test starts LRA1 calls a @Mandatory method multiLevelNestedActivity which enlists in LRA1
             * multiLevelNestedActivity then calls an @Nested method which starts L2 and enlists another participant
             *   when the method returns the nested participant is completed (ie completed count is incremented)
             * Canceling L1 should then compensate the L1 enlistement (ie compensate count is incrememted)
             * which will then tell L2 to compenstate (ie the compensate count is incrememted again)
             */
            // each nested participant should have completed (the +nestedCnt)
            assertEquals("multiLevelNestedActivity: step 7 (called test path " + resourcePath.getUri() + ")",
                    beforeCompletedCount + nestedCnt, afterCompletedCount);
            // each nested participant should have compensated. The top level enlistement should have compensated (the +1)
            assertEquals("multiLevelNestedActivity: step 8 (called test path " + resourcePath.getUri() + ")",
                    inMiddleCompensatedCount + 1 + nestedCnt, afterCompensatedCount);
        } else {
            /*
             * The test is calling for a mixed outcome:
             * - the top level LRA was closed
             * - one of the nested LRAs was compensated the rest should have been completed
             */
            // there should be just 1 compensation (the first nested LRA)
            assertEquals("multiLevelNestedActivity: step 9 (called test path " + resourcePath.getUri() + ")",
                    1, afterCompensatedCount - beforeCompensatedCount);
            /*
             * Expect nestedCnt + 1 completions, 1 for the top level and one for each nested LRA
             * (NB the first nested LRA is completed and compensated)
             * Note that the top level complete should not call complete again on the nested LRA
             */
            assertEquals("multiLevelNestedActivity: step 10 (called test path " + resourcePath.getUri() + ")",
                    nestedCnt + 1, afterCompletedCount - beforeCompletedCount);
        }
    }

    /**
     * The started LRA will be named based on the class name and the running test name.
     */
    private String lraClientId() {
        return this.getClass().getSimpleName() + "#" + testName.getMethodName();
    }

    /**
     * Adjusting the default timeout by the specified timeout factor
     * which can be defined by user.
     */
    private long lraTimeout() {
        return Util.adjust(LraTckConfigBean.LRA_TIMEOUT_MILLIS, config.timeoutFactor());
    }
}
