/*
 * Copyright (c) 2011 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.cometd.javascript;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests that handshake failures will backoff correctly
 */
public class CometDHandshakeFailureTest extends AbstractCometDTest
{
    @Test
    public void testHandshakeFailure() throws Exception
    {
        bayeuxServer.addExtension(new HandshakeThrowingExtension());

        defineClass(Latch.class);
        evaluateScript("cometd.configure({url: '" + cometdURL + "', logLevel: '" + getLogLevel() + "'});");
        evaluateScript("var handshakeLatch = new Latch(1);");
        Latch handshakeLatch = get("handshakeLatch");
        evaluateScript("var failureLatch = new Latch(1);");
        Latch failureLatch = get("failureLatch");
        evaluateScript("cometd.addListener('/meta/handshake', handshakeLatch, handshakeLatch.countDown);");
        evaluateScript("cometd.addListener('/meta/unsuccessful', failureLatch, failureLatch.countDown);");

        evaluateScript("var backoff = cometd.getBackoffPeriod();");
        evaluateScript("var backoffIncrement = cometd.getBackoffIncrement();");
        int backoff = ((Number)get("backoff")).intValue();
        final int backoffIncrement = ((Number)get("backoffIncrement")).intValue();
        Assert.assertEquals(0, backoff);
        Assert.assertTrue(backoffIncrement > 0);

        evaluateScript("cometd.handshake();");
        Assert.assertTrue(handshakeLatch.await(5000));
        Assert.assertTrue(failureLatch.await(5000));

        // There is a failure, the backoff will be increased from 0 to backoffIncrement
        Thread.sleep(backoffIncrement / 2); // Waits for the backoff to happen
        evaluateScript("var backoff = cometd.getBackoffPeriod();");
        backoff = ((Number)get("backoff")).intValue();
        Assert.assertEquals(backoffIncrement, backoff);

        handshakeLatch.reset(1);
        failureLatch.reset(1);
        Assert.assertTrue(handshakeLatch.await(backoffIncrement));
        Assert.assertTrue(failureLatch.await(backoffIncrement));

        // Another failure, backoff will be increased to 2 * backoffIncrement
        Thread.sleep(backoffIncrement / 2); // Waits for the backoff to happen
        evaluateScript("var backoff = cometd.getBackoffPeriod();");
        backoff = ((Number)get("backoff")).intValue();
        Assert.assertEquals(2 * backoffIncrement, backoff);

        handshakeLatch.reset(1);
        failureLatch.reset(1);
        Assert.assertTrue(handshakeLatch.await(2 * backoffIncrement));
        Assert.assertTrue(failureLatch.await(2 * backoffIncrement));

        // Disconnect so that handshake is not performed anymore
        evaluateScript("var disconnectLatch = new Latch(1);");
        Latch disconnectLatch = get("disconnectLatch");
        failureLatch.reset(1);
        evaluateScript("cometd.addListener('/meta/disconnect', disconnectLatch, disconnectLatch.countDown);");
        evaluateScript("cometd.disconnect();");
        Assert.assertTrue(disconnectLatch.await(5000));
        Assert.assertTrue(failureLatch.await(5000));
        String status = evaluateScript("cometd.getStatus();");
        Assert.assertEquals("disconnected", status);

        // Be sure the handshake is not retried anymore
        handshakeLatch.reset(1);
        Assert.assertFalse(handshakeLatch.await(4 * backoffIncrement));
    }

    public static class HandshakeThrowingExtension implements BayeuxServer.Extension
    {
        public boolean rcv(ServerSession from, ServerMessage.Mutable message)
        {
            return true;
        }

        public boolean rcvMeta(ServerSession from, ServerMessage.Mutable message)
        {
            if (Channel.META_HANDSHAKE.equals(message.getChannel()))
                throw new Error("explicitly_thrown_by_test");
            return true;
        }

        public boolean send(ServerSession from, ServerSession to, ServerMessage.Mutable message)
        {
            return true;
        }

        public boolean sendMeta(ServerSession to, ServerMessage.Mutable message)
        {
            return true;
        }
    }
}
