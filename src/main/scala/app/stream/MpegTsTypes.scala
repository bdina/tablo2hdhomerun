package app.stream

object MpegTsTypes {
  opaque type Pid <: Int = Int

  object Pid {
    inline val PatRaw = 0x0000
    inline val NullRaw = 0x1FFF
    val Pat: Pid = PatRaw
    val Null: Pid = NullRaw

    inline def apply(raw: Int): Pid = raw & 0x1FFF

    inline def fromPacket(arr: Array[Byte], pktOffset: Int): Pid =
      ((arr(pktOffset + 1) & 0x1F) << 8) | (arr(pktOffset + 2) & 0xFF)

    extension (pid: Pid) {
      inline def value: Int = pid
      inline def isNull: Boolean = pid == NullRaw
      inline def isPat: Boolean = pid == PatRaw
      inline def isElementary(pmtPid: Int): Boolean = pid > 0 && pid != NullRaw && pid != pmtPid
    }
  }

  opaque type ContinuityCounter <: Int = Int

  object ContinuityCounter {
    inline def apply(raw: Int): ContinuityCounter = raw & 0x0F

    inline def fromPacket(arr: Array[Byte], pktOffset: Int): ContinuityCounter =
      arr(pktOffset + 3) & 0x0F

    extension (cc: ContinuityCounter) {
      inline def value: Int = cc
      inline def next: ContinuityCounter = (cc + 1) & 0x0F
      inline def isDiscontinuousAfter(prev: Int): Boolean =
        prev >= 0 && cc != ((prev + 1) & 0x0F) && cc != prev
    }
  }

  opaque type PtsTicks <: Long = Long

  object PtsTicks {
    inline val Mask = 0x1FFFFFFFFL
    inline val HalfRing = 0x100000000L
    inline val FullRing = 0x200000000L

    inline def apply(raw: Long): PtsTicks = raw & Mask

    extension (pts: PtsTicks) {
      inline def value: Long = pts
      inline def masked: PtsTicks = pts & Mask
      inline def advance(delta: Long): PtsTicks = (pts + delta) & Mask
      inline def signedDiff(other: Long): Long = {
        val raw = (pts - other) & Mask
        if ((raw & HalfRing) != 0L) raw - FullRing else raw
      }
    }
  }

  opaque type OptPts <: Long = Long

  object OptPts {
    inline val EmptyRaw = -1L
    val Empty: OptPts = EmptyRaw

    inline def apply(raw: Long): OptPts =
      if (raw < 0L) EmptyRaw else raw & PtsTicks.Mask

    inline def fromRaw(raw: Long): OptPts = raw

    extension (opt: OptPts) {
      inline def isDefined: Boolean = opt >= 0L
      inline def isEmpty: Boolean = opt < 0L
      inline def get: PtsTicks = PtsTicks(opt)
      inline def rawValue: Long = opt
      inline def getOrElse(default: => Long): Long = if (opt >= 0L) opt else default
      inline def toOption: Option[Long] = if (opt >= 0L) Some(opt) else None
    }
  }
}
