package xiangshan.backend.vector.vagq

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.Parameters

import xiangshan.backend.fu.NewCSR.CSRConfig

class MaskGen(implicit p: Parameters) extends VAGQModule {
  val in = IO(Input(new MaskGenInput))
  val out = IO(Output(new VAGQMaskInfo))

  private val zeroVstart = 0.U(in.vstart.getWidth.W)
  private val effectiveVstart = Mux(in.useVstart, in.vstart, zeroVstart)
  private val uvlByte = in.uvlByte
  private val uvstartByte = uopByteRangeLen(effectiveVstart, in.deew, in.uopIdx)
  private val v0ByteMask = MuxLookup(in.deew, in.v0Mask)(Seq(
    0.U -> in.v0Mask,
    1.U -> FillInterleaved(2, in.v0Mask(vagqFlowBytes / 2 - 1, 0)),
    2.U -> FillInterleaved(4, in.v0Mask(vagqFlowBytes / 4 - 1, 0)),
    3.U -> FillInterleaved(8, in.v0Mask(vagqFlowBytes / 8 - 1, 0)),
  ))

  private val tailBits     = Wire(Vec(vagqFlowBytes, Bool()))
  private val inactiveBits = Wire(Vec(vagqFlowBytes, Bool()))
  private val activeBits   = Wire(Vec(vagqFlowBytes, Bool()))

  for (byteIdx <- 0 until vagqFlowBytes) {
    val byteIdxUInt = byteIdx.U(vagqUvlByteWidth.W)
    val inPrestart = byteIdxUInt < uvstartByte
    val inTail = !inPrestart && byteIdxUInt >= uvlByte
    val maskActive = in.vm || v0ByteMask(byteIdx)

    tailBits(byteIdx)     := inTail
    inactiveBits(byteIdx) := !inPrestart && !inTail && !maskActive
    activeBits(byteIdx)   := !inPrestart && !inTail &&  maskActive
  }

  private val inactiveAgnosticMask = inactiveBits.asUInt & Fill(vagqFlowBytes, in.vma)
  private val tailAgnosticMask = tailBits.asUInt & Fill(vagqFlowBytes, in.vta)
  private val elemActiveMask = activeBits.asUInt
  private val elemAgnosticMask = inactiveAgnosticMask | tailAgnosticMask

  out.elemActiveMask := elemActiveMask
  out.elemAgnosticMask := elemAgnosticMask
}

class MaskGenInput(implicit p: Parameters) extends VAGQBundle {
  val uopIdx    = UInt(vagqUopIdxWidth.W)
  val useVstart = Bool()
  val vstart    = UInt((CSRConfig.VlWidth-1).W)
  val uvlByte   = UInt(5.W)
  val vm        = Bool()
  val v0Mask    = UInt(vagqFlowBytes.W)
  val deew      = UInt(VAGQConstants.EewWidth.W)
  val vma       = Bool()
  val vta       = Bool()
}

class VAGQMaskInfo(implicit p: Parameters) extends VAGQBundle {
  val elemActiveMask   = UInt(vagqFlowBytes.W)
  val elemAgnosticMask = UInt(vagqFlowBytes.W)
}
