package xiangshan.backend.vector.vagq

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.Parameters
import xiangshan._
import xiangshan.backend.rob.RobPtr

class MergeCtrl(numEntries: Int)(implicit p: Parameters) extends VAGQModule {
  val io = IO(new MergeCtrlIO(numEntries))

  private val respVec = io.lduResp.toSeq ++ io.staResp.toSeq ++ Seq(io.lsqEmptyResp)
  private val respAcceptedVec = VecInit(respVec.map { resp =>
    resp.valid && respMatchesEntry(resp.bits, io.entry, numEntries)
  })
  private val respExceptionOH = respVec.zip(respAcceptedVec).map { case (resp, accepted) =>
    Mux(
      accepted && resp.bits.exception,
      UIntToOH(resp.bits.entryIdx, numEntries),
      0.U(numEntries.W)
    )
  }.reduce(_ | _)

  for (lane <- 0 until VAGQConstants.MergeRespWidth) {
    val resp = respVec(lane)
    val respAccepted = respAcceptedVec(lane)
    val respMask = resp.bits.mask

    io.reqUpdate(lane).valid                := respAccepted
    io.reqUpdate(lane).bits                 := 0.U.asTypeOf(io.reqUpdate(lane).bits)
    io.reqUpdate(lane).bits.entryIdx        := resp.bits.entryIdx
    io.reqUpdate(lane).bits.setReqAck       := Mux(resp.bits.isNACK && !resp.bits.exception, 0.U, respMask)
    io.reqUpdate(lane).bits.clearReqSent    := Mux(resp.bits.isNACK && !resp.bits.exception, respMask, 0.U)
    io.reqUpdate(lane).bits.exception       := resp.bits.exception
    io.reqUpdate(lane).bits.exceptionNumber := resp.bits.exceptionNumber
    io.reqUpdate(lane).bits.faultElemIdx    := resp.bits.byteOffset
  }

  private val entryAliveVec = VecInit(io.entry.map(x => entryAlive(x.entry, io.redirect)))
  private val entryStateOH = VecInit(io.entry.map(x => UIntToOH(x.entry.state, VAGQEntryState.numStates)))

  private val mergeCandidates = VecInit(io.entry.indices.map { i =>
    entryAliveVec(i) && entryStateOH(i)(VAGQEntryState.mergeId)
  })
  private val wbCandidates = VecInit(io.entry.indices.map { i =>
    entryAliveVec(i) && entryStateOH(i)(VAGQEntryState.wbId)
  })
  private val excpCandidates = VecInit(io.entry.indices.map { i =>
    val entry = io.entry(i).entry
    val reqInFlight = (entry.reqSent & ~entry.reqAck).orR
    entryAliveVec(i) && entryStateOH(i)(VAGQEntryState.excpId) && !reqInFlight
  })
  private val splitDoneCandidates = VecInit(io.entry.indices.map { i =>
    val entry = io.entry(i).entry
    entryAliveVec(i) && entryStateOH(i)(VAGQEntryState.splitId) && entry.reqAck.andR && !respExceptionOH(i)
  })

  private val hasMerge     = mergeCandidates.asUInt.orR
  private val hasWb        = wbCandidates.asUInt.orR
  private val hasExcp      = excpCandidates.asUInt.orR
  private val hasSplitDone = splitDoneCandidates.asUInt.orR

  private val mergeSel     = PriorityEncoder(mergeCandidates)
  private val wbSel        = PriorityEncoder(wbCandidates)
  private val excpSel      = PriorityEncoder(excpCandidates)
  private val splitDoneSel = PriorityEncoder(splitDoneCandidates)
  private val mergeEntry     = io.entry(mergeSel)
  private val wbEntry        = io.entry(wbSel)
  private val excpEntry      = io.entry(excpSel)
  private val splitDoneEntry = io.entry(splitDoneSel)

  private val skipMerge = splitDoneEntry.entry.elemActiveMask.andR
  private val splitDoneStateNext = Mux(
    splitDoneEntry.entry.isStore | skipMerge,
    VAGQEntryState.wb,
    VAGQEntryState.merge
  )

  io.stateUpdate.valid := false.B
  io.stateUpdate.bits  := 0.U.asTypeOf(io.stateUpdate.bits)

  private val mergePendingEntryIdx          = RegInit(0.U(vagqEntryIdxWidth.W))
  private val mergePendingRobIdx            = RegInit(0.U.asTypeOf(new RobPtr))
  private val mergePendingPdest             = Reg(UInt(VfPhyRegIdxWidth.W))
  private val mergePendingWriteMask         = Reg(UInt(vagqFlowBytes.W))
  private val mergePendingAgnosticWriteMask = Reg(UInt(vagqFlowBytes.W))
  private val mergePendingEntry             = mergeEntryAt(io.entry, mergePendingEntryIdx, numEntries)
  private val mergePendingAlive             = mergePendingEntry.entry.state === VAGQEntryState.merge &&
                                              mergePendingEntry.entry.robIdx === mergePendingRobIdx &&
                                              entryAlive(mergePendingEntry.entry, io.redirect)

  private val mergeReadValid = RegInit(false.B)
  private val mergeReadAlive = mergeReadValid && mergePendingAlive

  private val mergeRespValid = RegInit(false.B)
  private val mergeRespData  = Reg(UInt(VLEN.W))
  private val mergeRespAlive = mergeRespValid && mergePendingAlive

  io.vrfReadReq.valid         := hasMerge && !mergeReadValid && !mergeRespValid
  io.vrfReadReq.bits.entryIdx := mergeEntry.entryIdx
  io.vrfReadReq.bits.robIdx   := mergeEntry.entry.robIdx
  io.vrfReadReq.bits.psrc     := mergeEntry.entry.psrc2

  private val mergeEntryWriteMask = ~mergeEntry.entry.elemActiveMask

  private val vrfReadRespAlive = mergeReadAlive &&
                                 io.vrfReadResp.valid &&
                                 io.vrfReadResp.bits.entryIdx === mergePendingEntryIdx &&
                                 io.vrfReadResp.bits.robIdx === mergePendingRobIdx

  private val mergeWriteData = mergeRespData | FillInterleaved(8, mergePendingAgnosticWriteMask)

  io.vrfWriteReq.valid := mergeRespAlive && !hasSplitDone
  io.vrfWriteReq.bits  := 0.U.asTypeOf(io.vrfWriteReq.bits)
  io.vrfWriteReq.bits.entryIdx := mergePendingEntryIdx
  io.vrfWriteReq.bits.pdest    := mergePendingPdest
  io.vrfWriteReq.bits.data     := mergeWriteData
  io.vrfWriteReq.bits.mask     := mergePendingWriteMask

  private val vrfWriteValid = io.vrfWriteReq.valid

  io.robWriteback.valid                := (hasWb || hasExcp) && !hasSplitDone && !vrfWriteValid
  io.robWriteback.bits                 := 0.U.asTypeOf(io.robWriteback.bits)
  io.robWriteback.bits.meta            := Mux(hasExcp, excpEntry.entry.meta, wbEntry.entry.meta)
  io.robWriteback.bits.entryIdx        := Mux(hasExcp, excpEntry.entryIdx, wbEntry.entryIdx)
  io.robWriteback.bits.robIdx          := Mux(hasExcp, excpEntry.entry.robIdx, wbEntry.entry.robIdx)
  io.robWriteback.bits.exception       := hasExcp
  io.robWriteback.bits.exceptionNumber := Mux(hasExcp, excpEntry.entry.exceptionNumber, 0.U)
  io.robWriteback.bits.faultElemIdx    := Mux(hasExcp, excpEntry.entry.faultElemIdx, 0.U)
  io.robWriteback.bits.faultVstart     := Mux(hasExcp, faultVstart(excpEntry.entry), 0.U)
  io.robWriteback.bits.uopType         := Mux(hasExcp, excpEntry.entry.uopType, wbEntry.entry.uopType)
  io.robWriteback.bits.uopIdx          := Mux(hasExcp, excpEntry.entry.uopIdx, wbEntry.entry.uopIdx)
  io.robWriteback.bits.deew            := Mux(hasExcp, excpEntry.entry.deew, wbEntry.entry.deew)
  io.robWriteback.bits.nf              := Mux(hasExcp, excpEntry.entry.nf, wbEntry.entry.nf)

  when(mergeReadValid) {
    when(!mergeReadAlive || vrfReadRespAlive) {
      mergeReadValid := false.B
    }
  }.elsewhen(io.vrfReadReq.valid) {
    mergeReadValid                := true.B
    mergePendingEntryIdx          := io.vrfReadReq.bits.entryIdx
    mergePendingRobIdx            := io.vrfReadReq.bits.robIdx
    mergePendingPdest             := mergeEntry.entry.pdest
    mergePendingWriteMask         := mergeEntryWriteMask
    mergePendingAgnosticWriteMask := mergeEntry.entry.elemAgnosticMask & mergeEntryWriteMask
  }

  when(mergeRespValid) {
    when(!mergeRespAlive || vrfWriteValid) {
      mergeRespValid := false.B
    }
  }.elsewhen(vrfReadRespAlive) {
    mergeRespValid := true.B
    mergeRespData  := io.vrfReadResp.bits.data
  }

  when(hasSplitDone) {
    io.stateUpdate.valid := true.B
    io.stateUpdate.bits.entryIdx  := splitDoneEntry.entryIdx
    io.stateUpdate.bits.stateNext := splitDoneStateNext
  }.elsewhen(vrfWriteValid) {
    io.stateUpdate.valid := true.B
    io.stateUpdate.bits.entryIdx  := io.vrfWriteReq.bits.entryIdx
    io.stateUpdate.bits.stateNext := VAGQEntryState.wb
  }.elsewhen(io.robWriteback.fire) {
    io.stateUpdate.valid := true.B
    io.stateUpdate.bits.entryIdx   := io.robWriteback.bits.entryIdx
    io.stateUpdate.bits.clearValid := true.B
  }
}

class MergeCtrlIO(numEntries: Int)(implicit p: Parameters) extends VAGQBundle {
  val entry        = Input(Vec(numEntries, new CtrlInput))
  val lduResp      = Flipped(Vec(VAGQConstants.LduRespWidth, Valid(new VAGQResp)))
  val staResp      = Flipped(Vec(VAGQConstants.StaRespWidth, Valid(new VAGQResp)))
  val lsqEmptyResp = Flipped(Valid(new VAGQResp))
  val reqUpdate    = Vec(VAGQConstants.MergeRespWidth, Valid(new VAGQReqBitmapUpdate))
  val stateUpdate  = Valid(new VAGQEntryStateUpdate)
  val vrfReadReq   = Valid(new VAGQVRFReadReq)
  val vrfReadResp  = Flipped(Valid(new VAGQVRFReadResp))
  val vrfWriteReq  = ValidIO(new VAGQVRFWriteReq)
  val robWriteback = Decoupled(new VAGQWritebackReq)
  val redirect     = Flipped(Valid(new Redirect))
}
