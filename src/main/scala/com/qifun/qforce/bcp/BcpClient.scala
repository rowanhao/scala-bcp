package com.qifun.qforce.bcp

import java.util.concurrent.ScheduledExecutorService
import java.nio.ByteBuffer
import java.nio.channels.AsynchronousSocketChannel
import java.util.concurrent.Executor
import scala.concurrent.stm.InTxn
import com.qifun.statelessFuture.Future
import com.qifun.qforce.bcp.Bcp._
import com.qifun.qforce.bcp.BcpSession._
import scala.util.control.Exception.Catcher
import scala.PartialFunction
import scala.reflect.classTag
import scala.concurrent.stm.atomic
import scala.concurrent.stm.Ref
import java.security.SecureRandom
import com.dongxiguo.fastring.Fastring.Implicits._
import scala.concurrent.stm.Txn
import com.qifun.statelessFuture.util.Sleep
import java.util.concurrent.Executors
import scala.concurrent.duration._
import java.util.concurrent.ScheduledFuture

object BcpClient {

  private implicit val (logger, formatter, appender) = ZeroLoggerFactory.newLogger(this)

  private[BcpClient] final class Stream(bcpClient: BcpClient, socket: AsynchronousSocketChannel, internalExecutor: ScheduledExecutorService) extends BcpSession.Stream(socket) {
    // 客户端专有的数据结构，比如Timer
    val executor: ScheduledExecutorService = internalExecutor
    val belongedClient = bcpClient
    val timer = Ref.make[ScheduledFuture[_]]
  }

  private[BcpClient] final class Connection extends BcpSession.Connection[Stream] {

    override private[bcp] final def busy()(implicit txn: InTxn): Unit = {
      atomic { implicit txn =>
        logger.info("the connection is busy!")
        stream().timer() = stream().executor.schedule(new Runnable() {
          def run() {
            logger.info("client connect server again")
            stream().belongedClient.internalConnect()
          }
        }, 300, MILLISECONDS)
      }
    }

    override private[bcp] final def idle()(implicit txn: InTxn): Unit = {
      // TODO: 设置timer，关闭多余的连接
      atomic { implicit txn =>
        logger.info("the connection is idle!")
        stream().timer().cancel(false)
      }
    }
  }

}

abstract class BcpClient extends BcpSession[BcpClient.Stream, BcpClient.Connection] {

  import BcpClient.{ logger, formatter, appender }

  override private[bcp] final def newConnection = new BcpClient.Connection

  protected def connect(): Future[AsynchronousSocketChannel]

  protected def executor: ScheduledExecutorService

  override private[bcp] final def internalExecutor: ScheduledExecutorService = executor

  override private[bcp] final def release()(implicit txn: InTxn) {}

  private val sessionId: Array[Byte] = Array.ofDim[Byte](NumBytesSessionId)
  private val nextConnectionId = Ref(0)

  private[bcp] final def internalConnect()(implicit txn: InTxn) {
    if (connections.size <= MaxConnectionsPerSession) {
      implicit def catcher: Catcher[Unit] = PartialFunction.empty
      for (socket <- connect()) {
        logger.fine(fast"bcp client connect server success, socket: ${socket}")
        val stream = new BcpClient.Stream(this, socket, internalExecutor)
        atomic { implicit txn =>
          val connectionId = nextConnectionId()
          nextConnectionId() = connectionId + 1
          Txn.afterCommit(_ => {
            BcpIo.enqueueHead(stream, ConnectionHead(sessionId, connectionId))
            stream.flush()
            logger.fine(fast"bcp client send head to server success, sessionId: ${sessionId.toSeq} , connectionId: ${connectionId}")
          })
          addStream(connectionId, stream)
        }
      }
    }
  }

  private final def start() {
    atomic { implicit txn: InTxn =>
      val secureRandom = new SecureRandom
      secureRandom.setSeed(secureRandom.generateSeed(20))
      secureRandom.nextBytes(sessionId)
      internalConnect()
    }
  }

  start()

}