package com.qifun.qforce.bcp.server

import com.dongxiguo.fastring.Fastring.Implicits._
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.AsynchronousSocketChannel
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import scala.annotation.tailrec
import scala.collection.mutable.WrappedArray
import scala.concurrent.duration.DurationInt
import scala.concurrent.stm.InTxn
import scala.concurrent.stm.Ref
import scala.concurrent.stm.TMap
import scala.concurrent.stm.TSet
import scala.concurrent.stm.Txn
import scala.concurrent.stm.atomic
import scala.reflect.ClassTag
import scala.reflect.classTag
import scala.util.control.NoStackTrace
import scala.util.control.Exception.Catcher
import com.qifun.statelessFuture.io.SocketInputStream
import com.qifun.statelessFuture.io.SocketWritingQueue
import com.qifun.statelessFuture.Future
import scala.collection.immutable.Queue

object BcpServer {

  private implicit val (logger, formater, appender) = ZeroLoggerFactory.newLogger(this)

  //  class ShouldCancelException extends Exception
  //

  private class IdSetIsFullException extends Exception

  private def between(low: Int, high: Int, test: Int): Boolean = {
    if (low < high) {
      test >= low && test < high
    } else if (low > high) {
      test >= low || test < high
    } else {
      false
    }
  }

  private object IdSet {

    object NonEmpty {

      @tailrec
      private def compat(lowId: Int, highId: Int, ids: Set[Int]): NonEmpty = {
        if (ids(lowId)) {
          compat(lowId + 1, highId, ids - lowId)
        } else {
          new NonEmpty(lowId, highId, ids)
        }
      }
    }

    final case class NonEmpty(lowId: Int, highId: Int, ids: Set[Int]) extends IdSet {
      import NonEmpty._

      @throws(classOf[IdSetIsFullException])
      override final def +(id: Int) = {
        if (between(lowId, highId, id)) {
          compat(lowId, highId, ids + id)
        } else if (between(highId, highId + 1024, id)) {
          if (between(lowId, lowId + 2048, id)) {
            throw new IdSetIsFullException
          } else {
            new NonEmpty(lowId, id, ids + id)
          }
        } else {
          this
        }
      }

      override final def contains(id: Int) = {
        if (between(lowId, highId, id)) {
          ids.contains(id)
        } else if (between(highId, highId + 1024, id)) {
          false
        } else {
          true
        }
      }

      override final def allReceivedBelow(id: Int): Boolean = {
        ids.isEmpty && lowId == id && highId == id
      }

    }

    case object Empty extends IdSet {

      @throws(classOf[IdSetIsFullException])
      override final def +(id: Int) = new NonEmpty(id + 1, id + 1, Set.empty[Int])

      override final def contains(id: Int) = false

      override final def allReceivedBelow(id: Int) = true

    }
  }

  private sealed abstract class IdSet {
    def +(id: Int): IdSet
    def contains(id: Int): Boolean
    def allReceivedBelow(id: Int): Boolean
  }

  private final class Stream(override protected final val socket: AsynchronousSocketChannel)
    extends SocketInputStream with SocketWritingQueue {

    override protected final def readingTimeout = Bcp.ServerReadingTimeout

    override protected final def writingTimeout = Bcp.ServerWritingTimeout

  }

  //  type HeartBeatTimer = ScheduledFuture[_]

  private type BoxedSessionId = WrappedArray[Byte]

  //  import scala.language.existentials // Workaround for https://issues.scala-lang.org/browse/SI-6541
  //  final case class CometAvailable(cometStream: Stream, timer: HeartBeatTimer)
  //  final case class SendingPack(connectionId: Int, packId: Int, buffers: Seq[ByteBuffer])

  private final class Connection {

    import Bcp._

    private[BcpServer] val stream = Ref.make[Stream]

    private[BcpServer] val finishIdReceived = Ref[Option[Int]](None)

    private[BcpServer] val isFinishSent = Ref(false)

    /**
     * 收到了多少个[[Data]]
     */
    private[BcpServer] val numDataReceived = Ref(0)

    private[BcpServer] val receiveIdSet = Ref[IdSet](IdSet.Empty)

    /**
     * 发送了多少个[[Data]]
     */
    private[BcpServer] val numDataSent = Ref(0)

    /**
     * 收到了多少个用于[[Data]]的[[Acknowledge]]
     */
    private[BcpServer] val numAcknowledgeReceivedForData = Ref(0)

    /**
     * 已经发送但还没有收到对应的[[Acknowledge]]的数据
     */
    private[BcpServer] val unconfirmedPack = Ref(Queue.empty[AcknowledgeRequired with ServerToClient])

  }

  private sealed trait ShutDownInputState
  private object ShutDownInputState {
    final case object NotShutDownInput extends ShutDownInputState
    final case object ShutDownInputSent extends ShutDownInputState
    final case object ShutDownInputConfirmed extends ShutDownInputState
  }
  private sealed trait ShutDownOutputState
  private object ShutDownOutputState {
    final case object NotShutDownOutput extends ShutDownOutputState
    final case object ShutDownOutputSent extends ShutDownOutputState
    final case object ShutDownOutputConfirmed extends ShutDownOutputState

    /**
     * 等待所有已发的数据都收到[[Bcp.Acknowledge]]
     *
     * @param numAcknowledgeToReceive 还有多少个[[Bcp.Acknowledge]]要收。
     */
    final case class ShutDownOutputWaiting(numAcknowledgeToReceive: Int) extends ShutDownOutputState
  }

  private final case class SendingConnectionQueue(
    val openConnections: Set[Connection] = Set.empty[Connection],
    private val availableConnections: Set[Connection] = Set.empty[Connection])

  trait Session {

    private[BcpServer] final val lastConnectionId = Ref[Int](0)

    /**
     * 当前连接，包括尚未关闭的连接和已经关闭但数据尚未全部确认的连接。
     *
     * @note 只有[[Connection.isFinishReceived]]和[[Connection.isFinishSent]]都为`true`，
     * 且[[Connection.unconfirmedPack]]为空，
     * 才会把[[Connection]]从[[connections]]中移除。
     */
    private[BcpServer] final val connections = TMap.empty[Int, Connection]

    private type QueueLength = Int

    private[BcpServer] final val sendingQueue: Ref[Either[(QueueLength, Queue[Bcp.AcknowledgeRequired]), SendingConnectionQueue]] = {
      Ref(Right(SendingConnectionQueue()))
    }

    private[BcpServer] final val shutDownInputState: Ref[ShutDownInputState] = {
      Ref(ShutDownInputState.NotShutDownInput)
    }

    private[BcpServer] final val shutDownOutputState: Ref[ShutDownOutputState] = {
      Ref(ShutDownOutputState.NotShutDownOutput)
    }

  }
}

/**
 * 处理BCP协议的服务器。
 *
 * BCP协议的特性：
 *
 * <ol>
 *   <li>基于连接</li>
 *   <li>可靠，低延时</li>
 *   <li>以数据包为单位，没有流</li>
 *   <li>乱序数据包，不保证接收顺序与发送顺序一致</li>
 * </ol>
 */
abstract class BcpServer {

  import BcpServer._

  protected type Session <: BcpServer.Session

  protected def executor: ScheduledExecutorService

  final def send(session: Session, buffer: ByteBuffer*) {
    atomic { implicit txn =>
      enqueue(session, Bcp.Data(buffer))
    }
  }

  /**
   * 创建[[Session]]实例的工厂方法。
   *
   * @note 由于使用了软件事务内存，[[newSession]]可能会被随机的反复调用。
   */
  protected def newSession: Session

  /**
   * 每一次触发表示与客户端建立了一次新的会话。
   *
   * 建立一次会话期间，本[[BcpServer]]可能会调用多次[[newSession]]，但一定只调用一次[[open]]。
   */
  protected def open(session: Session)

  protected def closed(session: Session)

  protected def received(session: Session, pack: ByteBuffer*)

  private val sessions = TMap.empty[BoxedSessionId, Session]

  //  /**
  //   * @throws ShouldCancelException 本Runnable本应取消，但实际上没有来得及取消
  //   */
  //  @throws(classOf[ShouldCancelException])
  //  private def heartBeat(expectedTimer: HeartBeatTimer, session: Session, stream: Stream) {
  //    atomic { implicit txn =>
  //      session.sendingStreams.remove(stream) match {
  //        case Some(oldTimer) if oldTimer == expectedTimer => {
  //          session.receivingStreams.add(stream)
  //        }
  //        case _ => {
  //          throw new ShouldCancelException
  //        }
  //      }
  //    }
  //    Bcp.enqueueEndData(stream)
  //    stream.flush()
  //    startReceive(session, stream)
  //  }
  //
  //  private def startWait(session: BcpSession, stream: Stream)(implicit txn: InTxn) {
  //    object TimerRunnable extends Runnable {
  //      override final def run() {
  //        try {
  //          heartBeat(timer, session, stream)
  //        } catch {
  //          case _: ShouldCancelException =>
  //        }
  //      }
  //      val timer = executor.schedule(
  //        this,
  //        Bcp.ServerHeartBeatDelay.length,
  //        Bcp.ServerHeartBeatDelay.unit)
  //    }
  //    Txn.afterRollback { status =>
  //      TimerRunnable.timer.cancel(false)
  //    }
  //    session.sendingStreams.put(stream, TimerRunnable.timer)
  //  }

  /**
   * 从所有可用连接中轮流发送
   */
  private def enqueue(session: Session, newPack: Bcp.ServerToClient with Bcp.AcknowledgeRequired)(implicit txn: InTxn) {
    session.sendingQueue.transform {
      case Right(SendingConnectionQueue(openConnections, availableConnections)) => {
        if (openConnections.isEmpty) {
          Left((1, Queue(newPack)))
        } else {
          def consume(openConnections: Set[Connection], availableConnections: Set[Connection]) = {
            val (first, rest) = availableConnections.splitAt(1)
            BcpIo.enqueue(first.head.stream(), newPack)
            Right(SendingConnectionQueue(openConnections, rest))
          }
          if (availableConnections.isEmpty) {
            consume(openConnections, openConnections)
          } else {
            consume(openConnections, availableConnections)
          }
        }
      }
      case Left((queueLength, packQueue)) => {
        if (queueLength >= Bcp.MaxOfflinePack) {
          throw new BcpException.SendingQueueIsFull
        } else {
          Left((queueLength + 1, packQueue.enqueue(newPack)))
        }
      }
    }
  }

  private def checkConnectionFinish(
    sessionId: Array[Byte], session: Session, connectionId: Int, connection: Connection)(
      implicit txn: InTxn) {
    val isConnectionFinish =
      connection.isFinishSent() &&
        connection.finishIdReceived().exists(connection.receiveIdSet().allReceivedBelow) &&
        connection.unconfirmedPack().isEmpty
    if (isConnectionFinish) { // 所有外出数据都已经发送并确认，所有外来数据都已经收到并确认
      session.connections.remove(connectionId)
      val connectionStream = connection.stream()
      connection.stream() = null
      Txn.afterCommit(_ => connectionStream.interrupt())
      if (session.connections.isEmpty &&
        session.shutDownOutputState() == ShutDownOutputState.ShutDownOutputConfirmed &&
        session.shutDownInputState() == ShutDownInputState.ShutDownInputConfirmed) {
        val removedSessionOption = sessions.remove(sessionId)
        assert(removedSessionOption == Some(session))
      }
    }
    // TODO: 支持关闭整个Session
  }

  private def dataReceived(
    sessionId: Array[Byte],
    session: Session,
    connectionId: Int,
    connection: Connection,
    packId: Int,
    buffer: Seq[ByteBuffer])(
      implicit txn: InTxn) {
    val idSet = connection.receiveIdSet()
    if (idSet.contains(packId)) {
      // 已经收过了，直接忽略。
    } else {
      Txn.afterCommit(_ => received(session, buffer: _*))
      connection.receiveIdSet() = idSet + packId
      checkConnectionFinish(sessionId, session, connectionId, connection)
    }
  }

  private def checkShutDown(session: Session)(implicit txn: InTxn) {
    ??? // TODO: 如果两端都已经ShutDown，则进入Finish所有TCP连接的流程
  }

  private def finishReceived(
    sessionId: Array[Byte],
    session: Session,
    connectionId: Int,
    connection: Connection,
    packId: Int)(implicit txn: InTxn) {
    connection.numDataReceived() = packId + 1
    connection.finishIdReceived() match {
      case None => {
        connection.finishIdReceived() = Some(packId)
        checkConnectionFinish(sessionId, session, connectionId, connection)
        // 无需通知用户，结束的只是一个连接，而不是整个Session
      }
      case Some(originalPackId) => {
        assert(originalPackId == packId)
      }
    }
  }

  private def startReceive(
    sessionId: Array[Byte],
    session: Session,
    connectionId: Int,
    connection: Connection,
    stream: Stream) {
    implicit def catcher: Catcher[Unit] = {
      case e: Exception => {
        stream.interrupt()
        atomic { implicit txn =>
          connection.unconfirmedPack().foldLeft(connection.numAcknowledgeReceivedForData()) {
            case (packId, Bcp.Data(buffer)) => {
              enqueue(session, Bcp.RetransmissionData(connectionId, packId, buffer))
              packId + 1
            }
            case (packId, Bcp.Finish) => {
              Bcp.RetransmissionFinish(connectionId, packId)
              enqueue(session, Bcp.RetransmissionFinish(connectionId, packId))
              packId + 1
            }
            case (nextPackId, pack) => {
              enqueue(session, pack)
              nextPackId
            }
          }
          connection.unconfirmedPack() = Queue.empty
          checkConnectionFinish(sessionId, session, connectionId, connection)
        }
      }
    }
    for (clientToServer <- BcpIo.receive(stream)) {
      clientToServer match {
        case Bcp.Data(buffer) => {
          BcpIo.enqueue(stream, Bcp.Acknowledge)
          atomic { implicit txn =>
            val packId = connection.numDataReceived()
            connection.numDataReceived() = packId + 1
            dataReceived(sessionId, session, connectionId, connection, packId, buffer)
          }
          startReceive(sessionId, session, connectionId, connection, stream)
          stream.flush()
        }
        case Bcp.RetransmissionData(dataConnectionId, packId, data) => {
          BcpIo.enqueue(stream, Bcp.Acknowledge)
          atomic { implicit txn =>
            session.connections.get(dataConnectionId) match {
              case Some(dataConnection) => {
                dataReceived(sessionId, session, dataConnectionId, dataConnection, packId, data)
              }
              case None => {
                val lastConnectionId = session.lastConnectionId()
                if (between(lastConnectionId, lastConnectionId + Bcp.MaxConnectionsPerSession, dataConnectionId)) {
                  // 在成功建立连接以前先收到重传的数据，这表示原连接在BCP握手阶段卡住了
                  val newConnection = new Connection
                  session.connections(dataConnectionId) = newConnection
                  dataReceived(sessionId, session, dataConnectionId, newConnection, packId, data)
                } else {
                  // 原连接先前已经接收过所有数据并关闭了，可以安全忽略数据
                }
              }
            }
          }
          startReceive(sessionId, session, connectionId, connection, stream)
          stream.flush()
        }
        case Bcp.Acknowledge => {
          atomic { implicit txn =>
            val (originalPack, queue) = connection.unconfirmedPack().dequeue
            connection.unconfirmedPack() = queue
            originalPack match {
              case Bcp.Data(buffer) => {
                connection.numAcknowledgeReceivedForData() += 1
              }
              case Bcp.ShutDownInput => {
                session.shutDownInputState() = ShutDownInputState.ShutDownInputConfirmed
                checkShutDown(session)
              }
              case Bcp.ShutDownOutput => {
                session.shutDownOutputState() = ShutDownOutputState.ShutDownOutputConfirmed
                checkShutDown(session)
              }
              case Bcp.RetransmissionData(_, _, _) | Bcp.Finish | Bcp.RetransmissionFinish(_, _) => {
                // 简单移出重传队列即可，不用任何额外操作
              }
            }
            checkConnectionFinish(sessionId, session, connectionId, connection)
          }
          startReceive(sessionId, session, connectionId, connection, stream)
        }
        case Bcp.Finish => {
          BcpIo.enqueue(stream, Bcp.Acknowledge)
          atomic { implicit txn =>
            val packId = connection.numDataReceived()
            finishReceived(sessionId, session, connectionId, connection, packId)
          }
          startReceive(sessionId, session, connectionId, connection, stream)
          stream.flush()
        }
        case Bcp.RetransmissionFinish(finishConnectionId, packId) => {
          BcpIo.enqueue(stream, Bcp.Acknowledge)
          atomic { implicit txn =>
            session.connections.get(finishConnectionId) match {
              case Some(finishConnection) => {
                finishReceived(sessionId, session, finishConnectionId, finishConnection, packId)
              }
              case None => {
                val lastConnectionId = session.lastConnectionId()
                if (between(lastConnectionId, lastConnectionId + Bcp.MaxConnectionsPerSession, connectionId)) {
                  // 在成功建立连接以前先收到重传的数据，这表示原连接在BCP握手阶段卡住了
                  val newConnection = new Connection
                  session.connections(finishConnectionId) = newConnection
                  finishReceived(sessionId, session, finishConnectionId, newConnection, packId)
                } else {
                  // 原连接先前已经接收过所有数据并关闭了，可以安全忽略数据
                }
              }
            }
          }
          startReceive(sessionId, session, connectionId, connection, stream)
          stream.flush()
        }
        case Bcp.ShutDownInput => {
          BcpIo.enqueue(stream, Bcp.Acknowledge)
          atomic { implicit txn =>
            session.shutDownOutputState() = ShutDownOutputState.ShutDownOutputConfirmed
            checkShutDown(session)
          }
          startReceive(sessionId, session, connectionId, connection, stream)
          stream.flush()
        }
        case Bcp.ShutDownOutput => {
          BcpIo.enqueue(stream, Bcp.Acknowledge)
          atomic { implicit txn =>
            session.shutDownInputState() = ShutDownInputState.ShutDownInputConfirmed
            checkShutDown(session)
          }
          startReceive(sessionId, session, connectionId, connection, stream)
          stream.flush()
        }
        case Bcp.RenewRequest(newSessionId) => {
          //          atomic {
          //
          //          }
          //
          ??? // TODO: 
        }
      }

    }
  }

  protected final def addIncomingSocket(socket: AsynchronousSocketChannel) {
    val stream = new Stream(socket)
    implicit def catcher: Catcher[Unit] = PartialFunction.empty
    for (Bcp.ConnectionHead(sessionId, connectionId) <- BcpIo.receiveHead(stream)) {
      atomic { implicit txn =>
        val session = sessions.get(sessionId) match {
          case None => {
            val session = newSession
            sessions(sessionId) = session
            Txn.afterCommit { status =>
              open(session)
            }
            session
          }
          case Some(session) => {
            if (session.connections.size >= Bcp.MaxConnectionsPerSession) {
              stream.interrupt()
            }
            session
          }
        }
        val connection = session.connections.getOrElseUpdate(connectionId, new Connection)
        session.lastConnectionId() = connectionId
        if (connection.stream() == null) {
          connection.stream() = stream
          Txn.afterCommit(_ => startReceive(sessionId, session, connectionId, connection, stream))
        } else {
          logger.fine(fast"A client atempted to reuse existed connectionId. I rejected it.")
          stream.interrupt()
        }
      }
    }

  }

  //  
  //  private val listeningSocket = MutableSet.empty
  //  
  //  val listeningAddresses = new MutableSet[InetAddress] {
  //    
  //  }

}