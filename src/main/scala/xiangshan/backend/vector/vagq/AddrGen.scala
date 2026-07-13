package xiangshan.backend.vector.vagq

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.Parameters


class AddrGen(implicit p: Parameters) extends VAGQModule {
  val in = IO(Input(new AddrGenInput))
  val out = IO(Output(new AddrGenOutput))

  private val elemIdx = in.elemIdx

  private val strideElemOrd = elemOrdFromUop(in.uopIdx, elemIdx, in.deew) // element ordinal from inst
  private val strideOffsetWide = in.op2Data(XLEN - 1, 0) * strideElemOrd
  private val strideOffset = strideOffsetWide(XLEN - 1, 0)

  private val indexByteOffset = MuxLookup(in.ieew, elemIdx)(Seq(
    0.U -> elemIdx,
    1.U -> Cat(elemIdx(2, 0), 0.U(1.W)),
    2.U -> Cat(elemIdx(1, 0), 0.U(2.W)),
    3.U -> Cat(elemIdx(0), 0.U(3.W)),
  ))
  private val indexSel64 = Mux(indexByteOffset(3), in.op2Data(127, 64), in.op2Data(63, 0))
  private val indexSel32 = Mux(indexByteOffset(2), indexSel64(63, 32), indexSel64(31, 0))
  private val indexSel16 = Mux(indexByteOffset(1), indexSel32(31, 16), indexSel32(15, 0))
  private val indexSel8  = Mux(indexByteOffset(0), indexSel16(15, 8), indexSel16(7, 0))
  private val indexOffset = MuxLookup(in.ieew, indexSel64)(Seq(
    0.U -> Cat(0.U((XLEN - 8).W), indexSel8),
    1.U -> Cat(0.U((XLEN - 16).W), indexSel16),
    2.U -> Cat(0.U((XLEN - 32).W), indexSel32),
    3.U -> indexSel64,
  ))

  private val offset = Mux(VAGQUopType.isStride(in.uopType), strideOffset, indexOffset)

  out.vaddr := in.baseAddr + offset
  out.elemIdx := elemIdx
}

class AddrGenInput(implicit p: Parameters) extends VAGQBundle {
  val uopType = UInt(3.W)
  val baseAddr = UInt(XLEN.W)
  val op2Data = UInt(VLEN.W)
  val uopIdx = UInt(vagqUopIdxWidth.W)
  val elemIdx = UInt(vagqFlowByteWidth.W)
  val deew = UInt(VAGQConstants.EewWidth.W)
  val ieew = UInt(VAGQConstants.EewWidth.W)
}

class AddrGenOutput(implicit p: Parameters) extends VAGQBundle {
  val vaddr = UInt(XLEN.W)
  val elemIdx = UInt(vagqFlowByteWidth.W)
}
