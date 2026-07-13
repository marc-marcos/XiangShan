package xiangshan.backend.vector.vagq

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.Parameters
import xiangshan._
import xiangshan.backend.Bundles._
import xiangshan.backend.rob.RobPtr
import xiangshan.frontend.ftq.FtqPtr
import xiangshan.mem.{LqPtr, SqPtr}
import utility.XSError

import VAGQConstants._

class VAGQEntryTable(implicit p: Parameters) extends VAGQModule {
  val io = IO(new VAGQEntryTableIO)

  private val addrMaskGen = Seq.fill(VAGQConstants.AddrIssueWidth)(Module(new MaskGen))
  addrMaskGen.zip(io.addrUop).foreach { case (maskGen, addrUop) =>
    maskGen.in.uopIdx    := addrUop.bits.uopIdx
    maskGen.in.useVstart := addrUop.bits.useVstart
    maskGen.in.vstart    := addrUop.bits.vstart
    maskGen.in.uvlByte   := addrUop.bits.uvlByte
    maskGen.in.vm        := addrUop.bits.vm
    maskGen.in.v0Mask    := addrUop.bits.v0Mask
    maskGen.in.deew      := addrUop.bits.deew
    maskGen.in.vma       := addrUop.bits.vma
    maskGen.in.vta       := addrUop.bits.vta
  }

  private val entryValid      = RegInit(VecInit(Seq.fill(vagqSize)(false.B)))
  private val entryStaticReg  = Reg(Vec(vagqSize, new VAGQEntryStatic))
  private val entryDynamicReg = Reg(Vec(vagqSize, new VAGQEntryDynamic))
  private val entries         = Wire(Vec(vagqSize, new VAGQEntry))
  entries.zip(entryStaticReg).zip(entryDynamicReg).zip(entryValid).foreach {
    case (((entry, static), dynamic), valid) =>
      entry.static := static
      entry.dynamic := dynamic
      entry.valid := valid
  }

  private val addrEntry = io.addrUop.map(addrUop => entryAt(entries, addrUop.bits.entryIdx))
  private val dataEntry = io.dataUop.map(dataUop => entryAt(entries, dataUop.bits.entryIdx))
  private val addrIdxValid = io.addrUop.map(addrUop => idxValid(addrUop.bits.entryIdx))
  private val dataIdxValid = io.dataUop.map(dataUop => idxValid(dataUop.bits.entryIdx))
  private val addrEntryFlush = addrEntry.map(entry => entry.valid && entry.robIdx.needFlush(io.redirect))
  private val dataEntryFlush = dataEntry.map(entry => entry.valid && entry.robIdx.needFlush(io.redirect))
  private val addrCanAccept = addrEntry.map(entry => !entry.valid || entry.state === VAGQEntryState.waitA)
  private val dataCanAccept = dataEntry.map(entry => !entry.valid || entry.state === VAGQEntryState.waitSI)

  val addrSameEntry = io.addrUop(0).valid && io.addrUop(1).valid &&
    io.addrUop(0).bits.entryIdx === io.addrUop(1).bits.entryIdx
  XSError(addrSameEntry, "VAGQ addr uop get same entryIdx in the same cycle\n")

  val dataSameEntry = io.dataUop.zipWithIndex.combinations(2).map {
    case Seq((left, _), (right, _)) =>
      left.valid && right.valid && left.bits.entryIdx === right.bits.entryIdx
  }.reduce(_ || _)
  XSError(dataSameEntry, "VAGQ data uop get same entryIdx in the same cycle\n")

  io.addrUop.zipWithIndex.foreach { case (addrUop, lane) =>
    addrUop.ready := addrIdxValid(lane) && addrCanAccept(lane) && !addrEntryFlush(lane)
  }
  io.dataUop.zipWithIndex.foreach { case (dataUop, lane) =>
    dataUop.ready := dataIdxValid(lane) && dataCanAccept(lane) && !dataEntryFlush(lane)
  }

  private val addrFire = io.addrUop.map(addrUop => addrUop.fire && !addrUop.bits.robIdx.needFlush(io.redirect))
  private val dataFire = io.dataUop.map(dataUop => dataUop.fire && !dataUop.bits.robIdx.needFlush(io.redirect))
  private val reqBitmapUpdates = io.splitUpdate.toSeq ++ io.mergeReqUpdate.toSeq

  private def mergedUpdateMask(updateHits: Seq[Bool], select: VAGQReqBitmapUpdate => UInt): UInt = {
    reqBitmapUpdates.zip(updateHits).map { case (update, hit) =>
      Mux(hit, select(update.bits), 0.U(vagqFlowBytes.W))
    }.reduce(_ | _)
  }

  private def selectFirstFaultException(exceptionHits: Seq[Bool]): VAGQReqBitmapUpdate = {
    val maxFaultElemIdx = (vagqFlowBytes - 1).U(vagqFlowByteWidth.W)
    val minFaultElemIdx = reqBitmapUpdates.zip(exceptionHits).map { case (update, hit) =>
      Mux(hit, update.bits.faultElemIdx, maxFaultElemIdx)
    }.reduce((left, right) => Mux(left <= right, left, right))
    val firstFaultHits = reqBitmapUpdates.zip(exceptionHits).map { case (update, hit) =>
      hit && update.bits.faultElemIdx === minFaultElemIdx
    }

    // Tie-break equal fault offsets by lane order for deterministic hardware.
    PriorityMux(firstFaultHits.zip(reqBitmapUpdates.map(_.bits)))
  }

  private def applyReqBitmapUpdate(
    next: VAGQEntry,
    curr: VAGQEntry,
    updateHits: Seq[Bool],
    hasUpdate: Bool
  ): Unit = {
    val setReqSent = mergedUpdateMask(updateHits, _.setReqSent)
    val clearReqSent = mergedUpdateMask(updateHits, _.clearReqSent)
    val setReqAck = mergedUpdateMask(updateHits, _.setReqAck)
    val exceptionHits = reqBitmapUpdates.zip(updateHits).map { case (update, hit) =>
      hit && update.bits.exception
    }
    val hasExceptionUpdate = exceptionHits.reduce(_ || _)
    val exceptionUpdate = selectFirstFaultException(exceptionHits)

    when(hasUpdate) {
      next.reqSent := (curr.reqSent | setReqSent) & ~clearReqSent
      next.reqAck  := curr.reqAck | setReqAck
    }
    when(hasExceptionUpdate) {
      next.exceptionNumber := exceptionUpdate.exceptionNumber
      next.faultElemIdx    := exceptionUpdate.faultElemIdx
      next.state           := VAGQEntryState.excp
    }
  }

  private def applyMergeStateUpdate(next: VAGQEntry, mergeStateHit: Bool): Unit = {
    when(mergeStateHit) {
      when(io.mergeStateUpdate.bits.clearValid) {
        next.valid := false.B
      }.otherwise {
        next.state := io.mergeStateUpdate.bits.stateNext
      }
    }
  }

  private def applyEnqueueStateUpdate(
    next: VAGQEntry,
    curr: VAGQEntry,
    addrFireThis: Bool,
    dataFireThis: Bool
  ): Unit = {
    when(addrFireThis && dataFireThis) {
      enterSplit(next)
    }.elsewhen(addrFireThis) {
      when(curr.valid && curr.state === VAGQEntryState.waitA) {
        enterSplit(next)
      }.otherwise {
        initPending(next, VAGQEntryState.waitSI)
      }
    }.elsewhen(dataFireThis) {
      when(curr.valid && curr.state === VAGQEntryState.waitSI) {
        enterSplit(next)
      }.otherwise {
        initPending(next, VAGQEntryState.waitA)
      }
    }
  }

  private def applyFlushUpdate(next: VAGQEntry, flushHit: Bool): Unit = {
    when(flushHit) {
      next.valid := false.B
    }
  }

  for (i <- 0 until vagqSize) {
    val idx = i.U(vagqEntryIdxWidth.W)
    val addrFireThisVec = addrFire.zip(io.addrUop).map { case (fire, addrUop) =>
      fire && addrUop.bits.entryIdx === idx
    }
    val addrFireThis = addrFireThisVec.reduce(_ || _)
    val dataFireThisVec = dataFire.zip(io.dataUop).map { case (fire, dataUop) =>
      fire && dataUop.bits.entryIdx === idx
    }
    val dataFireThis = dataFireThisVec.reduce(_ || _)
    val enqueueThis = addrFireThis || dataFireThis
    val reqUpdateHits = reqBitmapUpdates.map(update => update.valid && update.bits.entryIdx === idx)
    val reqUpdateThis = reqUpdateHits.reduce(_ || _)
    val mergeStateThis = io.mergeStateUpdate.valid && io.mergeStateUpdate.bits.entryIdx === idx
    val mergeStateWrite = mergeStateThis && !io.mergeStateUpdate.bits.clearValid
    val mergeClearThis = mergeStateThis && io.mergeStateUpdate.bits.clearValid
    val flushThis = entries(i).valid && entries(i).robIdx.needFlush(io.redirect)

    val staticNext = WireInit(entryStaticReg(i))
    addrFireThisVec.zip(io.addrUop).zip(addrMaskGen).foreach { case ((fireThis, addrUop), maskGen) =>
      when(fireThis) {
        connectSamePort(staticNext, addrUop.bits)
        connectSamePort(staticNext, maskGen.out)
      }
    }
    dataFireThisVec.zip(io.dataUop).foreach { case (fireThis, dataUop) =>
      when(fireThis) {
        connectSamePort(staticNext, dataUop.bits)
      }
    }
    when(enqueueThis) {
      entryStaticReg(i) := staticNext
    }

    val next = WireInit(entries(i))

    applyReqBitmapUpdate(next, entries(i), reqUpdateHits, reqUpdateThis)
    applyMergeStateUpdate(next, mergeStateThis)
    applyEnqueueStateUpdate(next, entries(i), addrFireThis, dataFireThis)
    applyFlushUpdate(next, flushThis)

    when(reqUpdateThis || mergeStateWrite || enqueueThis) {
      entryDynamicReg(i) := next.dynamic
    }
    when(enqueueThis || mergeClearThis || flushThis) {
      entryValid(i) := next.valid
    }
  }

  io.entries := entries
}

class VAGQEntryTableIO(implicit p: Parameters) extends VAGQBundle {
  val addrUop          = Flipped(Vec(VAGQConstants.AddrIssueWidth, Decoupled(new VAGQAddrSideUop)))
  val dataUop          = Flipped(Vec(VAGQConstants.DataIssueWidth, Decoupled(new VAGQDataSideUop)))
  val entries          = Output(Vec(vagqSize, new VAGQEntry))
  val splitUpdate      = Input(Vec(VAGQConstants.SplitUpdateWidth, Valid(new VAGQReqBitmapUpdate)))
  val mergeReqUpdate   = Flipped(Vec(VAGQConstants.MergeRespWidth, Valid(new VAGQReqBitmapUpdate)))
  val mergeStateUpdate = Flipped(Valid(new VAGQEntryStateUpdate))
  val redirect         = Flipped(Valid(new Redirect))
}

class VAGQEntryStatic(implicit p: Parameters) extends VAGQBundle {
  val meta = new VAGQMeta
  val uopType = UInt(3.W)
  val robIdx = new RobPtr
  val pdest = UInt(VfPhyRegIdxWidth.W)
  val psrc2 = UInt(VfPhyRegIdxWidth.W)

  val baseAddr = UInt(XLEN.W)
  val op2Data = UInt(VLEN.W)

  val ieew = UInt(EewWidth.W)
  val deew = UInt(EewWidth.W)
  val uopIdx = UInt(UopIdxWidth.W)
  val elemActiveMask = UInt(vagqFlowBytes.W)
  val elemAgnosticMask = UInt(vagqFlowBytes.W)

  val nf = UInt(NfWidth.W)

  def isLoad: Bool    = VAGQUopType.isLoad(uopType)
  def isStore: Bool   = VAGQUopType.isStore(uopType)
  def isStride: Bool  = VAGQUopType.isStride(uopType)
  def isIndexed: Bool = VAGQUopType.isIndexed(uopType)
  def isOrdered: Bool = VAGQUopType.isOrdered(uopType)
}

class VAGQEntryDynamic(implicit p: Parameters) extends VAGQBundle {
  val reqSent = UInt(vagqFlowBytes.W)
  val reqAck = UInt(vagqFlowBytes.W)

  val exceptionNumber = UInt(ExceptionNumberWidth.W)
  val faultElemIdx = UInt(vagqFlowByteWidth.W)
  val state = UInt(3.W)
}

class VAGQEntryMeta(implicit p: Parameters) extends VAGQBundle {
  val valid = Bool()
  val static = new VAGQEntryStatic
  val dynamic = new VAGQEntryDynamic

  def meta: VAGQMeta = static.meta
  def uopType: UInt = static.uopType
  def robIdx: RobPtr = static.robIdx
  def pdest: UInt = static.pdest
  def psrc2: UInt = static.psrc2
  def baseAddr: UInt = static.baseAddr
  def op2Data: UInt = static.op2Data
  def ieew: UInt = static.ieew
  def deew: UInt = static.deew
  def uopIdx: UInt = static.uopIdx
  def elemActiveMask: UInt = static.elemActiveMask
  def elemAgnosticMask: UInt = static.elemAgnosticMask
  def nf: UInt = static.nf

  def reqSent: UInt = dynamic.reqSent
  def reqAck: UInt = dynamic.reqAck
  def exceptionNumber: UInt = dynamic.exceptionNumber
  def faultElemIdx: UInt = dynamic.faultElemIdx
  def state: UInt = dynamic.state

  def isLoad: Bool    = static.isLoad
  def isStore: Bool   = static.isStore
  def isStride: Bool  = static.isStride
  def isIndexed: Bool = static.isIndexed
  def isOrdered: Bool = static.isOrdered
}

class VAGQEntry(implicit p: Parameters) extends VAGQEntryMeta

class VAGQEntryStateUpdate(implicit p: Parameters) extends VAGQBundle {
  val entryIdx   = UInt(vagqEntryIdxWidth.W)
  val stateNext  = UInt(3.W)
  val clearValid = Bool()
}

object VAGQEntryState {
  val waitA  = "b001".U(3.W)
  val waitSI = "b010".U(3.W)
  val split  = "b011".U(3.W)
  val merge  = "b100".U(3.W)
  val wb     = "b101".U(3.W)
  val excp   = "b110".U(3.W)
}

object VAGQUopType {
  val strideLoad            = "b000".U(3.W)
  val strideStore           = "b001".U(3.W)
  val indexedUnorderedLoad  = "b100".U(3.W)
  val indexedUnorderedStore = "b101".U(3.W)
  val indexedOrderedLoad    = "b110".U(3.W)
  val indexedOrderedStore   = "b111".U(3.W)

  def isLoad(uopType: UInt): Bool    = !uopType(0)
  def isStore(uopType: UInt): Bool   =  uopType(0)
  def isStride(uopType: UInt): Bool  = !uopType(2) && !uopType(1)
  def isIndexed(uopType: UInt): Bool =  uopType(2)
  def isOrdered(uopType: UInt): Bool =  uopType(1)
}
