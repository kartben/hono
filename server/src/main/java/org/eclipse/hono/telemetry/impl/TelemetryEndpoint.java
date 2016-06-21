/**
 * Copyright (c) 2016 Bosch Software Innovations GmbH.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *    Bosch Software Innovations GmbH - initial creation
 */
package org.eclipse.hono.telemetry.impl;

import static org.eclipse.hono.telemetry.TelemetryConstants.EVENT_BUS_ADDRESS_TELEMETRY_FLOW_CONTROL;
import static org.eclipse.hono.telemetry.TelemetryConstants.EVENT_BUS_ADDRESS_TELEMETRY_IN;
import static org.eclipse.hono.telemetry.TelemetryConstants.EVENT_BUS_ADDRESS_TELEMETRY_LINK_CONTROL;
import static org.eclipse.hono.telemetry.TelemetryConstants.FIELD_NAME_CLOSE_LINK;
import static org.eclipse.hono.telemetry.TelemetryConstants.FIELD_NAME_LINK_ID;
import static org.eclipse.hono.telemetry.TelemetryConstants.FIELD_NAME_SUSPEND;
import static org.eclipse.hono.telemetry.TelemetryConstants.MSG_TYPE_ERROR;
import static org.eclipse.hono.telemetry.TelemetryConstants.MSG_TYPE_FLOW_CONTROL;
import static org.eclipse.hono.telemetry.TelemetryConstants.getLinkAttachedMsg;
import static org.eclipse.hono.telemetry.TelemetryConstants.getLinkDetachedMsg;
import static org.eclipse.hono.telemetry.TelemetryConstants.isErrorMessage;
import static org.eclipse.hono.telemetry.TelemetryConstants.isFlowControlMessage;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.qpid.proton.message.Message;
import org.eclipse.hono.AmqpMessage;
import org.eclipse.hono.server.BaseEndpoint;
import org.eclipse.hono.telemetry.TelemetryConstants;
import org.eclipse.hono.telemetry.TelemetryMessageFilter;
import org.eclipse.hono.util.ResourceIdentifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.vertx.core.Vertx;
import io.vertx.core.eventbus.DeliveryOptions;
import io.vertx.core.eventbus.MessageConsumer;
import io.vertx.core.json.JsonObject;
import io.vertx.proton.ProtonDelivery;
import io.vertx.proton.ProtonHelper;
import io.vertx.proton.ProtonQoS;
import io.vertx.proton.ProtonReceiver;

/**
 * A Hono {@code Endpoint} for uploading telemetry data.
 *
 */
public final class TelemetryEndpoint extends BaseEndpoint {

    private static final Logger             LOG                   = LoggerFactory.getLogger(TelemetryEndpoint.class);
    private Map<String, LinkWrapper>        activeClients         = new HashMap<>();
    private MessageConsumer<JsonObject>     flowControlConsumer;
    private String                          flowControlAddress;
    private String                          linkControlAddress;
    private String                          dataAddress;

    public TelemetryEndpoint(final Vertx vertx, final boolean singleTenant) {
        this(vertx, singleTenant, 0);
    }

    public TelemetryEndpoint(final Vertx vertx, final boolean singleTenant, final int instanceId) {
        super(Objects.requireNonNull(vertx), singleTenant, instanceId);
        registerFlowControlConsumer();

        linkControlAddress = getAddressForInstanceNo(EVENT_BUS_ADDRESS_TELEMETRY_LINK_CONTROL);
        LOG.info("publishing downstream link control messages on event bus [address: {}]", linkControlAddress);
        dataAddress = getAddressForInstanceNo(EVENT_BUS_ADDRESS_TELEMETRY_IN);
        LOG.info("publishing downstream telemetry messages on event bus [address: {}]", dataAddress);
    }

    private void registerFlowControlConsumer() {
        flowControlAddress = getAddressForInstanceNo(EVENT_BUS_ADDRESS_TELEMETRY_FLOW_CONTROL);
        flowControlConsumer = this.vertx.eventBus().consumer(flowControlAddress, this::handleFlowControlMsg);
        LOG.info("listening on event bus [address: {}] for downstream flow control messages",
                flowControlAddress);
    }

    private void handleFlowControlMsg(final io.vertx.core.eventbus.Message<JsonObject> msg) {
        vertx.runOnContext(run -> {
            if (msg.body() == null) {
                LOG.warn("received empty message from telemetry adapter, is this a bug?");
            } else if (isFlowControlMessage(msg.body())) {
                handleFlowControlMsg(msg.body().getJsonObject(MSG_TYPE_FLOW_CONTROL));
            } else if (isErrorMessage(msg.body())) {
                handleErrorMessage(msg.body().getJsonObject(MSG_TYPE_ERROR));
            } else {
                // discard message
                LOG.debug("received unsupported message from telemetry adapter: {}", msg.body().encode());
            }
        });
    }

    private void handleFlowControlMsg(final JsonObject msg) {
        if (LOG.isTraceEnabled()) {
            LOG.trace("received flow control message from telemetry adapter: {}", msg.encodePrettily());
        }
        String linkId = msg.getString(FIELD_NAME_LINK_ID);
        LinkWrapper client = activeClients.get(linkId);
        if (client != null) {
            client.setSuspended(msg.getBoolean(FIELD_NAME_SUSPEND, false));
        }
    }

    private void handleErrorMessage(final JsonObject msg) {
        if (LOG.isTraceEnabled()) {
            LOG.trace("received error message from telemetry adapter: {}", msg.encodePrettily());
        }
        String linkId = msg.getString(FIELD_NAME_LINK_ID);
        LinkWrapper client = activeClients.get(linkId);
        if (client != null) {
            boolean closeLink = msg.getBoolean(FIELD_NAME_CLOSE_LINK, false);
            if (closeLink) {
                onLinkDetach(client);
            }
        }
    }

    @Override
    public String getName() {
        return TelemetryConstants.TELEMETRY_ENDPOINT;
    }

    @Override
    public void onLinkAttach(final ProtonReceiver receiver, final ResourceIdentifier targetAddress) {
        if (ProtonQoS.AT_LEAST_ONCE.equals(receiver.getRemoteQoS())) {
            LOG.debug("client wants to use AT LEAST ONCE delivery mode, ignoring ...");
        }
        final String linkId = UUID.randomUUID().toString();
        final LinkWrapper link = new LinkWrapper(linkId, receiver);

        receiver.closeHandler(clientDetached -> {
            // client has closed link -> inform TelemetryAdapter about client detach
            onLinkDetach(link);
        }).handler((delivery, message) -> {
            final ResourceIdentifier messageAddress = getResourceIdentifier(message.getAddress());
            if (TelemetryMessageFilter.verify(targetAddress, messageAddress, message)) {
                sendTelemetryData(link, delivery, message, messageAddress);
            } else {
                onLinkDetach(link);
            }
        }).open();

        LOG.debug("registering new link for client [{}]", linkId);
        activeClients.put(linkId, link);
        JsonObject msg = getLinkAttachedMsg(linkId, targetAddress);
        DeliveryOptions headers = TelemetryConstants.addReplyToHeader(new DeliveryOptions(), flowControlAddress);
        vertx.eventBus().send(linkControlAddress, msg, headers);
    }

    private void onLinkDetach(final LinkWrapper client) {
        LOG.debug("closing receiver for client [{}]", client.getLinkId());
        client.close();
        activeClients.remove(client.getLinkId());
        JsonObject msg = getLinkDetachedMsg(client.getLinkId());
        vertx.eventBus().send(linkControlAddress, msg);
    }

    private void sendTelemetryData(final LinkWrapper link, final ProtonDelivery delivery, final Message msg, final ResourceIdentifier messageAddress) {
        if (!delivery.remotelySettled()) {
            LOG.trace("received un-settled telemetry message on link [{}]", link.getLinkId());
        }
        checkPermission(messageAddress, permissionGranted -> {
            if (permissionGranted) {
                vertx.runOnContext(run -> {
                    final String messageId = UUID.randomUUID().toString();
                    final AmqpMessage amqpMessage = AmqpMessage.of(msg, delivery);
                    vertx.sharedData().getLocalMap(dataAddress).put(messageId, amqpMessage);
                    sendAtMostOnce(link, messageId, delivery);
                });
            } else {
                LOG.debug("client is not authorized to upload telemetry data for address [{}], closing link ...", messageAddress);
                onLinkDetach(link); // inform downstream adapter about client detach
            }
        });
    }

    private void sendAtMostOnce(final LinkWrapper link, final String messageId, final ProtonDelivery delivery) {
        vertx.eventBus().send(dataAddress, TelemetryConstants.getTelemetryMsg(messageId, link.getLinkId()));
        ProtonHelper.accepted(delivery, true);
        int creditLeft = link.decrementAndGetRemainingCredit();
        LOG.trace("publishing telemetry msg received via Link[id: {}, credit left: {}] to {}",
                link.getLinkId(), creditLeft, dataAddress);
    }

    public static class LinkWrapper {
        private static final int INITIAL_CREDIT = 50;
        private ProtonReceiver link;
        private String id;
        private boolean suspended;
        private AtomicInteger credit = new AtomicInteger(INITIAL_CREDIT);

        /**
         * @param receiver
         */
        public LinkWrapper(final String linkId, final ProtonReceiver receiver) {
            this.id = Objects.requireNonNull(linkId);
            link = Objects.requireNonNull(receiver);
            link.setAutoAccept(false).setQoS(ProtonQoS.AT_MOST_ONCE).setPrefetch(0); // use manual flow control
            suspend();
        }

        /**
         * @return the creditAvailable
         */
        public boolean isSuspended() {
            return suspended;
        }

        public void suspend() {
            LOG.debug("suspending client [{}]", id);
            suspended = true;
        }

        public void resume() {
            credit.set(INITIAL_CREDIT);
            LOG.debug("replenishing client [{}] with {} credits", id, credit.get());
            link.flow(credit.get());
            suspended = false;
        }

        public void setSuspended(final boolean suspend) {
            if (suspend) {
                suspend();
            } else {
                resume();
            }
        }

        public int decrementAndGetRemainingCredit() {
            if (credit.decrementAndGet() == 0) {
                if (!suspended) {
                    // automatically replenish client with new credit
                    resume();
                }
            }
            return credit.get();
        }

        public void close() {
            link.close();
        }

        /**
         * @return the link
         */
        public ProtonReceiver getLink() {
            return link;
        }

        /**
         * @return the link ID
         */
        public String getLinkId() {
            return id;
        }
    }
}