package com.twitter.finagle.transport

import com.twitter.concurrent.AsyncQueue
import com.twitter.finagle.util.Conversions._
import com.twitter.finagle.util.Proc
import com.twitter.finagle.{
  CancelledWriteException, ChannelClosedException, ChannelException, WriteException}
import com.twitter.util.{Future, Promise}
import java.net.SocketAddress
import org.jboss.netty.channel._

/**
 * Implements a {{Transport}} based on a Netty channel. t is a
 * I{{ChannelHandler}} and must be the last in the pipeline.
 */
class ChannelTransport[In, Out](ch: Channel)
  extends Transport[In, Out] with ChannelUpstreamHandler
{
  ch.getPipeline.addLast("finagleTransportBridge", this)

  private[this] val readq = new AsyncQueue[Out]
  private[this] val writer = Proc[(In, Promise[Unit])] { case (msg, p) =>
    Channels.write(ch, msg).addListener(new ChannelFutureListener {
      def operationComplete(f: ChannelFuture) {
        if (f.isSuccess)
          p.setValue(())
        else if (f.isCancelled)
          p.setException(new WriteException(new CancelledWriteException))
        else
          p.setException(new WriteException(ChannelException(f.getCause, ch.getRemoteAddress)))
      }
    })
  }

  private[this] def fail(exc: Throwable) {
    readq.fail(exc)
    close()
  }

  override def handleUpstream(ctx: ChannelHandlerContext, e: ChannelEvent) {
    e match {
      case msg: MessageEvent =>
        readq.offer(msg.getMessage.asInstanceOf[Out])

      case e: ChannelStateEvent
      if e.getState == ChannelState.OPEN && e.getValue != java.lang.Boolean.TRUE =>
        fail(new ChannelClosedException(ch.getRemoteAddress))

      case e: ExceptionEvent =>
        fail(ChannelException(e.getCause, ch.getRemoteAddress))

      case _ =>  // drop.
    }

    // We terminate the upstream here on purpose: this must always
    // be the last handler.
  }

  def write(msg: In): Future[Unit] = {
    val p = new Promise[Unit]
    writer ! (msg, p)
    p
  }

  def read(): Future[Out] = readq.poll()

  def close() {
    if (ch.isOpen)
      Channels.close(ch)
  }

  def localAddress: SocketAddress = ch.getLocalAddress()
  def remoteAddress: SocketAddress = ch.getRemoteAddress()
  
  override def toString = "Transport<%s>".format(ch)
}
