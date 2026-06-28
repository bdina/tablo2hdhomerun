package app.stream

import org.apache.pekko.NotUsed
import org.apache.pekko.stream.{Attributes, FlowShape, Inlet, Outlet}
import org.apache.pekko.stream.scaladsl.Flow
import org.apache.pekko.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import org.apache.pekko.util.ByteString

object MpegTsDiscontinuity {
  val PacketSize = 188

  def markFirstPackets(count: Int): Flow[ByteString, ByteString, NotUsed] =
    Flow.fromGraph(new Stage(count))

  private final class Stage(count: Int) extends GraphStage[FlowShape[ByteString, ByteString]] {
    val in: Inlet[ByteString] = Inlet("MpegTsDiscontinuity.in")
    val out: Outlet[ByteString] = Outlet("MpegTsDiscontinuity.out")
    override val shape: FlowShape[ByteString, ByteString] = FlowShape(in, out)

    override def createLogic(attrs: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
      private var carry: ByteString = ByteString.empty
      private var marked = 0

      setHandler(in, new InHandler {
        override def onPush(): Unit = {
          val data = carry ++ grab(in)
          if (marked >= count) {
            carry = ByteString.empty
            push(out, data)
          } else {
            val full = (data.length / PacketSize) * PacketSize
            if (full == 0) {
              carry = data
              pull(in)
            } else {
              val arr = data.take(full).toArray
              var i = 0
              while (i < full && marked < count) {
                if ((arr(i) == 0x47.toByte) && ((arr(i + 3) & 0x20) != 0)) {
                  val afLen = arr(i + 4) & 0xFF
                  if (afLen >= 1) {
                    arr(i + 5) = (arr(i + 5) | 0x80.toByte).toByte
                    marked += 1
                  }
                }
                i += PacketSize
              }
              carry = data.drop(full)
              push(out, ByteString(arr))
            }
          }
        }

        override def onUpstreamFinish(): Unit = {
          if (carry.nonEmpty) {
            emit(out, carry)
          }
          completeStage()
        }
      })

      setHandler(out, new OutHandler {
        override def onPull(): Unit = pull(in)
      })
    }
  }
}
