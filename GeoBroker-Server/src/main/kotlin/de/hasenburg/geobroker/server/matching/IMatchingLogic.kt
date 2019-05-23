package de.hasenburg.geobroker.server.matching

import de.hasenburg.geobroker.commons.model.message.ControlPacketType
import de.hasenburg.geobroker.commons.model.message.ReasonCode
import de.hasenburg.geobroker.commons.model.message.Topic
import de.hasenburg.geobroker.commons.model.message.payloads.*
import de.hasenburg.geobroker.commons.model.spatial.Geofence
import de.hasenburg.geobroker.commons.model.spatial.Location
import de.hasenburg.geobroker.server.communication.InternalServerMessage
import de.hasenburg.geobroker.server.storage.TopicAndGeofenceMapper
import de.hasenburg.geobroker.server.storage.client.ClientDirectory
import org.apache.commons.lang3.tuple.ImmutablePair
import org.apache.logging.log4j.Logger
import org.zeromq.ZMQ
import org.zeromq.ZMQ.Socket

/**
 * Message Processing Notes <br></br>
 * - we already validated the messages above using #buildMessage() <br></br>
 * -> we expect the payload to be compatible with the control packet type <br></br>
 * -> we expect all fields to be set
 */
interface IMatchingLogic {

    fun processCONNECT(message: InternalServerMessage, clients: Socket, brokers: Socket)

    fun processDISCONNECT(message: InternalServerMessage, clients: Socket, brokers: Socket)

    fun processPINGREQ(message: InternalServerMessage, clients: Socket, brokers: Socket)

    fun processSUBSCRIBE(message: InternalServerMessage, clients: Socket, brokers: Socket)

    fun processUNSUBSCRIBE(message: InternalServerMessage, clients: Socket, brokers: Socket)

    fun processPUBLISH(message: InternalServerMessage, clients: Socket, brokers: Socket)

    fun processBrokerForwardDisconnect(message: InternalServerMessage, clients: Socket, brokers: Socket)

    fun processBrokerForwardPingreq(message: InternalServerMessage, clients: Socket, brokers: Socket)

    fun processBrokerForwardSubscribe(message: InternalServerMessage, clients: Socket, brokers: Socket)

    fun processBrokerForwardUnsubscribe(message: InternalServerMessage, clients: Socket, brokers: Socket)

    fun processBrokerForwardPublish(message: InternalServerMessage, clients: Socket, brokers: Socket)

}

/*****************************************************************
 * Common Matching Tasks
 ****************************************************************/

fun connectClientAtLocalBroker(clientIdentifier: String, location: Location, clientDirectory: ClientDirectory,
                               logger: Logger): InternalServerMessage {

    val success = clientDirectory.addClient(clientIdentifier, location)

    if (success) {
        logger.debug("Created client {}, acknowledging.", clientIdentifier)
        return InternalServerMessage(clientIdentifier, ControlPacketType.CONNACK, CONNACKPayload(ReasonCode.Success))
    } else {
        logger.debug("Client {} already exists, so protocol error. Disconnecting.", clientIdentifier)
        clientDirectory.removeClient(clientIdentifier)
        return InternalServerMessage(clientIdentifier,
                ControlPacketType.DISCONNECT,
                DISCONNECTPayload(ReasonCode.ProtocolError))
    }
}

fun updateClientLocationAtLocalBroker(clientIdentifier: String, location: Location, clientDirectory: ClientDirectory,
                                      logger: Logger): InternalServerMessage {

    val success = clientDirectory.updateClientLocation(clientIdentifier, location)
    if (success) {
        logger.debug("Updated location of {} to {}", clientIdentifier, location)

        return InternalServerMessage(clientIdentifier,
                ControlPacketType.PINGRESP,
                PINGRESPPayload(ReasonCode.LocationUpdated))
    } else {
        logger.debug("Client {} is not connected", clientIdentifier)
        return InternalServerMessage(clientIdentifier,
                ControlPacketType.PINGRESP,
                PINGRESPPayload(ReasonCode.NotConnected))
    }

}

fun subscribeAtLocalBroker(clientIdentifier: String, clientDirectory: ClientDirectory,
                           topicAndGeofenceMapper: TopicAndGeofenceMapper, topic: Topic, geofence: Geofence,
                           logger: Logger): ReasonCode {

    val subscribed: ImmutablePair<ImmutablePair<String, Int>, Geofence>? = clientDirectory.checkIfSubscribed(
            clientIdentifier,
            topic,
            geofence)

    // if already subscribed -> remove subscription id from now unrelated geofence parts
    subscribed?.let { topicAndGeofenceMapper.removeSubscriptionId(subscribed.left, topic, subscribed.right) }

    val subscriptionId = clientDirectory.updateSubscription(clientIdentifier, topic, geofence)

    if (subscriptionId == null) {
        logger.debug("Client {} is not connected", clientIdentifier)
        return ReasonCode.NotConnected
    } else {
        topicAndGeofenceMapper.putSubscriptionId(subscriptionId, topic, geofence)
        logger.debug("Client {} subscribed to topic {} and geofence {}", clientIdentifier, topic, geofence)
        return ReasonCode.GrantedQoS0
    }
}

fun unsubscribeAtLocalBroker(clientIdentifier: String, clientDirectory: ClientDirectory,
                             topicAndGeofenceMapper: TopicAndGeofenceMapper, topic: Topic, geofence: Geofence,
                             logger: Logger): ReasonCode {
    var reasonCode = ReasonCode.Success

    // unsubscribe from client directory -> get subscription id
    val s = clientDirectory.removeSubscription(clientIdentifier, topic)

    // remove from storage if existed
    if (s != null) {
        topicAndGeofenceMapper.removeSubscriptionId(s.subscriptionId, s.topic, s.geofence)
        logger.debug("Client $clientIdentifier unsubscribed from $topic topic, subscription had the id ${s.subscriptionId}")
    } else {
        logger.debug("Client $clientIdentifier has no subscription with topic $topic, thus unable to unsubscribe")
        reasonCode = ReasonCode.NoSubscriptionExisted
    }

    return reasonCode
}

/**
 * @param publisherLocation - the location of the publisher
 */
fun publishMessageToLocalClients(publisherLocation: Location, publishPayload: PUBLISHPayload,
                                 clientDirectory: ClientDirectory, topicAndGeofenceMapper: TopicAndGeofenceMapper,
                                 clients: Socket, logger: Logger): ReasonCode {

    logger.debug("Publishing topic {} to all subscribers", publishPayload.topic)

    // get subscriptions that have a geofence containing the publisher location
    val subscriptionIds = topicAndGeofenceMapper.getSubscriptionIds(publishPayload.topic, publisherLocation)

    // only keep subscription if subscriber location is insider message geofence
    subscriptionIds.removeIf { subId ->
        !publishPayload.geofence.contains(clientDirectory.getClientLocation(subId.left)!!)
    }

    // publish message to remaining subscribers
    for (subscriptionId in subscriptionIds) {
        val subscriberClientIdentifier = subscriptionId.left
        logger.debug("Client {} is a subscriber", subscriberClientIdentifier)
        val toPublish = InternalServerMessage(subscriberClientIdentifier, ControlPacketType.PUBLISH, publishPayload)
        logger.trace("Publishing $toPublish")
        toPublish.zMsg.send(clients)
    }

    if (subscriptionIds.isEmpty()) {
        logger.debug("No subscriber exists.")
        return ReasonCode.NoMatchingSubscribers
    } else {
        return ReasonCode.Success
    }

}