package de.hasenburg.geobroker.commons.communication

import de.hasenburg.geobroker.commons.randomName
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.apache.logging.log4j.LogManager
import org.zeromq.SocketType
import org.zeromq.ZContext
import org.zeromq.ZMQ
import org.zeromq.ZMQ.Socket
import org.zeromq.ZMsg
import kotlin.math.roundToInt

private val logger = LogManager.getLogger()

/**
 * The [SPDealer] (Split Personality Dealer) is a special kind of ZeroMQ [SocketType.DEALER] socket that
 * - uses Coroutines (easier API through suspend functionality)
 * - manages multiple individual [SocketType.DEALER] sockets that all connect to the same other [Socket] (multi-tenancy)
 *
 * TODO add how to use; requirements for processed ZMsg format
 *
 * [ObsoleteCoroutinesApi] necessary, as the [SPDealer] uses its own ThreadPool and the API is being changed, soon.
 */
class SPDealer(val ip: String = "localhost", val port: Int = 5559) {

    // constants
    private val measurementInterval: Long = 10 // in seconds
    private val pullSocketAddress = "inproc://" + randomName()

    // channels
    val toSent: Channel<ZMsg> = Channel(1000)
    val wasSent: Channel<ZMsgTP> = Channel(Channel.UNLIMITED)
    val wasReceived: Channel<ZMsgTP> = Channel(Channel.UNLIMITED)

    // context, poller, pull socket, and push socket
    private val zContext = ZContext(1)
    private val poller = zContext.createPoller(1)
    private val puller = zContext.createSocket(SocketType.PULL).also {
        it.bind(pullSocketAddress)
        poller.register(it, ZMQ.Poller.POLLIN)
    }
    private val pusher = zContext.createSocket(SocketType.PUSH).also { it.connect(pullSocketAddress) }

    // dealer sockets
    private val indexMap = HashMap<String, Int>()
    private val socketList = ArrayList<Socket>()
    val numberOfPersonalities
        get() = socketList.size

    // thread pool (has to be closed on shutdown, coroutine API changes incoming)
    private val threadPool = newFixedThreadPoolContext(2, "SPDealerPool")

    // shutdown error handler and job
    private val eh = CoroutineExceptionHandler { _, e ->
        logger.error("Unknown Exception, shutting down SPDealer", e)
        shutdown()
    }
    private val job = GlobalScope.launch(eh + threadPool) {
        launch { processToSendBlocking() }
        launch { sendAndReceiveBlocking() }
    }
    val isActive: Boolean
        get() = job.isActive

    fun shutdown() {
        job.cancel()
        zContext.destroy()
        threadPool.close()
        toSent.close()
        wasSent.close()
        wasReceived.close()
        logger.info("Shutdown of SPDealer completed")
    }

    private suspend fun processToSendBlocking() = coroutineScope {
        for (msg in toSent) {
            logger.trace("Scheduling $msg for sending")
            msg.send(pusher)
        }
        logger.debug("ToSent channel was closed, shutting down.")
    }

    private suspend fun sendAndReceiveBlocking() = coroutineScope {
        var pollTime: Long = 0 // in ns
        var processingTime: Long = 0 // in ns
        var newTime: Long
        var oldTime: Long

        logger.info("Started communication with target socket at $ip:$port")

        while (isActive) {
            oldTime = System.nanoTime()

            val objects = poller.poll(1000)

            newTime = System.nanoTime()
            pollTime += newTime - oldTime
            oldTime = System.nanoTime()

            if (objects > 0) {
                if (poller.pollin(0)) {
                    onPullSocketMessage()
                } else {
                    onDealerSocketMessage()
                }
            }

            newTime = System.nanoTime()
            processingTime += newTime - oldTime

            // add utilization roughly every 10 seconds
            if (pollTime + processingTime >= measurementInterval * 1000000000L) {
                var utilization = processingTime / (processingTime.toDouble() + pollTime.toDouble() + 0.0)
                utilization = (utilization * 1000.0).roundToInt() / 10.0
                logger.debug("Current Utilization is {}%", utilization)
                pollTime = 0
                processingTime = 0
            }
        }
        logger.debug("Job is not active anymore, shutting down.")
    }

    /**
     * Received a message via the [puller] socket that is supposed to be sent..
     */
    private suspend fun onPullSocketMessage() {
        val msg = ZMsg.recvMsg(puller)
        val thingId = msg.popString()

        logger.trace("Processing scheduled message for $thingId")

        // get dealer socket, create a new one if none existed yet
        var index = indexMap[thingId]
        if (index == null) {
            logger.debug("Thing $thingId did not have a dealer socket yet, creating a new one")
            zContext.createSocket(SocketType.DEALER).also {
                it.identity = thingId.toByteArray()
                it.connect("tcp://$ip:$port")
                poller.register(it, ZMQ.Poller.POLLIN)
                socketList.add(it)
            }

            index = socketList.size - 1
            indexMap[thingId] = index
        }
        msg.send(socketList[index], false) // send via correct socket
        logger.trace("Send message $msg for $thingId to target socket")
        wasSent.send(ZMsgTP(msg.addFirst(thingId), System.currentTimeMillis())) // add to wasSent channel
    }

    /**
     * Received a message via one of the managed dealers.
     */
    private suspend fun onDealerSocketMessage() {
        for (socketIndex in socketList.indices) {
            val pollerIndex = socketIndex + 1 // poller also contains pull socket
            if (poller.pollin(pollerIndex)) {
                val thingId = String(socketList[socketIndex].identity)
                val msg = ZMsg.recvMsg(socketList[socketIndex])

                if (msg == null) {
                    logger.warn("Received a null message")
                } else {
                    logger.trace("Received message for client {}", thingId)
                    wasReceived.send(ZMsgTP(msg.addFirst(thingId), System.currentTimeMillis()))
                }
                return // we already found the correct dealer socket, so return
            }
        }
        // if we get here, we screwed up programming
        logger.error("Received a message via a socket that is not inside the socket list, this should never happen!")
    }

}

/**
 * A [ZMsgTP] (ZMsg Time Pair) comprises a [ZMsg] and a unix timestamp as [Long].
 */
data class ZMsgTP(val msg: ZMsg, val timestamp: Long)